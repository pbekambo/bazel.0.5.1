// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * This is PID 1 inside the sandbox environment and runs in a separate user,
 * mount, UTS, IPC and PID namespace.
 */

#include "linux-sandbox-options.h"
#include "linux-sandbox-utils.h"
#include "linux-sandbox.h"

// Note that we define DIE() here and not in a shared header, because we want to
// use _exit() in the
// pid1 child, but exit() in the parent.
#define DIE(args...)                                     \
  {                                                      \
    fprintf(stderr, __FILE__ ":" S__LINE__ ": \"" args); \
    fprintf(stderr, "\": ");                             \
    perror(NULL);                                        \
    _exit(EXIT_FAILURE);                                 \
  }

#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <math.h>
#include <mntent.h>
#include <net/if.h>
#include <pwd.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

static int global_child_pid;

static void SetupSelfDestruction(int *sync_pipe) {
  // We could also poll() on the pipe fd to find out when the parent goes away,
  // and rely on SIGCHLD interrupting that otherwise. That might require us to
  // install some trivial handler for SIGCHLD. Using O_ASYNC to turn the pipe
  // close into SIGIO may also work. Another option is signalfd, although that's
  // almost as obscure as this prctl.
  if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) {
    DIE("prctl");
  }

  // Verify that the parent still lives.
  char buf = 0;
  if (close(sync_pipe[0]) < 0) {
    DIE("close");
  }
  if (write(sync_pipe[1], &buf, 1) < 0) {
    DIE("write");
  }
  if (close(sync_pipe[1]) < 0) {
    DIE("close");
  }
}

static void SetupMountNamespace() {
  // Fully isolate our mount namespace private from outside events, so that
  // mounts in the outside environment do not affect our sandbox.
  if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) < 0) {
    DIE("mount");
  }
}

static void WriteFile(const std::string &filename, const char *fmt, ...) {
  FILE *stream = fopen(filename.c_str(), "w");
  if (stream == NULL) {
    DIE("fopen(%s)", filename.c_str());
  }

  va_list ap;
  va_start(ap, fmt);
  int r = vfprintf(stream, fmt, ap);
  va_end(ap);

  if (r < 0) {
    DIE("vfprintf");
  }

  if (fclose(stream) != 0) {
    DIE("fclose(%s)", filename.c_str());
  }
}

static void SetupUserNamespace() {
  // Disable needs for CAP_SETGID.
  struct stat sb;
  if (stat("/proc/self/setgroups", &sb) == 0) {
    WriteFile("/proc/self/setgroups", "deny");
  } else {
    // Ignore ENOENT, because older Linux versions do not have this file (but
    // also do not require writing to it).
    if (errno != ENOENT) {
      DIE("stat(/proc/self/setgroups");
    }
  }

  int inner_uid, inner_gid;
  if (opt.fake_root) {
    // Change our username to 'root'.
    inner_uid = 0;
    inner_gid = 0;
  } else if (opt.fake_username) {
    // Change our username to 'nobody'.
    struct passwd *pwd = getpwnam("nobody");
    if (pwd == NULL) {
      DIE("unable to find passwd entry for user nobody")
    }

    inner_uid = pwd->pw_uid;
    inner_gid = pwd->pw_gid;
  } else {
    // Do not change the username inside the sandbox.
    inner_uid = global_outer_uid;
    inner_gid = global_outer_gid;
  }

  WriteFile("/proc/self/uid_map", "%d %d 1\n", inner_uid, global_outer_uid);
  WriteFile("/proc/self/gid_map", "%d %d 1\n", inner_gid, global_outer_gid);
}

static void SetupUtsNamespace() {
  if (sethostname("localhost", 9) < 0) {
    DIE("sethostname");
  }

  if (setdomainname("localdomain", 11) < 0) {
    DIE("setdomainname");
  }
}

static void MountFilesystems() {
  for (const std::string &tmpfs_dir : opt.tmpfs_dirs) {
    PRINT_DEBUG("tmpfs: %s", tmpfs_dir.c_str());
    if (mount("tmpfs", tmpfs_dir.c_str(), "tmpfs",
              MS_NOSUID | MS_NODEV | MS_NOATIME, NULL) < 0) {
      DIE("mount(tmpfs, %s, tmpfs, MS_NOSUID | MS_NODEV | MS_NOATIME, NULL)",
          tmpfs_dir.c_str());
    }
  }

  // Make sure that our working directory is a mount point. The easiest way to
  // do this is by bind-mounting it upon itself.
  PRINT_DEBUG("working dir: %s", opt.working_dir.c_str());

  if (mount(opt.working_dir.c_str(), opt.working_dir.c_str(), NULL, MS_BIND,
            NULL) < 0) {
    DIE("mount(%s, %s, NULL, MS_BIND, NULL)", opt.working_dir.c_str(),
        opt.working_dir.c_str());
  }

  for (size_t i = 0; i < opt.bind_mount_sources.size(); i++) {
    std::string source = opt.bind_mount_sources.at(i);
    std::string target = opt.bind_mount_targets.at(i);
    PRINT_DEBUG("bind mount: %s -> %s", source.c_str(), target.c_str());
    if (mount(source.c_str(), target.c_str(), NULL, MS_BIND, NULL) < 0) {
      DIE("mount(%s, %s, NULL, MS_BIND, NULL)", source.c_str(), target.c_str());
    }
  }

  for (const std::string &writable_file : opt.writable_files) {
    PRINT_DEBUG("writable: %s", writable_file.c_str());
    if (mount(writable_file.c_str(), writable_file.c_str(), NULL, MS_BIND,
              NULL) < 0) {
      DIE("mount(%s, %s, NULL, MS_BIND, NULL)", writable_file.c_str(),
          writable_file.c_str());
    }
  }
}

// We later remount everything read-only, except the paths for which this method
// returns true.
static bool ShouldBeWritable(const std::string &mnt_dir) {
  if (mnt_dir == opt.working_dir) {
    return true;
  }

  for (const std::string &writable_file : opt.writable_files) {
    if (mnt_dir == writable_file) {
      return true;
    }
  }

  for (const std::string &tmpfs_dir : opt.tmpfs_dirs) {
    if (mnt_dir == tmpfs_dir) {
      return true;
    }
  }

  return false;
}

// Makes the whole filesystem read-only, except for the paths for which
// ShouldBeWritable returns true.
static void MakeFilesystemMostlyReadOnly() {
  FILE *mounts = setmntent("/proc/self/mounts", "r");
  if (mounts == NULL) {
    DIE("setmntent");
  }

  struct mntent *ent;
  while ((ent = getmntent(mounts)) != NULL) {
    int mountFlags = MS_BIND | MS_REMOUNT;

    // MS_REMOUNT does not allow us to change certain flags. This means, we have
    // to first read them out and then pass them in back again. There seems to
    // be no better way than this (an API for just getting the mount flags of a
    // mount entry as a bitmask would be great).
    if (hasmntopt(ent, "nodev") != NULL) {
      mountFlags |= MS_NODEV;
    }
    if (hasmntopt(ent, "noexec") != NULL) {
      mountFlags |= MS_NOEXEC;
    }
    if (hasmntopt(ent, "nosuid") != NULL) {
      mountFlags |= MS_NOSUID;
    }
    if (hasmntopt(ent, "noatime") != NULL) {
      mountFlags |= MS_NOATIME;
    }
    if (hasmntopt(ent, "nodiratime") != NULL) {
      mountFlags |= MS_NODIRATIME;
    }
    if (hasmntopt(ent, "relatime") != NULL) {
      mountFlags |= MS_RELATIME;
    }

    if (!ShouldBeWritable(ent->mnt_dir)) {
      mountFlags |= MS_RDONLY;
    }

    PRINT_DEBUG("remount %s: %s", (mountFlags & MS_RDONLY) ? "ro" : "rw",
                ent->mnt_dir);
    if (mount(NULL, ent->mnt_dir, NULL, mountFlags, NULL) < 0) {
      // If we get EACCES or EPERM, this might be a mount-point for which we
      // don't have read access. Not much we can do about this, but it also
      // won't do any harm, so let's go on. The same goes for EINVAL or ENOENT,
      // which are fired in case a later mount overlaps an earlier mount, e.g.
      // consider the case of /proc, /proc/sys/fs/binfmt_misc and /proc, with
      // the latter /proc being the one that an outer sandbox has mounted on
      // top of its parent /proc. In that case, we're not allowed to remount
      // /proc/sys/fs/binfmt_misc, because it is hidden. If we get ESTALE, the
      // mount is a broken NFS mount. In the ideal case, the user would either
      // fix or remove that mount, but in cases where that's not possible, we
      // should just ignore it.
      if (errno != EACCES && errno != EPERM && errno != EINVAL &&
          errno != ENOENT && errno != ESTALE) {
        DIE("remount(NULL, %s, NULL, %d, NULL)", ent->mnt_dir, mountFlags);
      }
    }
  }

  endmntent(mounts);
}

static void MountProc() {
  // Mount a new proc on top of the old one, because the old one still refers to
  // our parent PID namespace.
  if (mount("/proc", "/proc", "proc", MS_NODEV | MS_NOEXEC | MS_NOSUID, NULL) <
      0) {
    DIE("mount");
  }
}

static void SetupNetworking() {
  // When running in a separate network namespace, enable the loopback interface
  // because some application may want to use it.
  if (opt.create_netns) {
    int fd;
    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
      DIE("socket");
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, "lo", IF_NAMESIZE);

    // Verify that name is valid.
    if (if_nametoindex(ifr.ifr_name) == 0) {
      DIE("if_nametoindex");
    }

    // Enable the interface.
    ifr.ifr_flags |= IFF_UP;
    if (ioctl(fd, SIOCSIFFLAGS, &ifr) < 0) {
      DIE("ioctl");
    }

    if (close(fd) < 0) {
      DIE("close");
    }
  }
}

static void EnterSandbox() {
  if (chdir(opt.working_dir.c_str()) < 0) {
    DIE("chdir(%s)", opt.working_dir.c_str());
  }
}

static void InstallSignalHandler(int signum, void (*handler)(int)) {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = handler;
  if (handler == SIG_IGN || handler == SIG_DFL) {
    // No point in blocking signals when using the default handler or ignoring
    // the signal.
    if (sigemptyset(&sa.sa_mask) < 0) {
      DIE("sigemptyset");
    }
  } else {
    // When using a custom handler, block all signals from firing while the
    // handler is running.
    if (sigfillset(&sa.sa_mask) < 0) {
      DIE("sigfillset");
    }
  }
  // sigaction may fail for certain reserved signals. Ignore failure in this
  // case, but report it in debug mode, just in case.
  if (sigaction(signum, &sa, NULL) < 0) {
    PRINT_DEBUG("sigaction(%d, &sa, NULL) failed", signum);
  }
}

static void IgnoreSignal(int signum) { InstallSignalHandler(signum, SIG_IGN); }

// Reset the signal mask and restore the default handler for all signals.
static void RestoreSignalHandlersAndMask() {
  // Use an empty signal mask for the process (= unblock all signals).
  sigset_t empty_set;
  if (sigemptyset(&empty_set) < 0) {
    DIE("sigemptyset");
  }
  if (sigprocmask(SIG_SETMASK, &empty_set, nullptr) < 0) {
    DIE("sigprocmask(SIG_SETMASK, <empty set>, nullptr)");
  }

  // Set the default signal handler for all signals.
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  if (sigemptyset(&sa.sa_mask) < 0) {
    DIE("sigemptyset");
  }
  sa.sa_handler = SIG_DFL;
  for (int i = 1; i < NSIG; ++i) {
    // Ignore possible errors, because we might not be allowed to set the
    // handler for certain signals, but we still want to try.
    sigaction(i, &sa, nullptr);
  }
}

static void ForwardSignal(int signum) {
  PRINT_DEBUG("ForwardSignal(%d)", signum);
  kill(-global_child_pid, signum);
}

static void SetupSignalHandlers() {
  RestoreSignalHandlersAndMask();

  for (int signum = 1; signum < NSIG; signum++) {
    switch (signum) {
      // Some signals should indeed kill us and not be forwarded to the child,
      // thus we can use the default handler.
      case SIGABRT:
      case SIGBUS:
      case SIGFPE:
      case SIGILL:
      case SIGSEGV:
      case SIGSYS:
      case SIGTRAP:
        break;
      // It's fine to use the default handler for SIGCHLD, because we use
      // waitpid() in the main loop to wait for children to die anyway.
      case SIGCHLD:
        break;
      // One does not simply install a signal handler for these two signals
      case SIGKILL:
      case SIGSTOP:
        break;
      // Ignore SIGTTIN and SIGTTOU, as we hand off the terminal to the child in
      // SpawnChild().
      case SIGTTIN:
      case SIGTTOU:
        IgnoreSignal(signum);
        break;
      // All other signals should be forwarded to the child.
      default:
        InstallSignalHandler(signum, ForwardSignal);
        break;
    }
  }
}

static void SpawnChild() {
  global_child_pid = fork();

  if (global_child_pid < 0) {
    DIE("fork()");
  } else if (global_child_pid == 0) {
    // Put the child into its own process group.
    if (setpgid(0, 0) < 0) {
      DIE("setpgid");
    }

    // Try to assign our terminal to the child process.
    if (tcsetpgrp(STDIN_FILENO, getpgrp()) < 0 && errno != ENOTTY) {
      DIE("tcsetpgrp")
    }

    // Unblock all signals, restore default handlers.
    RestoreSignalHandlersAndMask();

    // Force umask to include read and execute for everyone, to make output
    // permissions predictable.
    umask(022);

    // argv[] passed to execve() must be a null-terminated array.
    opt.args.push_back(nullptr);

    if (execvp(opt.args[0], opt.args.data()) < 0) {
      DIE("execvp(%s, %p)", opt.args[0], opt.args.data());
    }
  }
}

static void WaitForChild() {
  while (1) {
    // Check for zombies to be reaped and exit, if our own child exited.
    int status;
    pid_t killed_pid = waitpid(-1, &status, 0);
    PRINT_DEBUG("waitpid returned %d", killed_pid);

    if (killed_pid < 0) {
      // Our PID1 process got a signal that interrupted the waitpid() call and
      // that was either ignored or forwared to the child. This is expected &
      // fine, just continue waiting.
      if (errno == EINTR) {
        continue;
      }
      DIE("waitpid")
    } else {
      if (killed_pid == global_child_pid) {
        // If the child process we spawned earlier terminated, we'll also
        // terminate. We can simply _exit() here, because the Linux kernel will
        // kindly SIGKILL all remaining processes in our PID namespace once we
        // exit.
        if (WIFSIGNALED(status)) {
          PRINT_DEBUG("child died due to signal %d", WTERMSIG(status));
          _exit(128 + WTERMSIG(status));
        } else {
          PRINT_DEBUG("child exited with code %d", WEXITSTATUS(status));
          _exit(WEXITSTATUS(status));
        }
      }
    }
  }
}

int Pid1Main(void *sync_pipe_param) {
  if (getpid() != 1) {
    DIE("Using PID namespaces, but we are not PID 1");
  }

  SetupSelfDestruction(reinterpret_cast<int *>(sync_pipe_param));
  SetupMountNamespace();
  SetupUserNamespace();
  if (opt.fake_hostname) {
    SetupUtsNamespace();
  }
  MountFilesystems();
  MakeFilesystemMostlyReadOnly();
  MountProc();
  SetupNetworking();
  EnterSandbox();
  SetupSignalHandlers();
  SpawnChild();
  WaitForChild();
  _exit(EXIT_FAILURE);
}

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

package com.google.devtools.build.lib.remote;

import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;

/** Options for remote execution and distributed caching. */
public final class RemoteOptions extends OptionsBase {
  @Option(
    name = "rest_cache_url",
    defaultValue = "null",
    category = "remote",
    help =
        "A base URL for a RESTful cache server for storing build artifacts."
            + "It has to support PUT, GET, and HEAD requests."
  )
  public String restCacheUrl;

  @Option(
    name = "hazelcast_node",
    defaultValue = "null",
    category = "remote",
    help = "A comma separated list of hostnames of hazelcast nodes."
  )
  public String hazelcastNode;

  @Option(
    name = "hazelcast_client_config",
    defaultValue = "null",
    category = "remote",
    help = "A file path to a hazelcast client config XML file."
  )
  public String hazelcastClientConfig;

  @Option(
    name = "hazelcast_standalone_listen_port",
    defaultValue = "0",
    category = "build_worker",
    help =
        "Runs an embedded hazelcast server that listens to this port. The server does not join"
            + " any cluster. This is useful for testing."
  )
  public int hazelcastStandaloneListenPort;

  @Option(
    name = "remote_worker",
    defaultValue = "null",
    category = "remote",
    help = "Hostname and port number of remote worker in the form of host:port. "
  )
  public String remoteWorker;

  @Option(
    name = "remote_cache",
    defaultValue = "null",
    category = "remote",
    help = "Hostname and port number of remote gRPC cache in the form of host:port. "
  )
  public String remoteCache;

  @Option(
    name = "grpc_max_chunk_size_bytes",
    defaultValue = "16000",
    category = "remote",
    help = "The maximal number of data bytes to be sent in a single message."
  )
  public int grpcMaxChunkSizeBytes;

  @Option(
    name = "grpc_max_batch_inputs",
    defaultValue = "100",
    category = "remote",
    help = "The maximal number of input files to be sent in a single batch."
  )
  public int grpcMaxBatchInputs;

  @Option(
    name = "grpc_max_batch_size_bytes",
    defaultValue = "10485760", // 10MB
    category = "remote",
    help = "The maximal number of input bytes to be sent in a single batch."
  )
  public int grpcMaxBatchSizeBytes;

  @Option(
    name = "grpc_timeout_seconds",
    defaultValue = "60",
    category = "remote",
    help = "The maximal number of seconds to wait for remote calls."
  )
  public int grpcTimeoutSeconds;

  @Option(
    name = "remote_accept_cached",
    defaultValue = "true",
    category = "remote",
    help = "Whether to accept remotely cached action results."
  )
  public boolean remoteAcceptCached;

  @Option(
    name = "remote_allow_local_fallback",
    defaultValue = "true",
    category = "remote",
    help = "Whether to fall back to standalone strategy if remote fails."
  )
  public boolean remoteAllowLocalFallback;

  @Option(
    name = "remote_local_exec_upload_results",
    defaultValue = "true",
    category = "remote",
    help = "Whether to upload action results to the remote cache after executing locally."
  )
  public boolean remoteLocalExecUploadResults;

  @Option(
    name = "experimental_remote_platform_override",
    defaultValue = "null",
    category = "remote",
    help = "Temporary, for testing only. Manually set a Platform to pass to remote execution."
  )
  public String experimentalRemotePlatformOverride;

  @Option(
    name = "auth_enabled",
    defaultValue = "false",
    category = "remote",
    help = "Whether to enable API key authentication."
  )
  public boolean authEnabled;

  @Option(
    name = "auth_scope",
    defaultValue = "null",
    category = "remote",
    help = "If server authentication requires a scope, provide it here."
  )
  public String authScope;

  @Option(
    name = "auth_credentials_json",
    defaultValue = "null",
    category = "remote",
    help = "Location of credentials JSON file."
  )
  public String authCredentialsJson;

  @Option(
    name = "tls_enabled",
    defaultValue = "false",
    category = "remote",
    help =
        "If set to true, Bazel uses TLS encryption for all connections to remote cache and "
            + "execution servers."
  )
  public boolean tlsEnabled;

  @Option(
    name = "tls_cert",
    defaultValue = "null",
    category = "remote",
    help = "TLS certificate file to use."
  )
  public String tlsCert;

  @Option(
    name = "tls_authority_override",
    defaultValue = "null",
    category = "remote",
    help =
        "If present, consider the value of the flag a valid TLS authority. This is useful for "
            + "using self-signed test TLS certificates. For testing only."
  )
  public String tlsAuthorityOverride;
}

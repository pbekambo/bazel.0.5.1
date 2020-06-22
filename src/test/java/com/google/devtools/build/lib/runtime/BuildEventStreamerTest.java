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
package com.google.devtools.build.lib.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.EventReportingArtifacts;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.buildeventstream.AnnounceBuildEventTransportsEvent;
import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventConverters;
import com.google.devtools.build.lib.buildeventstream.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId.NamedSetOfFilesId;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransportClosedEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventWithOrderConstraint;
import com.google.devtools.build.lib.buildeventstream.GenericBuildEvent;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.buildeventstream.ProgressEvent;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.NestedSetView;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Tests {@link BuildEventStreamer}. */
@RunWith(JUnit4.class)
public class BuildEventStreamerTest extends FoundationTestCase {

  private static class RecordingBuildEventTransport implements BuildEventTransport {
    private final List<BuildEvent> events = new ArrayList<>();
    private final List<BuildEventStreamProtos.BuildEvent> eventsAsProtos = new ArrayList<>();

    @Override
    public String name() {
      return this.getClass().getSimpleName();
    }

    @Override
    public void sendBuildEvent(BuildEvent event, final ArtifactGroupNamer namer) {
      events.add(event);
      eventsAsProtos.add(
          event.asStreamProto(
              new BuildEventConverters() {
                @Override
                public ArtifactGroupNamer artifactGroupNamer() {
                  return namer;
                }

                @Override
                public PathConverter pathConverter() {
                  return new PathConverter() {
                    @Override
                    public String apply(Path path) {
                      return path.toString();
                    }
                  };
                }
              }));
    }

    @Override
    public ListenableFuture<Void> close() {
      return Futures.immediateFuture(null);
    }

    List<BuildEvent> getEvents() {
      return events;
    }

    List<BuildEventStreamProtos.BuildEvent> getEventProtos() {
      return eventsAsProtos;
    }
  }

  private static class GenericOrderEvent implements BuildEventWithOrderConstraint {
    private final BuildEventId id;
    private final Collection<BuildEventId> children;
    private final Collection<BuildEventId> after;

    GenericOrderEvent(
        BuildEventId id, Collection<BuildEventId> children, Collection<BuildEventId> after) {
      this.id = id;
      this.children = children;
      this.after = after;
    }

    GenericOrderEvent(BuildEventId id, Collection<BuildEventId> children) {
      this(id, children, children);
    }

    @Override
    public BuildEventId getEventId() {
      return id;
    }

    @Override
    public Collection<BuildEventId> getChildrenEvents() {
      return children;
    }

    @Override
    public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventConverters converters) {
      return GenericBuildEvent.protoChaining(this).build();
    }

    @Override
    public Collection<BuildEventId> postedAfter() {
      return after;
    }
  }

  private static class GenericArtifactReportingEvent implements EventReportingArtifacts {
    private final BuildEventId id;
    private final Collection<BuildEventId> children;
    private final Collection<NestedSet<Artifact>> artifacts;

    GenericArtifactReportingEvent(
        BuildEventId id,
        Collection<BuildEventId> children,
        Collection<NestedSet<Artifact>> artifacts) {
      this.id = id;
      this.children = children;
      this.artifacts = artifacts;
    }

    GenericArtifactReportingEvent(BuildEventId id, Collection<NestedSet<Artifact>> artifacts) {
      this(id, ImmutableSet.<BuildEventId>of(), artifacts);
    }

    @Override
    public BuildEventId getEventId() {
      return id;
    }

    @Override
    public Collection<BuildEventId> getChildrenEvents() {
      return children;
    }

    @Override
    public Collection<NestedSet<Artifact>> reportedArtifacts() {
      return artifacts;
    }

    @Override
    public BuildEventStreamProtos.BuildEvent asStreamProto(BuildEventConverters converters) {
      BuildEventStreamProtos.NamedSetOfFiles.Builder builder =
          BuildEventStreamProtos.NamedSetOfFiles.newBuilder();
      for (NestedSet<Artifact> artifactset : artifacts) {
        builder.addFileSets(
            converters
                .artifactGroupNamer()
                .apply((new NestedSetView<Artifact>(artifactset)).identifier()));
      }
      return GenericBuildEvent.protoChaining(this).setNamedSetOfFiles(builder.build()).build();
    }
  }

  private static BuildEventId testId(String opaque) {
    return BuildEventId.unknownBuildEventId(opaque);
  }

  private static class EventBusHandler {

    Set<BuildEventTransport> transportSet;

    @Subscribe
    void transportsAnnounced(AnnounceBuildEventTransportsEvent evt) {
      transportSet = Collections.synchronizedSet(new HashSet<>(evt.transports()));
    }

    @Subscribe
    void transportClosed(BuildEventTransportClosedEvent evt) {
      transportSet.remove(evt.transport());
    }
  }

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  @Test(timeout = 5000)
  public void testSimpleStream() {
    // Verify that a well-formed event is passed through and that completion of the
    // build clears the pending progress-update event.

    EventBusHandler handler = new EventBusHandler();
    eventBus.register(handler);
    assertNull(handler.transportSet);

    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"), ImmutableSet.of(ProgressEvent.INITIAL_PROGRESS_UPDATE,
            BuildEventId.buildFinished()));

    streamer.buildEvent(startEvent);

    List<BuildEvent> afterFirstEvent = transport.getEvents();
    assertThat(afterFirstEvent).hasSize(1);
    assertEquals(startEvent.getEventId(), afterFirstEvent.get(0).getEventId());
    assertEquals(1, handler.transportSet.size());

    streamer.buildEvent(new BuildCompleteEvent(new BuildResult(0)));

    List<BuildEvent> finalStream = transport.getEvents();
    assertThat(finalStream).hasSize(3);
    assertEquals(BuildEventId.buildFinished(), finalStream.get(1).getEventId());
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, finalStream.get(2).getEventId());

    while (!handler.transportSet.isEmpty()) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
    }
  }

  @Test
  public void testChaining() {
    // Verify that unannounced events are linked in with progress update events, assuming
    // a correctly formed initial event.

    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"), ImmutableSet.of(ProgressEvent.INITIAL_PROGRESS_UPDATE));
    BuildEvent unexpectedEvent =
        new GenericBuildEvent(testId("unexpected"), ImmutableSet.<BuildEventId>of());

    streamer.buildEvent(startEvent);
    streamer.buildEvent(unexpectedEvent);

    List<BuildEvent> eventsSeen = transport.getEvents();
    assertThat(eventsSeen).hasSize(3);
    assertEquals(startEvent.getEventId(), eventsSeen.get(0).getEventId());
    assertEquals(unexpectedEvent.getEventId(), eventsSeen.get(2).getEventId());
    BuildEvent linkEvent = eventsSeen.get(1);
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, linkEvent.getEventId());
    assertTrue(
        "Unexpected events should be linked",
        linkEvent.getChildrenEvents().contains(unexpectedEvent.getEventId()));
  }

  @Test
  public void testBadInitialEvent() {
    // Verify that, if the initial event does not announce the initial progress update event,
    // the initial progress event is used instead to chain that event; in this way, new
    // progress updates can always be chained in.

    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEvent unexpectedStartEvent =
        new GenericBuildEvent(testId("unexpected start"), ImmutableSet.<BuildEventId>of());

    streamer.buildEvent(unexpectedStartEvent);

    List<BuildEvent> eventsSeen = transport.getEvents();
    assertThat(eventsSeen).hasSize(2);
    assertEquals(unexpectedStartEvent.getEventId(), eventsSeen.get(1).getEventId());
    BuildEvent initial = eventsSeen.get(0);
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, initial.getEventId());
    assertTrue(
        "Event should be linked",
        initial.getChildrenEvents().contains(unexpectedStartEvent.getEventId()));

    // The initial event should also announce a new progress event; we test this
    // by streaming another unannounced event.

    BuildEvent unexpectedEvent =
        new GenericBuildEvent(testId("unexpected"), ImmutableSet.<BuildEventId>of());

    streamer.buildEvent(unexpectedEvent);
    List<BuildEvent> allEventsSeen = transport.getEvents();
    assertThat(allEventsSeen).hasSize(4);
    assertEquals(unexpectedEvent.getEventId(), allEventsSeen.get(3).getEventId());
    BuildEvent secondLinkEvent = allEventsSeen.get(2);
    assertTrue(
        "Progress should have been announced",
        initial.getChildrenEvents().contains(secondLinkEvent.getEventId()));
    assertTrue(
        "Second event should be linked",
        secondLinkEvent.getChildrenEvents().contains(unexpectedEvent.getEventId()));
  }

  @Test
  public void testReferPastEvent() {
    // Verify that, if an event is refers to a previously done event, that duplicated
    // late-referenced event is not expected again.
    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"),
            ImmutableSet.<BuildEventId>of(ProgressEvent.INITIAL_PROGRESS_UPDATE,
                BuildEventId.buildFinished()));
    BuildEvent earlyEvent =
        new GenericBuildEvent(testId("unexpected"), ImmutableSet.<BuildEventId>of());
    BuildEvent lateReference =
        new GenericBuildEvent(testId("late reference"), ImmutableSet.of(earlyEvent.getEventId()));

    streamer.buildEvent(startEvent);
    streamer.buildEvent(earlyEvent);
    streamer.buildEvent(lateReference);
    streamer.buildEvent(new BuildCompleteEvent(new BuildResult(0)));

    List<BuildEvent> eventsSeen = transport.getEvents();
    int earlyEventCount = 0;
    for (BuildEvent event : eventsSeen) {
      if (event.getEventId().equals(earlyEvent.getEventId())) {
        earlyEventCount++;
      }
    }
    // The early event should be reported precisely once.
    assertEquals(1, earlyEventCount);
  }

  @Test
  public void testReodering() {
    // Verify that an event requiring to be posted after another one is indeed.

    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEventId expectedId = testId("the target");
    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"),
            ImmutableSet.<BuildEventId>of(ProgressEvent.INITIAL_PROGRESS_UPDATE, expectedId));
    BuildEvent rootCause =
        new GenericBuildEvent(testId("failure event"), ImmutableSet.<BuildEventId>of());
    BuildEvent failedTarget =
        new GenericOrderEvent(expectedId, ImmutableSet.<BuildEventId>of(rootCause.getEventId()));

    streamer.buildEvent(startEvent);
    streamer.buildEvent(failedTarget);
    streamer.buildEvent(rootCause);

    List<BuildEvent> allEventsSeen = transport.getEvents();
    assertThat(allEventsSeen).hasSize(4);
    assertEquals(startEvent.getEventId(), allEventsSeen.get(0).getEventId());
    BuildEvent linkEvent = allEventsSeen.get(1);
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, linkEvent.getEventId());
    assertEquals(rootCause.getEventId(), allEventsSeen.get(2).getEventId());
    assertEquals(failedTarget.getEventId(), allEventsSeen.get(3).getEventId());
  }

  @Test
  public void testMissingPrerequisits() {
    // Verify that an event where the prerequisite is never coming till the end of
    // the build still gets posted, with the prerequisite aborted.

    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEventId expectedId = testId("the target");
    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"),
            ImmutableSet.<BuildEventId>of(ProgressEvent.INITIAL_PROGRESS_UPDATE, expectedId,
                BuildEventId.buildFinished()));
    BuildEventId rootCauseId = testId("failure event");
    BuildEvent failedTarget =
        new GenericOrderEvent(expectedId, ImmutableSet.<BuildEventId>of(rootCauseId));

    streamer.buildEvent(startEvent);
    streamer.buildEvent(failedTarget);
    streamer.buildEvent(new BuildCompleteEvent(new BuildResult(0)));

    List<BuildEvent> allEventsSeen = transport.getEvents();
    assertThat(allEventsSeen).hasSize(6);
    assertEquals(startEvent.getEventId(), allEventsSeen.get(0).getEventId());
    assertEquals(BuildEventId.buildFinished(), allEventsSeen.get(1).getEventId());
    BuildEvent linkEvent = allEventsSeen.get(2);
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, linkEvent.getEventId());
    assertEquals(rootCauseId, allEventsSeen.get(3).getEventId());
    assertEquals(failedTarget.getEventId(), allEventsSeen.get(4).getEventId());
  }

  @Test
  public void testVeryFirstEventNeedsToWait() {
    // Verify that we can handle an first event waiting for another event.
    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEventId initialId = testId("Initial");
    BuildEventId waitId = testId("Waiting for initial event");
    BuildEvent startEvent =
        new GenericBuildEvent(
            initialId,
            ImmutableSet.<BuildEventId>of(ProgressEvent.INITIAL_PROGRESS_UPDATE, waitId));
    BuildEvent waitingForStart =
        new GenericOrderEvent(waitId, ImmutableSet.<BuildEventId>of(), ImmutableSet.of(initialId));

    streamer.buildEvent(waitingForStart);
    streamer.buildEvent(startEvent);

    List<BuildEvent> allEventsSeen = transport.getEvents();
    assertThat(allEventsSeen).hasSize(2);
    assertEquals(startEvent.getEventId(), allEventsSeen.get(0).getEventId());
    assertEquals(waitingForStart.getEventId(), allEventsSeen.get(1).getEventId());
  }

  private Artifact makeArtifact(String pathString) {
    Path path = outputBase.getRelative(PathFragment.create(pathString));
    return new Artifact(path, Root.asSourceRoot(path));
  }

  @Test
  public void testReportedArtifacts() {
    // Verify that reported artifacts are correctly unfolded into the stream
    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);

    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"),
            ImmutableSet.<BuildEventId>of(ProgressEvent.INITIAL_PROGRESS_UPDATE));

    Artifact a = makeArtifact("path/a");
    Artifact b = makeArtifact("path/b");
    Artifact c = makeArtifact("path/c");
    NestedSet<Artifact> innerGroup = NestedSetBuilder.<Artifact>stableOrder().add(a).add(b).build();
    NestedSet<Artifact> group =
        NestedSetBuilder.<Artifact>stableOrder().addTransitive(innerGroup).add(c).build();
    BuildEvent reportingArtifacts =
        new GenericArtifactReportingEvent(testId("reporting"), ImmutableSet.of(group));

    streamer.buildEvent(startEvent);
    streamer.buildEvent(reportingArtifacts);

    List<BuildEvent> allEventsSeen = transport.getEvents();
    List<BuildEventStreamProtos.BuildEvent> eventProtos = transport.getEventProtos();
    assertEquals(7, allEventsSeen.size());
    assertEquals(startEvent.getEventId(), allEventsSeen.get(0).getEventId());
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, allEventsSeen.get(1).getEventId());
    List<BuildEventStreamProtos.File> firstSetDirects =
        eventProtos.get(2).getNamedSetOfFiles().getFilesList();
    assertEquals(2, firstSetDirects.size());
    assertEquals(
        ImmutableSet.of(a.getPath().toString(), b.getPath().toString()),
        ImmutableSet.of(firstSetDirects.get(0).getUri(), firstSetDirects.get(1).getUri()));
    List<NamedSetOfFilesId> secondSetTransitives =
        eventProtos.get(4).getNamedSetOfFiles().getFileSetsList();
    assertEquals(1, secondSetTransitives.size());
    assertEquals(eventProtos.get(2).getId().getNamedSet(), secondSetTransitives.get(0));
    List<NamedSetOfFilesId> reportedArtifactSets =
        eventProtos.get(6).getNamedSetOfFiles().getFileSetsList();
    assertEquals(1, reportedArtifactSets.size());
    assertEquals(eventProtos.get(4).getId().getNamedSet(), reportedArtifactSets.get(0));
  }

  @Test
  public void testStdoutReported() {
    // Verify that stdout and stderr are reported in the build-event stream on progress
    // events.
    RecordingBuildEventTransport transport = new RecordingBuildEventTransport();
    BuildEventStreamer streamer =
        new BuildEventStreamer(ImmutableSet.<BuildEventTransport>of(transport), reporter);
    BuildEventStreamer.OutErrProvider outErr =
        Mockito.mock(BuildEventStreamer.OutErrProvider.class);
    String stdoutMsg = "Some text that was written to stdout.";
    String stderrMsg = "The UI text that bazel wrote to stderr.";
    when(outErr.getOut()).thenReturn(stdoutMsg);
    when(outErr.getErr()).thenReturn(stderrMsg);
    BuildEvent startEvent =
        new GenericBuildEvent(
            testId("Initial"),
            ImmutableSet.<BuildEventId>of(ProgressEvent.INITIAL_PROGRESS_UPDATE));
    BuildEvent unexpectedEvent =
        new GenericBuildEvent(testId("unexpected"), ImmutableSet.<BuildEventId>of());

    streamer.registerOutErrProvider(outErr);
    streamer.buildEvent(startEvent);
    streamer.buildEvent(unexpectedEvent);

    List<BuildEvent> eventsSeen = transport.getEvents();
    assertThat(eventsSeen).hasSize(3);
    assertEquals(startEvent.getEventId(), eventsSeen.get(0).getEventId());
    assertEquals(unexpectedEvent.getEventId(), eventsSeen.get(2).getEventId());
    BuildEvent linkEvent = eventsSeen.get(1);
    BuildEventStreamProtos.BuildEvent linkEventProto = transport.getEventProtos().get(1);
    assertEquals(ProgressEvent.INITIAL_PROGRESS_UPDATE, linkEvent.getEventId());
    assertTrue(
        "Unexpected events should be linked",
        linkEvent.getChildrenEvents().contains(unexpectedEvent.getEventId()));
    assertEquals(stdoutMsg, linkEventProto.getProgress().getStdout());
    assertEquals(stderrMsg, linkEventProto.getProgress().getStderr());

    // As there is only one progress event, the OutErrProvider should be queried
    // only once for stdout and stderr.
    verify(outErr, times(1)).getOut();
    verify(outErr, times(1)).getErr();
  }
}

package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EffectStateTest {
    @Test void continuousScrubbingSendsOnlyTheLatestSampleAtTwentyHertzAndAuditionsEveryMove() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); var auditioned = new ArrayList<Integer>();
        state.transport(sent::add); state.localControl((action, frame) -> auditioned.add(frame)); state.update(snapshot(0));
        state.scrub(1, 0); state.acknowledge(state.inFlightRequest(), 1_000_000);
        for (int frame = 2; frame < 50; frame++) state.scrub(frame, frame * 1_000_000L);
        state.tick(49_999_999); assertEquals(1, sent.size()); assertTrue(state.controlPending());
        state.tick(50_000_000); assertEquals(2, sent.size()); assertEquals(49, ((EffectProtocol.Edit) sent.getLast()).frame());
        state.acknowledge(state.inFlightRequest(), 50_000_000);
        for (int frame = 50; frame < 100; frame++) state.scrub(frame, frame * 1_000_000L);
        state.tick(99_999_999); assertEquals(2, sent.size());
        state.tick(100_000_000); assertEquals(3, sent.size()); assertEquals(99, ((EffectProtocol.Edit) sent.getLast()).frame());
        assertEquals(99, auditioned.size()); assertEquals(99, auditioned.getLast());
    }
    @Test void releasingScrubImmediatelySendsTheFinalFrameAndAvoidsDuplicateFinalRequests() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.scrub(1, 0); state.acknowledge(state.inFlightRequest(), 1_000_000);
        state.scrub(10, 10_000_000); assertEquals(1, sent.size());
        state.finishScrub(20, 20_000_000); assertEquals(2, sent.size()); assertEquals(20, ((EffectProtocol.Edit) sent.getLast()).frame());
        state.finishScrub(20, 21_000_000); assertEquals(2, sent.size());
        state.update(new EffectProtocol.Transport(0, "intro", false, false, false, 20));
        state.acknowledge(state.inFlightRequest(), 22_000_000); state.finishScrub(20, 23_000_000);
        assertEquals(2, sent.size()); assertFalse(state.busy());
    }
    @Test void releasingWhileARequestIsInFlightPreservesTheLastFrameWithoutWaitingForTheRateGate() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.scrub(1, 0); long first = state.inFlightRequest();
        state.scrub(10, 1_000_000); state.scrub(40, 2_000_000); state.finishScrub(50, 3_000_000);
        assertEquals(1, sent.size()); state.acknowledge(first, 4_000_000);
        assertEquals(2, sent.size()); assertEquals(50, ((EffectProtocol.Edit) sent.getLast()).frame());
    }
    @Test void discreteSeeksRemainImmediateAndHeldScrubsStayBeforePlaybackAndKeyEdits() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.scrub(1, 0); state.acknowledge(state.inFlightRequest(), 1_000_000);
        state.seek(8); assertNotEquals(0, state.inFlightRequest()); assertEquals(2, sent.size());
        state.acknowledge(state.inFlightRequest(), 2_000_000);
        state.scrub(10, 3_000_000); assertEquals(2, sent.size()); state.action("play");
        assertEquals(3, sent.size()); assertEquals(10, ((EffectProtocol.Edit) sent.getLast()).frame());
        state.scrub(20, 4_000_000); state.key("title", "offset.y", new EffectProtocol.Key(20, "7", "linear", .3, 0, .7, 1), 20);
        while (state.inFlightRequest() != 0) state.acknowledge(state.inFlightRequest(), 5_000_000);
        assertEquals(List.of("seek", "seek", "seek", "play", "seek", "key"), sent.stream().map(packet -> ((EffectProtocol.Edit) packet).action()).toList());
        assertEquals(20, ((EffectProtocol.Edit) sent.get(4)).frame());
    }
    @Test void aSceneBarrierCannotOvertakeTheHeldScrubAndLaterScrubsWaitBehindThatBarrier() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.scrub(1, 0); long first = state.inFlightRequest(); state.scrub(2, 1_000_000);
        state.beginSceneEdit(2_000_000); assertTrue(state.beforeScenePending());
        state.scrub(3, 3_000_000); state.acknowledge(first, 4_000_000);
        assertEquals(2, sent.size()); assertEquals(2, ((EffectProtocol.Edit) sent.getLast()).frame()); assertFalse(state.beforeScenePending());
        state.acknowledge(state.inFlightRequest(), 5_000_000); state.tick(100_000_000);
        assertEquals(2, sent.size()); assertTrue(state.sceneBusy()); assertTrue(state.controlPending());
        state.endSceneEdit(100_000_000); assertEquals(3, sent.size()); assertEquals(3, ((EffectProtocol.Edit) sent.getLast()).frame());
    }
    @Test void closingOrChangingProjectsDropsTheUnsentScrubAndItsClockGate() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.scrub(1, 0); state.acknowledge(state.inFlightRequest(), 1_000_000); state.scrub(2, 2_000_000);
        state.clear(); state.tick(100_000_000); assertEquals(1, sent.size()); assertFalse(state.controlPending());
        state.update(snapshot(1)); state.scrub(3, 3_000_000); assertEquals(2, sent.size());
        state.scrub(4, 4_000_000); state.beginSceneEdit(5_000_000);
        state.update(new EffectProtocol.Snapshot(2, "other", 20, 100, 0, 100, false, false, false, false, 0, List.of(), List.of(), List.of(), List.of(), List.of()));
        assertFalse(state.busy()); assertFalse(state.beforeScenePending()); state.scrub(5, 6_000_000); assertEquals(3, sent.size());
    }
    @Test void transportUpdatesOnlyClockAndFlagsWithoutReleasingQueuedWork() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add);
        var tracks = List.of(new EffectProtocol.Track("title", "offset.y", true, List.of(new EffectProtocol.Key(0, "3", "linear", .3, 0, .7, 1))));
        var original = new EffectProtocol.Snapshot(4, "intro", 24, 300, 10, 200, true, false, false, false, 10,
                tracks, List.of(new EffectProtocol.Layer("title", 5, 250, true, false)),
                List.of(new EffectProtocol.Cue("music", 0, 100, "sound", "music.ogg", .5, 1, true)),
                List.of(new EffectProtocol.Marker(20, "Reveal")), List.of("music.ogg"));
        state.update(original); state.beginSceneEdit(); state.action("save");
        assertTrue(state.update(new EffectProtocol.Transport(4, "intro", true, true, true, 40)));
        var updated = state.snapshot();
        assertEquals(original.tracks(), updated.tracks()); assertEquals(original.layers(), updated.layers());
        assertEquals(original.cues(), updated.cues()); assertEquals(original.markers(), updated.markers()); assertEquals(original.assets(), updated.assets());
        assertEquals(24, updated.fps()); assertEquals(300, updated.duration()); assertEquals(10, updated.workStart()); assertEquals(200, updated.workEnd()); assertTrue(updated.loop());
        assertTrue(updated.dirty()); assertTrue(updated.playing()); assertTrue(updated.autoKey()); assertEquals(40, updated.playhead());
        assertEquals(64, state.playhead(System.nanoTime() + 1_000_000_000L), .1);
        assertTrue(state.sceneBusy()); assertTrue(state.busy()); assertTrue(sent.isEmpty());
        state.endSceneEdit(); assertEquals(4, ((EffectProtocol.Edit) sent.getFirst()).revision());
    }
    @Test void staleForeignAndUnseenTransportRevisionsCannotReplaceTheCurrentTimeline() {
        var state = new EffectState(); assertFalse(state.update(new EffectProtocol.Transport(3, "intro", true, true, true, 20)));
        var original = snapshot(3); state.update(original);
        for (var update : List.of(new EffectProtocol.Transport(2, "intro", true, true, true, 20),
                new EffectProtocol.Transport(4, "intro", true, true, true, 20), new EffectProtocol.Transport(3, "other", true, true, true, 20))) {
            assertFalse(state.update(update)); assertSame(original, state.snapshot());
        }
        assertTrue(state.update(new EffectProtocol.Transport(3, "intro", false, false, false, 75)));
        assertEquals(75, state.playhead(System.nanoTime() + 1_000_000_000L));
    }
    @Test void scrubCoalescingPreservesPlaybackAndMutationBarriers() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.seek(1); state.seek(2); state.action("play"); state.seek(3);
        state.key("title", "offset.y", new EffectProtocol.Key(3, "7", "linear", .3, 0, .7, 1), 3);
        state.seek(4); state.seek(5);
        while (state.inFlightRequest() != 0) state.acknowledge(state.inFlightRequest());
        assertEquals(List.of("seek", "seek", "play", "seek", "key", "seek"), sent.stream().map(packet -> ((EffectProtocol.Edit) packet).action()).toList());
        assertEquals(List.of(1, 2, 3, 5), sent.stream().map(packet -> (EffectProtocol.Edit) packet).filter(edit -> edit.action().equals("seek")).map(EffectProtocol.Edit::frame).toList());
    }
    @Test void switchingProjectsDropsPendingInspectorAndTimelineWork() {
        var state=new EffectState();var sent=new ArrayList<EffectProtocol.Packet>();state.transport(sent::add);state.update(snapshot(1));
        state.beginSceneEdit();state.action("save");
        state.update(new EffectProtocol.Snapshot(2,"other",20,100,0,100,false,false,false,false,0,List.of(),List.of(),List.of(),List.of(),List.of()));
        assertFalse(state.busy());state.action("save");assertEquals(1,sent.size());assertEquals(2,((EffectProtocol.Edit)sent.getFirst()).revision());
    }
    @Test void timelineWaitsForInspectorAcknowledgementAndUsesItsUpdatedRevision(){
        var state=new EffectState();var sent=new ArrayList<EffectProtocol.Packet>();state.transport(sent::add);state.update(snapshot(1));
        state.beginSceneEdit();state.action("save");assertTrue(state.busy());assertTrue(sent.isEmpty());
        state.update(snapshot(2));state.endSceneEdit();assertEquals(2,((EffectProtocol.Edit)sent.getFirst()).revision());assertFalse(state.sceneBusy());
    }
    @Test void rejectedAtomicEditDoesNotBlockLaterOperationsOrReplayItsQueue() {
        var state=new EffectState();var sent=new ArrayList<EffectProtocol.Packet>();state.transport(sent::add);state.update(snapshot(3));
        state.action("move-keys");state.action("paste-layers");state.reject();assertFalse(state.busy());
        state.action("undo");assertEquals(2,sent.size());assertEquals("undo",((EffectProtocol.Edit)sent.getLast()).action());assertEquals(3,((EffectProtocol.Edit)sent.getLast()).revision());
    }
    private static EffectProtocol.Snapshot snapshot(long revision) { return new EffectProtocol.Snapshot(revision, "intro", 20, 100, 0, 100, false, false, false, false, 0, List.of(), List.of(), List.of(), List.of(), List.of()); }
    @Test void queuedEditsUseAcknowledgedRevisionsAndIgnoreUnrelatedSnapshots() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.key("title", "offset.x", new EffectProtocol.Key(0, "1", "linear", 0, 0, 1, 1), 0);
        state.key("title", "offset.x", new EffectProtocol.Key(10, "2", "linear", 0, 0, 1, 1), 10);
        assertEquals(1, sent.size());
        state.update(snapshot(0)); assertEquals(1, sent.size());
        state.update(snapshot(1)); state.acknowledge(999); assertEquals(1, sent.size());
        state.acknowledge(((EffectProtocol.Edit) sent.getFirst()).requestId());
        assertEquals(2, sent.size()); assertEquals(1, ((EffectProtocol.Edit) sent.getLast()).revision());
        state.update(snapshot(2)); state.acknowledge(((EffectProtocol.Edit) sent.getLast()).requestId()); assertFalse(state.busy());
    }
    @Test void rapidScrubbingCoalescesPendingSeeksAndClosingDropsQueuedWork() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); state.transport(sent::add); state.update(snapshot(0));
        state.seek(1); state.seek(2); state.seek(3); state.seek(4);
        state.acknowledge(((EffectProtocol.Edit) sent.getFirst()).requestId());
        assertEquals(2, sent.size()); assertEquals(4, ((EffectProtocol.Edit) sent.getLast()).frame());
        state.update(new EffectProtocol.Viewport(100, 3, 0, 0));
        state.action("save"); state.clear(); state.acknowledge(((EffectProtocol.Edit) sent.getLast()).requestId());
        assertEquals(2, sent.size()); assertFalse(state.active()); assertFalse(state.busy()); assertNull(state.viewport());
    }
    @Test void audioControlsWaitForTheFinalQueuedSeekAndNotifyImmediately() {
        var state = new EffectState(); var sent = new ArrayList<EffectProtocol.Packet>(); var observed = new ArrayList<String>();
        state.transport(sent::add); state.localControl((action, frame) -> observed.add(action + ":" + frame)); state.update(snapshot(0));
        state.seek(30); state.seek(60); assertTrue(state.controlPending()); assertEquals(List.of("seek:30", "seek:60"), observed);
        state.acknowledge(((EffectProtocol.Edit) sent.getFirst()).requestId()); assertTrue(state.controlPending());
        state.acknowledge(((EffectProtocol.Edit) sent.getLast()).requestId()); assertFalse(state.controlPending());
        state.action("save"); assertFalse(state.controlPending()); state.reject();
        state.action("play"); assertTrue(state.controlPending()); state.clear(); assertFalse(state.controlPending());
    }
    @Test void interpolatedLoopWrapsInsideTheWorkAreaWhileOrdinaryPlaybackStopsAtItsEnd() {
        var state = new EffectState();
        state.update(new EffectProtocol.Snapshot(0, "loop", 20, 100, 10, 30, true, false, true, false, 20, List.of(), List.of(), List.of(), List.of(), List.of()));
        double looped = state.playhead(System.nanoTime() + 1_000_000_000L); assertEquals(20, looped, .1);
        state.update(new EffectProtocol.Snapshot(0, "loop", 20, 100, 10, 30, false, false, true, false, 20, List.of(), List.of(), List.of(), List.of(), List.of()));
        assertEquals(30, state.playhead(System.nanoTime() + 1_000_000_000L));
    }
}

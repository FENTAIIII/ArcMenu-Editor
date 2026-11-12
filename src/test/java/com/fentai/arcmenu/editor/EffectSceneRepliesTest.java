package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.ArcMenuEditorClient.SceneReplies;
import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.CreatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.MovePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.NodeSnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.SetPropertyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SnapshotPacket;
import com.fentai.arcmenu.protocol.EffectProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol.NodeFrame;
import com.fentai.arcmenu.protocol.EffectProtocol.SceneFrame;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectSceneRepliesTest
{
    @Test
    void compactSamplesKeepStructuralAndTimelineEditsBlockedUntilTheFullSceneSnapshot()
    {
        var state = new EffectState();
        var sent = new ArrayList<EffectProtocol.Packet>();
        state.transport(sent::add);
        state.update(snapshot(0));
        var scene = new EditorState();
        scene.update(new SnapshotPacket(2, "intro", 320, 180, false, true, "26.3", List.of(
                new NodeSnapshot("title", "", EditorProtocol.KIND_TEXT, 0, 0, 10, 10, 0, true, false)),
                List.of()));
        var replies = new SceneReplies();
        replies.enqueue(create(2));
        state.beginSceneEdit();
        replies.dispatch(2);
        assertEquals(0, replies.acknowledge(EditorProtocol.OP_CREATE));
        state.action("save");
        assertTrue(state.update(new EffectProtocol.Transport(0, "intro", true, false, false, 20)));
        assertTrue(scene.update(new SceneFrame(2, "intro",
                List.of(new NodeFrame("title", 10, 20, 30, 40, 0, true, List.of())))));
        assertTrue(state.sceneBusy());
        assertTrue(sent.isEmpty());
        assertNull(replies.dispatch(3));
        assertEquals(1, replies.snapshot());
        state.endSceneEdit();
        assertEquals(1, sent.size());
    }

    private static CreatePacket create(long revision)
    {
        return new CreatePacket(revision, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_TEXT, "group",
                "Text\nwith a second line");
    }

    private static EffectProtocol.Snapshot snapshot(long revision)
    {
        return new EffectProtocol.Snapshot(revision, "intro", 20, 100, 0, 100, false, false, false, false, 0, List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void structuralAcknowledgementKeepsLaterEditsBlockedUntilItsAuthoritativeSnapshot()
    {
        var replies = new SceneReplies();
        replies.enqueue(create(1));
        replies.enqueue(create(1));
        assertEquals(4, ((CreatePacket) replies.dispatch(4)).revision());
        assertEquals(0, replies.snapshot());
        assertEquals(0, replies.acknowledge(EditorProtocol.OP_CREATE));
        assertNull(replies.dispatch(5));
        assertEquals(1, replies.snapshot());
        assertEquals(5, ((CreatePacket) replies.dispatch(5)).revision());
    }

    @Test
    void liveGeometryAcknowledgementsReleaseWithoutWaitingForANonexistentSnapshot()
    {
        var replies = new SceneReplies();
        var point = new EditorProtocol.Pointer(20, 30, 0, 0, 400, 300, 7, -9);
        replies.enqueue(new MovePacket(1, EditorProtocol.TAB_FRONTEND, "title", point, 4, 5, 99, false));
        var packet = (MovePacket) replies.dispatch(7);
        assertEquals(7, packet.revision());
        assertEquals(point, packet.pointer());
        assertEquals(99, packet.gestureId());
        assertFalse(packet.finalUpdate());
        assertEquals(0, replies.acknowledge(EditorProtocol.OP_CREATE));
        assertEquals(1, replies.acknowledge(EditorProtocol.OP_MOVE));
        assertNull(replies.dispatch(8));
        assertEquals(0, replies.snapshot());
    }

    @Test
    void propertyFinalizationHasTheSameSnapshotBarrierAsCreation()
    {
        var replies = new SceneReplies();
        replies.enqueue(new SetPropertyPacket(1, EditorProtocol.TAB_FRONTEND, "title", "offset.y", "42",
                10, false));
        replies.dispatch(2);
        assertEquals(1, replies.acknowledge(EditorProtocol.OP_PROPERTY));
        replies.enqueue(new SetPropertyPacket(2, EditorProtocol.TAB_FRONTEND, "title", "offset.y", "43",
                10, true));
        replies.dispatch(3);
        assertEquals(0, replies.acknowledge(EditorProtocol.OP_PROPERTY));
        assertEquals(1, replies.snapshot());
    }

    @Test
    void rejectedEditsDiscardQueuedWorkAndWaitForTheRecoverySnapshot()
    {
        var replies = new SceneReplies();
        replies.enqueue(create(1));
        replies.enqueue(create(1));
        replies.enqueue(create(1));
        replies.dispatch(3);
        assertEquals(2, replies.reject(true));
        assertNull(replies.dispatch(4));
        assertEquals(1, replies.snapshot());
        assertNull(replies.dispatch(4));
        replies.enqueue(create(4));
        replies.dispatch(4);
        assertEquals(1, replies.reject(false));
        assertEquals(0, replies.snapshot());
    }

    @Test
    void rebasePreservesSourceParentAndSelectionIntent()
    {
        var replies = new SceneReplies();
        replies.enqueue(create(1));
        var packet = (CreatePacket) replies.dispatch(50);
        assertEquals(50, packet.revision());
        assertEquals("group", packet.parentId());
        assertEquals("Text\nwith a second line", packet.initialSource());
        assertEquals(create(1).kind(), packet.kind());
        assertEquals(create(1).tab(), packet.tab());
    }

    @Test
    void timelineAndSceneChannelsSerializeInBothDirectionsWithoutLosingQueuedKeys()
    {
        var state = new EffectState();
        var sent = new ArrayList<EffectProtocol.Packet>();
        state.transport(sent::add);
        state.update(snapshot(0));
        var replies = new SceneReplies();
        state.seek(20);
        long first = state.inFlightRequest();
        replies.enqueue(create(0));
        state.beginSceneEdit();
        state.key("title", "offset.y", new EffectProtocol.Key(20, "7", "linear", .333, 0, .667, 1), 20);
        state.update(snapshot(1));
        state.acknowledge(first);
        assertEquals(1, sent.size());
        assertTrue(state.sceneBusy());
        replies.dispatch(1);
        assertEquals(0, replies.acknowledge(EditorProtocol.OP_CREATE));
        assertEquals(1, sent.size());
        state.update(snapshot(2));
        assertEquals(1, replies.snapshot());
        state.endSceneEdit();
        assertEquals(2, sent.size());
        assertEquals(2, ((EffectProtocol.Edit) sent.getLast()).revision());
        assertEquals("key", ((EffectProtocol.Edit) sent.getLast()).action());
    }
}

package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackendGroupStateTest {
    @Test void liveBackendGroupResizeUpdatesDescendantOutlinesBeforeTheFinalSnapshot() {
        var state = new EditorState();
        state.update(snapshot(0, List.of(node("controls", "", EditorProtocol.KIND_GROUP, 0),
                node("nested", "controls", EditorProtocol.KIND_GROUP, 5),
                node("first", "nested", EditorProtocol.KIND_REGION, 20),
                node("other", "", EditorProtocol.KIND_REGION, 50))));
        state.activeTab(EditorProtocol.TAB_BACKEND);
        state.apply(new EditorProtocol.AckPacket(EditorProtocol.OP_RESIZE, 1, "controls", 10, 20, 20, 30,
                Double.NaN, Double.NaN, "", true, false, "Resized"));
        assertEquals(50, state.find("first").x());
        assertEquals(20, state.find("first").y());
        assertEquals(20, state.find("first").width());
        assertEquals(30, state.find("first").height());
        assertEquals(20, state.find("nested").x());
        assertEquals("nested", state.find("first").parentId());
        assertEquals(50, state.find("other").x());
        assertEquals(10, state.find("other").width());
        state.apply(new EditorProtocol.AckPacket(EditorProtocol.OP_MOVE, 2, "controls", 12, 25, 20, 30,
                Double.NaN, Double.NaN, "", true, false, "Moved"));
        assertEquals(52, state.find("first").x());
        assertEquals(25, state.find("first").y());
        assertEquals(20, state.find("first").width());
    }

    @Test void groupingSelectsTheBackendGroupAndPreservesItsChildTreeAcrossSnapshots() {
        var state = new EditorState();
        var first = node("first", "", EditorProtocol.KIND_REGION, 0);
        var second = node("second", "", EditorProtocol.KIND_REGION, 10);
        state.update(snapshot(0, List.of(first, second)));
        state.activeTab(EditorProtocol.TAB_BACKEND);
        state.selectAll(List.of("first", "second"));
        state.expectReply(new EditorProtocol.GroupPacket(0, List.of("first", "second")));
        var grouped = List.of(node("controls", "", EditorProtocol.KIND_GROUP, 0),
                node("first", "controls", EditorProtocol.KIND_REGION, 0),
                node("second", "controls", EditorProtocol.KIND_REGION, 10));
        state.update(snapshot(1, grouped));
        state.apply(new EditorProtocol.AckPacket(EditorProtocol.OP_GROUP, 1, "controls", 0, 0, 10, 10,
                Double.NaN, Double.NaN, "", true, false, "Grouped"));
        assertEquals("controls", state.selectedId());
        assertEquals(EditorProtocol.TAB_BACKEND, state.activeTab());
        assertTrue(state.hasChildren("controls"));
        assertEquals("controls", state.find("first").parentId());
        state.expanded().add("controls");
        state.update(snapshot(2, grouped));
        assertTrue(state.expanded().contains("controls"));
        assertEquals("controls", state.selectedId());
        state.update(snapshot(3, List.of(first, second)));
        assertFalse(state.hasChildren("controls"));
        assertTrue(state.selectedIds().isEmpty());
    }

    private static EditorProtocol.NodeSnapshot node(String id, String parent, byte kind, double x) {
        return new EditorProtocol.NodeSnapshot(id, parent, kind, x, 0, 10, 10, 0, true, false,
                List.of(new EditorProtocol.PropertySnapshot("x", EditorProtocol.PROPERTY_NUMBER, Double.toString(x))));
    }

    private static EditorProtocol.SnapshotPacket snapshot(long revision, List<EditorProtocol.NodeSnapshot> backend) {
        return new EditorProtocol.SnapshotPacket(revision, "menu", 320, 180, true, false, "26.3",
                List.of(node("label", "", EditorProtocol.KIND_TEXT, 0)), backend);
    }
}

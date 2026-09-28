package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorStateTest {
    @Test
    void ctrlAndShiftSelectionFollowVisibleOrder() {
        EditorState state = state();
        List<String> order = List.of("a", "b", "c", "d");

        state.selectOnly("b");
        state.toggleSelection("d");
        assertEquals(List.of("b", "d"), state.selectedIds().stream().toList());

        state.selectRange(order, "b");
        assertEquals(List.of("b", "c", "d"), state.selectedIds().stream().toList());
        assertEquals("b", state.selectedId());
    }

    @Test
    void authoritativeSnapshotDropsOnlyMissingSelections() {
        EditorState state = state();
        state.selectOnly("a");
        state.toggleSelection("c");
        state.update(snapshot(List.of(node("a"), node("b"))));

        assertTrue(state.selected("a"));
        assertFalse(state.selected("c"));
        assertEquals("a", state.selectedId());
    }

    @Test void propertyResponseDoesNotJumpBackToTheFirstElementOrCollapseSelection() {
        EditorState state = state();
        state.selectOnly("a");
        state.expectReply(property("a", "x", "42"));
        state.selectOnly("c");
        state.toggleSelection("d");
        state.apply(ack(EditorProtocol.OP_PROPERTY, "a"));
        assertEquals("d", state.selectedId());
        assertEquals(List.of("c", "d"), state.selectedIds().stream().toList());
        assertEquals(42, state.find("a").x());
    }

    @Test void geometryAcknowledgementsUpdateTheNodeWithoutChangingCurrentSelection() {
        for (byte operation : List.of(EditorProtocol.OP_MOVE, EditorProtocol.OP_RESIZE, EditorProtocol.OP_PROPERTY)) {
            EditorState state = state();
            state.selectOnly("b");
            state.toggleSelection("c");
            state.apply(ack(operation, "a"));
            assertEquals("c", state.selectedId());
            assertEquals(List.of("b", "c"), state.selectedIds().stream().toList());
        }
    }

    @Test void createdElementIsSelectedWhenTheUserHasNotChangedSelection() {
        EditorState state = state();
        state.selectOnly("c");
        state.expectReply(new EditorProtocol.CreatePacket(0, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_IMAGE, "", "/image.png"));
        state.apply(ack(EditorProtocol.OP_CREATE, "image"));
        state.update(snapshot(List.of(node("a"), node("c"), node("image"))));
        assertEquals("image", state.selectedId());
    }

    @Test void delayedCreationDoesNotOverrideANewerUserSelection() {
        EditorState state = state();
        state.expectReply(new EditorProtocol.CreatePacket(0, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_IMAGE, "", "/image.png"));
        state.selectOnly("d");
        state.apply(ack(EditorProtocol.OP_CREATE, "image"));
        assertEquals("d", state.selectedId());
    }

    @Test void renamingKeepsTheRenamedElementAndOtherSelectedElementsSelected() {
        EditorState state = state();
        state.selectOnly("b");
        state.toggleSelection("c");
        state.expectReply(property("c", "id", "renamed"));
        state.apply(ack(EditorProtocol.OP_PROPERTY, "renamed"));
        state.update(snapshot(List.of(node("a"), node("b"), node("renamed"))));
        assertEquals("renamed", state.selectedId());
        assertTrue(state.selected("b"));
        assertTrue(state.selected("renamed"));
    }

    @Test void normalPropertyAckCannotConsumeTheIntentOfALaterRename() {
        EditorState state = state();
        state.selectOnly("c");
        state.expectReply(property("c", "x", "42"));
        state.expectReply(property("c", "id", "renamed"));
        state.apply(ack(EditorProtocol.OP_PROPERTY, "c"));
        assertEquals("c", state.selectedId());
        state.apply(ack(EditorProtocol.OP_PROPERTY, "renamed"));
        assertEquals("renamed", state.selectedId());
    }

    @Test void lateRenameDoesNotStealSelectionFromAnotherElement() {
        EditorState state = state();
        state.selectOnly("a");
        state.expectReply(property("a", "id", "renamed"));
        state.selectOnly("c");
        state.apply(ack(EditorProtocol.OP_PROPERTY, "renamed"));
        state.update(snapshot(List.of(node("renamed"), node("b"), node("c"))));
        assertEquals("c", state.selectedId());
    }

    @Test void aReplyFromThePreviousTabCannotSelectOrChangeABackendElementWithTheSameId() {
        EditorState state = new EditorState();
        state.update(new EditorProtocol.SnapshotPacket(0, "menu", 320, 180, false, true, "26.1.2",
                List.of(node("a")), List.of(node("a"))));
        state.selectOnly("a");
        state.expectReply(property("a", "x", "42"));
        state.activeTab(EditorProtocol.TAB_BACKEND);
        state.apply(ack(EditorProtocol.OP_PROPERTY, "a"));
        assertEquals("", state.selectedId());
        assertEquals(0, state.find("a").x());
        assertEquals(42, state.snapshot().frontend().getFirst().x());
    }

    private static EditorProtocol.SetPropertyPacket property(String id, String key, String value) {
        return new EditorProtocol.SetPropertyPacket(0, EditorProtocol.TAB_FRONTEND, id, key, value);
    }

    @Test void latePropertyRepliesCompleteOnlyTheGestureThatSentThem() {
        EditorState state = state();
        state.expectReply(property("a", "x", "42"));
        state.expectReply(new EditorProtocol.SetPropertyPacket(1, EditorProtocol.TAB_FRONTEND, "c", "x", "48", 17, false));
        assertEquals(0, state.apply(ack(EditorProtocol.OP_PROPERTY, "a")));
        assertEquals(17, state.apply(ack(EditorProtocol.OP_PROPERTY, "c")));
    }

    private static EditorProtocol.AckPacket ack(byte operation, String id) {
        return new EditorProtocol.AckPacket(operation, 1, id, 42, 12, 20, 30,
                Double.NaN, Double.NaN, "", true, false, "updated");
    }

    private static EditorState state() {
        EditorState state = new EditorState();
        state.update(snapshot(List.of(node("a"), node("b"), node("c"), node("d"))));
        return state;
    }

    private static EditorProtocol.SnapshotPacket snapshot(List<EditorProtocol.NodeSnapshot> nodes) {
        return new EditorProtocol.SnapshotPacket(0, "menu", 320, 180, false, true, "26.1.2", nodes, List.of());
    }

    private static EditorProtocol.NodeSnapshot node(String id) {
        return new EditorProtocol.NodeSnapshot(id, "", EditorProtocol.KIND_RECTANGLE,
                0, 0, 10, 10, 0, true, false);
    }
}

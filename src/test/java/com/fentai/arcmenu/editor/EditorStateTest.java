package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class EditorStateTest {
    @Test void sampledFramesKeepPropertySchemasCataloguesSelectionAndErrorStatus() {
        var state = new EditorState();
        var schema = List.of(new EditorProtocol.PropertySnapshot("offset.y", EditorProtocol.PROPERTY_NUMBER, "1", List.of()),
                new EditorProtocol.PropertySnapshot("material", EditorProtocol.PROPERTY_CHOICE, "STONE", List.of("STONE", "GOLD_BLOCK")));
        var original = new EditorProtocol.NodeSnapshot("a", "group", EditorProtocol.KIND_BLOCK, 0, 0, 10, 10, 0, true, true, schema);
        var images = List.of(new EditorProtocol.ImageSnapshot("image.png", 64, 64));
        var templates = List.of(new EditorProtocol.TemplateSnapshot("card", "root", 3));
        var backend = List.of(node("a"));
        state.update(new EditorProtocol.SnapshotPacket(5, "menu", 320, 180, true, false, "26.3", List.of(original, node("b")), backend, images, templates));
        state.selectOnly("a"); state.toggleSelection("b"); state.toggleLocked("b"); state.error("Conflict remains visible");
        assertTrue(state.update(new EffectProtocol.SceneFrame(5, "menu", List.of(new EffectProtocol.NodeFrame("a", 20, 30, 40, 50, 35, false,
                List.of(new EffectProtocol.PropertyValue("offset.y", "30"), new EffectProtocol.PropertyValue("unknown", "ignored")))))));
        var sampled = state.find("a");
        assertEquals(20, sampled.x()); assertEquals(30, sampled.y()); assertEquals(40, sampled.width()); assertEquals(50, sampled.height()); assertEquals(35, sampled.rotationZ()); assertFalse(sampled.visible());
        assertEquals("group", sampled.parentId()); assertEquals(EditorProtocol.KIND_BLOCK, sampled.kind()); assertTrue(sampled.locked());
        assertEquals(2, sampled.properties().size()); assertEquals("30", sampled.properties().getFirst().value());
        assertEquals(schema.getFirst().type(), sampled.properties().getFirst().type()); assertEquals(schema.get(1), sampled.properties().get(1));
        assertEquals(List.of("a", "b"), state.selectedIds().stream().toList()); assertEquals("b", state.selectedId()); assertTrue(state.locked("b"));
        assertEquals(backend, state.snapshot().backend()); assertEquals(images, state.snapshot().images()); assertEquals(templates, state.snapshot().templates());
        assertEquals(5, state.snapshot().revision()); assertTrue(state.snapshot().dirty()); assertFalse(state.snapshot().saved()); assertEquals("26.3", state.snapshot().serverVersion());
        assertTrue(state.errorStatus()); assertEquals("Conflict remains visible", state.status());
    }

    @Test void sampledFramesRejectUnseenRevisionsForeignMenusAndUnknownNodesAtomically() {
        var state = state(); var original = state.snapshot();
        var frame = new EffectProtocol.NodeFrame("a", 10, 20, 30, 40, 50, false, List.of());
        for (var update : List.of(new EffectProtocol.SceneFrame(1, "menu", List.of(frame)),
                new EffectProtocol.SceneFrame(-1, "menu", List.of(frame)), new EffectProtocol.SceneFrame(0, "other", List.of(frame)),
                new EffectProtocol.SceneFrame(0, "menu", List.of(frame, new EffectProtocol.NodeFrame("unknown", 1, 2, 3, 4, 5, true, List.of()))))) {
            assertFalse(state.update(update)); assertSame(original, state.snapshot());
        }
        assertFalse(new EditorState().update(new EffectProtocol.SceneFrame(0, "menu", List.of(frame))));
    }

    @Test void sampledFramesRetainUnchangedValuesAndCanRestoreAnEarlierValue() {
        var state = new EditorState();
        state.update(snapshot(List.of(new EditorProtocol.NodeSnapshot("a", "", EditorProtocol.KIND_TEXT, 0, 0, 10, 10, 0, true, false,
                List.of(new EditorProtocol.PropertySnapshot("content", EditorProtocol.PROPERTY_TEXT, "Original", List.of()))))));
        state.update(new EffectProtocol.SceneFrame(0, "menu", List.of(new EffectProtocol.NodeFrame("a", 10, 0, 10, 10, 0, true, List.of(new EffectProtocol.PropertyValue("content", "Animated"))))));
        state.update(new EffectProtocol.SceneFrame(0, "menu", List.of(new EffectProtocol.NodeFrame("a", 20, 0, 10, 10, 0, true, List.of()))));
        assertEquals("Animated", state.find("a").properties().getFirst().value());
        state.update(new EffectProtocol.SceneFrame(0, "menu", List.of(new EffectProtocol.NodeFrame("a", 0, 0, 10, 10, 0, true, List.of(new EffectProtocol.PropertyValue("content", "Original"))))));
        assertEquals("Original", state.find("a").properties().getFirst().value()); assertEquals(0, state.find("a").x());
    }

    @Test void sampledFramesDoNotConsumePendingCreationSelectionIntent() {
        var state = state(); state.selectOnly("a");
        state.expectReply(new EditorProtocol.CreatePacket(0, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_TEXT, "", "New text"));
        state.update(new EffectProtocol.SceneFrame(0, "menu", List.of(new EffectProtocol.NodeFrame("a", 10, 20, 30, 40, 50, false, List.of()))));
        assertEquals("a", state.selectedId());
        state.apply(ack(EditorProtocol.OP_CREATE, "created"));
        state.update(snapshot(List.of(node("a"), node("created"))));
        assertEquals("created", state.selectedId());
    }
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

    @Test void rejectionReasonSurvivesTheAuthoritativeSnapshotUntilTheNextSuccessfulEdit() {
        EditorState state = state();
        state.selectOnly("c");
        state.expectReply(property("c", "id", "a"));
        state.error("Element key already exists: a");
        state.update(snapshot(List.of(node("a"), node("b"), node("c"))));
        assertTrue(state.errorStatus());
        assertEquals("Element key already exists: a", state.status());
        assertEquals("c", state.selectedId());
        state.expectReply(property("c", "id", "Shop.Item"));
        state.apply(ack(EditorProtocol.OP_PROPERTY, "Shop.Item"));
        state.update(snapshot(List.of(node("a"), node("b"), node("Shop.Item"))));
        assertFalse(state.errorStatus());
        assertEquals("Shop.Item", state.selectedId());
    }

    @Test void aNewMenuClearsThePreviousMenusRejectionReason() {
        EditorState state = state();
        state.error("Element key already exists: a");
        state.update(new EditorProtocol.SnapshotPacket(0, "other-menu", 320, 180,
                false, true, "26.3", List.of(node("a")), List.of()));
        assertFalse(state.errorStatus());
    }

    @Test void aDifferentMenuAlwaysStartsOnTheFrontend() {
        EditorState state = state();
        state.activeTab(EditorProtocol.TAB_BACKEND);
        state.update(new EditorProtocol.SnapshotPacket(0, "other-menu", 320, 180,
                false, true, "26.3", List.of(node("front")), List.of(node("back"))));
        assertEquals(EditorProtocol.TAB_FRONTEND, state.activeTab());
        assertEquals(List.of("front"), state.nodes().stream().map(EditorProtocol.NodeSnapshot::id).toList());
        assertTrue(state.selectedIds().isEmpty());
    }

    @Test void ordinarySnapshotsKeepTheSelectedBackendView() {
        EditorState state = new EditorState();
        var snapshot = new EditorProtocol.SnapshotPacket(0, "menu", 320, 180,
                false, true, "26.3", List.of(node("front")), List.of(node("back")));
        state.update(snapshot);
        state.activeTab(EditorProtocol.TAB_BACKEND);
        state.selectOnly("back");
        state.update(snapshot);
        assertEquals(EditorProtocol.TAB_BACKEND, state.activeTab());
        assertEquals("back", state.selectedId());
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

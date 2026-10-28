package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EffectHierarchyTest {
    private static EditorProtocol.NodeSnapshot node(String id, String parent, boolean group) {
        return new EditorProtocol.NodeSnapshot(id, parent, group ? EditorProtocol.KIND_GROUP : EditorProtocol.KIND_TEXT,
                0, 0, 10, 10, 0, true, false, List.of());
    }
    private static EditorState scene() {
        var scene = new EditorState();
        scene.update(new EditorProtocol.SnapshotPacket(5, "effect", 320, 180, true, false, "26.3", List.of(
                node("outer", "", true), node("a", "outer", false), node("b", "outer", false),
                node("nested", "outer", true), node("child", "nested", false), node("c", "outer", false),
                node("other", "", true), node("root", "", false)), List.of()));
        return scene;
    }

    @Test void rightClickPreservesNestedMultiSelectionEvenWithModifiers() {
        var scene = scene(); scene.selectAll(List.of("a", "b"));
        EffectHierarchy.select(scene, List.of("a", "b", "nested"), "a", true, true, true);
        assertEquals(Set.of("a", "b"), scene.selectedIds());
        assertTrue(EffectHierarchy.canGroup(scene));
        assertEquals("outer", EffectHierarchy.creationParent(scene));
        EffectHierarchy.select(scene, List.of("a", "b", "nested"), "nested", true, false, false);
        assertEquals(Set.of("nested"), scene.selectedIds());
    }

    @Test void rangeSelectionExcludesCollapsedChildrenAndKeepsItsAnchor() {
        var scene = scene(); var visible = List.of("a", "b", "nested", "c");
        scene.selectOnly("a");
        EffectHierarchy.select(scene, visible, "c", false, true, false);
        assertEquals(Set.of("a", "b", "nested", "c"), scene.selectedIds());
        EffectHierarchy.select(scene, visible, "b", false, true, false);
        assertEquals(Set.of("a", "b"), scene.selectedIds());
    }

    @Test void pressingAnAlreadySelectedLayerKeepsTheWholeDragSelection() {
        var scene = scene(); scene.selectAll(List.of("a", "b"));
        EffectHierarchy.select(scene, List.of("a", "b"), "a", false, false, false);
        assertEquals(List.of("a", "b"), EffectHierarchy.roots(scene, scene.selectedIds()));
    }

    @Test void creatingFromChildrenUsesTheirParentAndCreatingFromGroupsUsesTheGroup() {
        var scene = scene(); scene.selectOnly("child");
        assertEquals("nested", EffectHierarchy.creationParent(scene));
        scene.selectOnly("nested"); assertEquals("nested", EffectHierarchy.creationParent(scene));
        scene.selectAll(List.of("a", "b")); assertEquals("outer", EffectHierarchy.creationParent(scene));
        scene.clearSelection(); assertEquals("", EffectHierarchy.creationParent(scene));
    }

    @Test void groupingAndMovingUseEachSelectedSubtreeOnlyOnce() {
        var scene = scene(); scene.selectAll(List.of("nested", "child", "b"));
        assertEquals(List.of("b", "nested"), EffectHierarchy.roots(scene, scene.selectedIds()));
        assertTrue(EffectHierarchy.canGroup(scene));
        scene.selectAll(List.of("a", "child")); assertFalse(EffectHierarchy.canGroup(scene));
        scene.selectAll(List.of("a", "b")); scene.toggleLocked("b"); assertFalse(EffectHierarchy.canGroup(scene));
    }

    @Test void hierarchyMovesRejectCyclesLockedDestinationsAndInvalidInsertionSiblings() {
        var scene = scene();
        assertFalse(EffectHierarchy.canMove(scene, List.of("outer"), "outer", ""));
        assertFalse(EffectHierarchy.canMove(scene, List.of("outer"), "nested", ""));
        assertFalse(EffectHierarchy.canMove(scene, List.of("a"), "b", ""));
        assertFalse(EffectHierarchy.canMove(scene, List.of("a"), "other", "b"));
        assertTrue(EffectHierarchy.canMove(scene, List.of("a", "b"), "other", ""));
        assertTrue(EffectHierarchy.canMove(scene, List.of("a", "b"), "", "outer"));
        scene.toggleLocked("other"); assertFalse(EffectHierarchy.canMove(scene, List.of("a"), "other", ""));
    }

    @Test void groupCenterDropNestsWhileEdgesReorderBesideTheGroup() {
        var scene = scene(); var row = new EffectHierarchy.Row("nested", new EditorLayout.Rect(10, 100, 200, 20));
        var inside = EffectHierarchy.drop(scene, List.of("a", "b"), row, 110);
        assertNotNull(inside); assertTrue(inside.inside()); assertEquals("nested", inside.parent()); assertEquals("", inside.before());
        var before = EffectHierarchy.drop(scene, List.of("a", "b"), row, 101);
        assertNotNull(before); assertFalse(before.inside()); assertEquals("outer", before.parent()); assertEquals("nested", before.before());
        var after = EffectHierarchy.drop(scene, List.of("a", "b"), row, 119);
        assertNotNull(after); assertEquals("outer", after.parent()); assertEquals("c", after.before());
        assertNull(EffectHierarchy.drop(scene, List.of("outer"), row, 110));
    }

    @Test void movingSeveralLayersOneStepUsesTheEndOfTheSelectedBlock() {
        var scene = scene();
        assertEquals("c", EffectHierarchy.reorderBefore(scene, List.of("a", "b"), "raise"));
        assertEquals("a", EffectHierarchy.reorderBefore(scene, List.of("b", "nested"), "lower"));
        assertEquals("", EffectHierarchy.reorderBefore(scene, List.of("a", "b"), "to-front"));
    }
}

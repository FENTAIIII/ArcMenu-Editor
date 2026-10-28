package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared layer selection and destination rules for the Effect tree and timeline. */
final class EffectHierarchy {
    record Row(String id, EditorLayout.Rect bounds) {}
    record Drop(String parent, String before, EditorLayout.Rect bounds, boolean inside) {}

    static List<String> roots(EditorState scene, Collection<String> selection) {
        var selected = new HashSet<>(selection);
        return scene.nodes().stream().filter(node -> selected.contains(node.id()) && !hasAncestor(scene, node.parentId(), selected))
                .map(EditorProtocol.NodeSnapshot::id).toList();
    }

    private static boolean hasAncestor(EditorState scene, String parent, Set<String> ids) {
        var visited = new HashSet<String>();
        while (!parent.isEmpty() && visited.add(parent)) {
            if (ids.contains(parent)) return true;
            var node = scene.find(parent);
            parent = node == null ? "" : node.parentId();
        }
        return false;
    }

    static boolean editable(EditorState scene, List<String> ids) {
        return !ids.isEmpty() && ids.stream().allMatch(id -> {
            var node = scene.find(id);
            return node != null && !node.locked() && !scene.locked(id);
        });
    }

    static boolean sameParent(EditorState scene, List<String> ids) {
        var first = ids.isEmpty() ? null : scene.find(ids.getFirst());
        return first != null && ids.stream().allMatch(id -> scene.find(id) != null
                && scene.find(id).parentId().equals(first.parentId()));
    }

    static boolean canGroup(EditorState scene) {
        var ids = roots(scene, scene.selectedIds());
        return editable(scene, ids) && sameParent(scene, ids);
    }

    static String creationParent(EditorState scene) {
        var ids = roots(scene, scene.selectedIds());
        if (ids.size() == 1 && scene.find(ids.getFirst()).kind() == EditorProtocol.KIND_GROUP) return ids.getFirst();
        return sameParent(scene, ids) ? scene.find(ids.getFirst()).parentId() : "";
    }

    static boolean canMove(EditorState scene, List<String> ids, String parent, String before) {
        if (!editable(scene, ids)) return false;
        if (!parent.isEmpty()) {
            var target = scene.find(parent);
            if (target == null || target.kind() != EditorProtocol.KIND_GROUP || !editable(scene, List.of(parent))
                    || hasAncestor(scene, parent, Set.copyOf(ids))) return false;
        }
        var next = scene.find(before);
        return before.isEmpty() || next != null && next.parentId().equals(parent) && !ids.contains(before);
    }

    static void select(EditorState scene, List<String> visible, String id, boolean context, boolean range, boolean toggle) {
        if (context) { if (!scene.selected(id)) scene.selectOnly(id); }
        else if (range) scene.selectRange(visible, id);
        else if (toggle) scene.toggleSelection(id);
        else if (!scene.selected(id)) scene.selectOnly(id);
    }

    static Drop drop(EditorState scene, List<String> ids, Row row, double y) {
        var node = scene.find(row.id());
        if (node == null || ids.contains(node.id())) return null;
        var rect = row.bounds();
        boolean inside = node.kind() == EditorProtocol.KIND_GROUP && y >= rect.y() + rect.height() * .25 && y < rect.y() + rect.height() * .75;
        String parent = inside ? node.id() : node.parentId();
        String before = inside ? "" : node.id();
        boolean after = !inside && y >= rect.y() + rect.height() / 2.0;
        if (after) {
            var siblings = scene.nodes().stream().filter(n -> n.parentId().equals(parent) && !ids.contains(n.id())).map(EditorProtocol.NodeSnapshot::id).toList();
            int next = siblings.indexOf(node.id()) + 1;
            before = next < siblings.size() ? siblings.get(next) : "";
        }
        if (!canMove(scene, ids, parent, before)) return null;
        return new Drop(parent, before, inside ? rect : new EditorLayout.Rect(rect.x(), after ? rect.bottom() : rect.y(), rect.width(), 2), inside);
    }

    static String reorderBefore(EditorState scene, List<String> ids, String action) {
        var siblings = scene.nodes().stream().filter(n -> n.parentId().equals(scene.find(ids.getFirst()).parentId())).map(EditorProtocol.NodeSnapshot::id).toList();
        var rest = new ArrayList<>(siblings); rest.removeAll(ids);
        int first = siblings.indexOf(ids.getFirst());
        int last = siblings.indexOf(ids.getLast()) - ids.size() + 1;
        int index = switch (action) {
            case "to-front" -> rest.size();
            case "to-back" -> 0;
            case "raise" -> Math.min(rest.size(), last + 1);
            default -> Math.max(0, first - 1);
        };
        return index < rest.size() ? rest.get(index) : "";
    }

    private EffectHierarchy() {}
}

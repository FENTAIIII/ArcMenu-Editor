package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class EditorState {
    private EditorProtocol.SnapshotPacket snapshot;
    private String selectedId = "";
    private String selectionAnchor = "";
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private byte activeTab = EditorProtocol.TAB_FRONTEND;
    private String status = EditorI18n.text("arcmenu_editor.status.waiting");
    private boolean errorStatus;
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> locked = new HashSet<>();
    private long selectionVersion;
    private final ArrayDeque<SelectionRequest> requests = new ArrayDeque<>();

    /** Capture intent when sending, before a delayed response can observe a different selection. */
    public void expectReply(EditorProtocol.Packet packet) {
        byte operation;
        String renamedId = "";
        long gestureId = 0;
        switch (packet) {
            case EditorProtocol.MovePacket move -> { operation = EditorProtocol.OP_MOVE; gestureId = move.gestureId(); }
            case EditorProtocol.ResizePacket resize -> { operation = EditorProtocol.OP_RESIZE; gestureId = resize.gestureId(); }
            case EditorProtocol.CreatePacket ignored -> operation = EditorProtocol.OP_CREATE;
            case EditorProtocol.DuplicatePacket ignored -> operation = EditorProtocol.OP_DUPLICATE;
            case EditorProtocol.GroupPacket ignored -> operation = EditorProtocol.OP_GROUP;
            case EditorProtocol.InstantiateTemplatePacket ignored -> operation = EditorProtocol.OP_TEMPLATE_INSTANTIATE;
            case EditorProtocol.SetPropertyPacket property -> {
                operation = EditorProtocol.OP_PROPERTY;
                gestureId = property.gestureId();
                if (property.key().equals("id")) renamedId = property.nodeId();
            }
            default -> { return; }
        }
        requests.addLast(new SelectionRequest(operation, selectionVersion, activeTab, renamedId, gestureId));
    }

    private record SelectionRequest(byte operation, long selectionVersion, byte tab, String renamedId, long gestureId) {}

    public void update(EditorProtocol.SnapshotPacket value) {
        if (snapshot != null && !snapshot.menuId().equals(value.menuId())) {
            clearSelection();
            requests.clear();
        }
        snapshot = value;
        selected.removeIf(id -> find(id) == null);
        if (find(selectedId) == null) selectedId = selected.stream().findFirst().orElse("");
        if (find(selectionAnchor) == null) selectionAnchor = selectedId;
        status = EditorI18n.text(value.dirty() ? "arcmenu_editor.status.dirty" : "arcmenu_editor.status.synced");
        errorStatus = false;
    }

    /** Applies the compact authoritative response used between live-drag samples. */
    public long apply(EditorProtocol.AckPacket value) {
        status = value.message();
        errorStatus = false;
        SelectionRequest request = null;
        for (var iterator = requests.iterator(); iterator.hasNext();) {
            SelectionRequest candidate = iterator.next();
            if (candidate.operation == value.operation()) {
                request = candidate;
                iterator.remove();
                break;
            }
        }
        if (request != null && request.selectionVersion == selectionVersion && request.tab == activeTab
                && !value.nodeId().isBlank()) {
            if (value.operation() == EditorProtocol.OP_CREATE || value.operation() == EditorProtocol.OP_DUPLICATE
                    || value.operation() == EditorProtocol.OP_GROUP || value.operation() == EditorProtocol.OP_TEMPLATE_INSTANTIATE)
                selectOnly(value.nodeId());
            else if (!request.renamedId.isBlank() && selected.remove(request.renamedId)) {
                selected.add(value.nodeId());
                if (selectedId.equals(request.renamedId)) selectedId = value.nodeId();
                if (selectionAnchor.equals(request.renamedId)) selectionAnchor = value.nodeId();
            }
        }
        long gestureId = request == null ? 0 : request.gestureId;
        if (snapshot == null) return gestureId;
        byte responseTab = request == null ? activeTab : request.tab;
        List<EditorProtocol.NodeSnapshot> frontend = responseTab == EditorProtocol.TAB_FRONTEND
                ? updateGeometry(snapshot.frontend(), value) : snapshot.frontend();
        List<EditorProtocol.NodeSnapshot> backend = responseTab == EditorProtocol.TAB_BACKEND
                ? updateGeometry(snapshot.backend(), value) : snapshot.backend();
        snapshot = new EditorProtocol.SnapshotPacket(
                value.revision(), snapshot.menuId(), snapshot.canvasWidth(), snapshot.canvasHeight(),
                value.dirty(), value.saved(), snapshot.serverVersion(), frontend, backend,
                snapshot.images(), snapshot.templates());
        return gestureId;
    }

    private static List<EditorProtocol.NodeSnapshot> updateGeometry(
            List<EditorProtocol.NodeSnapshot> nodes, EditorProtocol.AckPacket value) {
        if (value.nodeId().isBlank() || !Double.isFinite(value.x()) || !Double.isFinite(value.y())
                || !Double.isFinite(value.width()) || !Double.isFinite(value.height())) return nodes;
        List<EditorProtocol.NodeSnapshot> result = new ArrayList<>(nodes.size());
        for (EditorProtocol.NodeSnapshot node : nodes) {
            if (node.id().equals(value.nodeId())) {
                result.add(new EditorProtocol.NodeSnapshot(node.id(), node.parentId(), node.kind(),
                        value.x(), value.y(), value.width(), value.height(), node.rotationZ(),
                        node.visible(), node.locked(), node.properties()));
            } else result.add(node);
        }
        return result;
    }

    public EditorProtocol.SnapshotPacket snapshot() { return snapshot; }
    public String selectedId() { return selectedId; }
    public Set<String> selectedIds() { return Collections.unmodifiableSet(selected); }
    public boolean selected(String id) { return selected.contains(id); }
    public void select(String id) { selectOnly(id); }
    public void selectOnly(String id) {
        selectionVersion++;
        selected.clear();
        selectedId = id == null ? "" : id;
        selectionAnchor = selectedId;
        if (!selectedId.isBlank()) selected.add(selectedId);
    }
    public void toggleSelection(String id) {
        if (id == null || id.isBlank()) return;
        selectionVersion++;
        if (!selected.remove(id)) {
            selected.add(id);
            selectedId = id;
        } else if (selectedId.equals(id)) {
            selectedId = selected.stream().reduce((first, second) -> second).orElse("");
        }
        selectionAnchor = id;
    }
    public void selectRange(List<String> orderedIds, String id) {
        if (id == null || id.isBlank()) return;
        selectionVersion++;
        int end = orderedIds.indexOf(id);
        int start = orderedIds.indexOf(selectionAnchor);
        if (start < 0 || end < 0) { selectOnly(id); return; }
        selected.clear();
        int low = Math.min(start, end);
        int high = Math.max(start, end);
        selected.addAll(orderedIds.subList(low, high + 1));
        selectedId = id;
    }
    public void selectAll(List<String> orderedIds) {
        selectionVersion++;
        selected.clear();
        selected.addAll(orderedIds);
        selectedId = orderedIds.isEmpty() ? "" : orderedIds.get(orderedIds.size() - 1);
        selectionAnchor = selectedId;
    }
    public void clearSelection() { selectOnly(""); }
    public byte activeTab() { return activeTab; }
    public void activeTab(byte value) { activeTab = value; clearSelection(); }
    public String status() { return status; }
    public boolean errorStatus() { return errorStatus; }
    public void status(String value) { status = value == null ? "" : value; errorStatus = false; }
    public void error(String value) { status = value == null ? "" : value; errorStatus = true; requests.clear(); }
    public Set<String> expanded() { return expanded; }
    public boolean locked(String id) { return locked.contains(id); }
    public void toggleLocked(String id) { if (!locked.add(id)) locked.remove(id); }

    public List<EditorProtocol.NodeSnapshot> nodes() {
        if (snapshot == null) return List.of();
        return activeTab == EditorProtocol.TAB_FRONTEND ? snapshot.frontend() : snapshot.backend();
    }

    public EditorProtocol.NodeSnapshot find(String id) {
        if (id == null || id.isBlank()) return null;
        return nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    public boolean hasChildren(String id) {
        return nodes().stream().anyMatch(node -> node.parentId().equals(id));
    }
}

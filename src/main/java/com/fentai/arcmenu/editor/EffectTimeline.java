package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import com.google.gson.Gson;
import java.util.stream.Collectors;

/** Composition layers, timed clips and inline temporal curves in the Effect workspace. */
final class EffectTimeline {
    interface Host {
        void effectDialog(String mode, String target, String property, int frame, EffectProtocol.Key key, EffectProtocol.Cue cue);
        default void serverStateChanged() {}
        default void timelineContextMenu(double x, double y, List<MenuItem> entries) {}
        default void timelineCommand(String action) {}
        default void timelineSearchFocus(EditBox field) {}
    }
    record MenuItem(String translationKey, Runnable action, boolean enabled) {
        MenuItem(String translationKey, Runnable action) { this(translationKey, action, true); }
    }
    private final Host screen;
    private final EditorState scene;
    private final EffectState state = ArcMenuEditorClient.EFFECT;
    private final List<Row> rows = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private final LinkedHashSet<KeyRef> selected = new LinkedHashSet<>();
    private List<KeyRef> clipboard = List.of();
    private KeyRef selectionAnchor;
    private EffectKeyframeGestures.Mapping gestureMapping;
    private double pointerOriginX, pointerOriginY;
    private boolean marquee, marqueeAdd, marqueeLayers, graphAutoZoom = true;
    private double marqueeX, marqueeY;
    private final Set<String> propertyFilters = new LinkedHashSet<>();
    private boolean animatedOnly, keysFocused;
    private List<KeyRef> gestureKeys = List.of();
    private EditorLayout.Rect panel;
    private int rowScroll;
    private double zoom = 1;
    private double offset;
    private int labelWidth;
    private int gridX;
    private int gridTop;
    private int gridWidth;
    private boolean scrubbing;
    private KeyRef dragging;
    private EffectProtocol.Cue draggingCue;
    private int dragFrame;
    private int dragOrigin;
    private EffectProtocol.Layer draggingLayer;
    private EffectProtocol.Marker draggingMarker;
    private int trimming;
    private int trimStart;
    private int trimEnd;
    private String query = "", graphTarget = "", graphProperty = "";
    private boolean graphMode, speedGraph, snapping = true, draggingValue;
    private EditorLayout.Rect searchBox, handleOne, handleTwo;
    private EditBox searchField;
    private final Set<String> collapsed = new HashSet<>();
    private final Set<String> seenGroups = new HashSet<>();
    private Set<String> previousSelection = Set.of();
    private String hierarchyProject = "";
    private EffectProtocol.Track graphTrack;
    private double graphMin, graphMax, dragValue, dragValueOrigin;
    private int graphTop, graphBottom, graphHandle;
    private EffectProtocol.Key handlePreview;
    private KeyRef handleKey;
    boolean searchFocused() { return searchField != null && searchField.isFocused(); }
    boolean graphVisible() { return graphMode; }
    void graphVisible(boolean value) { cancelGesture(); graphMode = value; blurSearch(); }
    void graphMode(boolean speed) { cancelGesture(); graphMode = true; speedGraph = speed; graphAutoZoom = true; blurSearch(); }
    private static EditorLayout.Rect searchBounds(EditorLayout.Rect dock) {
        int label = Math.min(Math.max(0, dock.width() - 80), Math.max(118, (int) (dock.width() * .42)));
        return new EditorLayout.Rect(dock.x() + 97, dock.y() + 26, Math.max(17, label - 103), 14);
    }
    EditBox rebuildSearch(Font font, EditorLayout.Rect dock, boolean preserveFocus) {
        int cursor = searchField == null ? query.length() : searchField.getCursorPosition();
        searchBox = searchBounds(dock);
        searchField = new EditBox(font, searchBox.x(), searchBox.y(), searchBox.width(), searchBox.height(), EffectUi.component(EffectUi.label("search")));
        searchField.setBordered(false); searchField.setTextColor(EffectUi.TEXT); searchField.setMaxLength(64);
        searchField.addFormatter((value, position) -> EffectUi.component(value).getVisualOrderText());
        searchField.setValue(query); searchField.setResponder(value -> { query = value; rowScroll = 0; });
        if (preserveFocus) {
            screen.timelineSearchFocus(searchField);
            searchField.setCursorPosition(cursor); searchField.setHighlightPos(cursor);
        }
        return searchField;
    }
    private void blurSearch() {
        if (searchFocused()) screen.timelineSearchFocus(null);
    }
    private record Row(String target, String property, EffectProtocol.Track track, EffectProtocol.Cue cue, int y) {}
    private record Control(String action, EditorLayout.Rect rect, String hint) {}
    record KeyRef(String target, String property, EffectProtocol.Key key) {}
    EffectTimeline(Host screen, EditorState scene) { this.screen = screen; this.scene = scene; }
    boolean active() { return state.active(); }
    void draw(GuiGraphicsExtractor g, Font font, EditorLayout.Rect dock, int mouseX, int mouseY) {
        panel = dock;
        var p = state.snapshot(); if (p == null) return;
        controls.clear(); rows.clear(); handleOne = null; handleTwo = null;
        if (!gestureActive() && !state.busy()) {
            var current = new LinkedHashSet<KeyRef>();
            for (var ref : selected) p.tracks().stream().filter(track -> same(track, ref)).findFirst().ifPresent(track -> track.keys().stream().filter(key -> key.frame() == ref.key.frame()).findFirst().ifPresent(key -> current.add(new KeyRef(ref.target, ref.property, key))));
            selected.clear(); selected.addAll(current);
        }
        if (!gestureActive()) {
            labelWidth = Math.min(Math.max(0, panel.width() - 80), Math.max(118, (int) (panel.width() * .42)));
            gridX = panel.x() + labelWidth; gridWidth = Math.max(16, panel.right() - gridX - 5); gridTop = panel.y() + 54;
        }
        g.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), 0xFF1B1B1B);
        g.fill(panel.x(), panel.y(), panel.right(), panel.y() + 20, EffectUi.HEADER);
        g.fill(panel.x(), panel.y() + 20, gridX, gridTop, EffectUi.PANEL);
        EffectUi.tab(g, font, EffectUi.fit(font, p.id() + (p.dirty() ? " *" : ""), Math.max(40, panel.width() - 208)), panel.x() + 10, panel.y() + 5, true);
        String[] actions = {"play", "stop", "key", "cue", "marker", "graph", "auto-key", "undo", "redo", "save", "settings"};
        int first = Math.max(panel.x() + 55, panel.right() - 201);
        for (int i = 0; i < actions.length; i++) {
            var rect = new EditorLayout.Rect(first + i * 18, panel.y() + 3, 16, 14);
            boolean enabled = actions[i].equals("graph") && graphMode || actions[i].equals("auto-key") && p.autoKey();
            g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), enabled ? EffectUi.ACCENT : rect.contains(mouseX, mouseY) ? 0xFF383838 : EffectUi.HEADER);
            icon(g, actions[i], rect.x() + 4, rect.y() + 3, p.playing(), enabled ? 0xFFFFFFFF : EffectUi.TEXT);
            controls.add(new Control(actions[i], rect, EditorI18n.text("arcmenu_editor.effect." + actions[i])));
        }
        EffectUi.mono(g, font, timecode(state.playhead(), p.fps()), panel.x() + 8, panel.y() + 25, 0xFF3D8FF5);
        EffectUi.mono(g, font, String.format(Locale.ROOT, "%05d (%.2f fps)", (int) state.playhead(), p.fps()), panel.x() + 8, panel.y() + 36, EffectUi.MUTED);
        searchBox = searchBounds(panel);
        if (searchField != null) {
            searchField.setPosition(searchBox.x(), searchBox.y()); searchField.setWidth(searchBox.width());
        }
        g.fill(searchBox.x(), searchBox.y(), searchBox.right(), searchBox.bottom(), 0xFF171717);
        g.outline(searchBox.x(), searchBox.y(), searchBox.width(), searchBox.height(), searchFocused() ? EffectUi.ACCENT : EffectUi.BORDER);
        if (query.isEmpty() && !searchFocused()) EffectUi.text(g, font, EffectUi.fit(font, EffectUi.label("search"), searchBox.width() - 6), searchBox.x() + 3, searchBox.y() + 4, EffectUi.MUTED);
        EffectUi.text(g, font, EffectUi.label("source_name"), panel.x() + 62, panel.y() + 46, EffectUi.MUTED);
        if (labelWidth > 205) EffectUi.text(g, font, EffectUi.label("parent"), gridX - 51, panel.y() + 46, EffectUi.MUTED);
        eye(g, panel.x() + 6, panel.y() + 49, EffectUi.MUTED);
        g.fill(panel.x() + 22, panel.y() + 46, panel.x() + 25, panel.y() + 49, EffectUi.MUTED);
        drawRuler(g, font);
        var nodes = scene.snapshot() == null ? List.<EditorProtocol.NodeSnapshot>of() : scene.snapshot().frontend();
        var hierarchy = new HashMap<String, EditorProtocol.NodeSnapshot>();
        for (var node : nodes) hierarchy.put(node.id(), node);
        updateHierarchy(p.id(), hierarchy);
        int index = 0;
        for (var node : nodes) {
            if (hiddenByGroup(node, hierarchy)) continue;
            var tracks = visibleTracks(node, p.tracks());
            if (!query.isEmpty() && !matches(node.id()) && tracks.stream().noneMatch(track -> matches(track.property()) || matches(EditorI18n.property(track.property())))) continue;
            var layer = layer(node.id());
            int rowY = gridTop + (index++ - rowScroll) * 16;
            boolean expanded = !collapsed.contains(node.id());
            if (visibleRow(rowY)) {
                rows.add(new Row(node.id(), "", null, null, rowY));
                int background = scene.selected(node.id()) ? 0xFF373737 : index % 2 == 0 ? 0xFF242424 : 0xFF202020;
                g.fill(panel.x(), rowY, graphMode ? gridX : panel.right(), rowY + 15, background);
                eye(g, panel.x() + 6, rowY + 8, layer.enabled() ? EffectUi.TEXT : 0xFF535353);
                if (layer.solo()) g.fill(panel.x() + 22, rowY + 6, panel.x() + 25, rowY + 9, 0xFFFFD568); else g.outline(panel.x() + 21, rowY + 5, 5, 5, 0xFF484848);
                g.outline(panel.x() + 33, rowY + 5, 5, 6, (node.locked() || scene.locked(node.id())) ? EffectUi.TEXT : 0xFF484848);
                int color = nodeColor(node.kind());
                g.fill(panel.x() + 43, rowY + 4, panel.x() + 49, rowY + 11, color);
                triangle(g, panel.x() + 53, rowY + 7, expanded, EffectUi.MUTED);
                int indent = Math.min(28, hierarchyDepth(node, hierarchy) * 7);
                EffectUi.text(g, font, EffectUi.fit(font, node.id(), labelWidth - 68 - (labelWidth > 205 ? 55 : 0) - indent), panel.x() + 63 + indent, rowY + 4, EffectUi.TEXT);
                if (labelWidth > 205) EffectUi.text(g, font, EffectUi.fit(font, node.parentId().isEmpty() ? "-" : node.parentId(), 46), gridX - 51, rowY + 4, EffectUi.MUTED);
                if (!graphMode) {
                    int start = layer.start(), end = layer.end();
                    if (draggingLayer != null && draggingLayer.target().equals(node.id())) { start = trimStart; end = trimEnd; }
                    bar(g, frameX(start), frameX(end), rowY + 3, rowY + 13, layer.enabled() ? dim(color) : 0xFF3E3E3E);
                    if (scene.selected(node.id())) { int left = Math.max(gridX, frameX(start)), right = Math.min(panel.right() - 5, frameX(end)); if (right > left) g.outline(left, rowY + 3, right - left, 10, color); }
                }
            }
            if (!expanded) continue;
            for (var track : tracks) {
                if (!query.isEmpty() && !matches(node.id()) && !matches(track.property()) && !matches(EditorI18n.property(track.property()))) continue;
                rowY = gridTop + (index++ - rowScroll) * 16;
                if (!visibleRow(rowY)) continue;
                rows.add(new Row(node.id(), track.property(), track, null, rowY));
                g.fill(panel.x(), rowY, graphMode ? gridX : panel.right(), rowY + 15, graphTarget.equals(node.id()) && graphProperty.equals(track.property()) ? 0xFF303030 : 0xFF1E1E1E);
                g.outline(panel.x() + 33, rowY + 5, 5, 5, track.enabled() && !track.keys().isEmpty() ? EffectUi.ACCENT : 0xFF545454);
                EffectUi.text(g, font, EffectUi.fit(font, EditorI18n.property(track.property()), labelWidth - 65), panel.x() + 62, rowY + 4, track.enabled() ? EffectUi.TEXT : 0xFF626262);
                if (!graphMode) for (var key : track.keys()) {
                    int frame = dragging != null && moving(track, key) ? key.frame() + dragFrame - dragging.key.frame() : key.frame();
                    int px = frameX(frame); if (px < gridX || px >= panel.right() - 5) continue;
                    diamond(g, px, rowY + 8, chosen(track, key) ? 0xFFFFD568 : 0xFFACACAC);
                }
            }
        }
        for (var cue : p.cues()) {
            if (!query.isEmpty() && !matches(cue.id()) && !matches(cue.kind()) && !matches(EffectDialog.choiceLabel("kind", cue.kind())) && !matches(cue.source())) continue;
            int rowY = gridTop + (index++ - rowScroll) * 16;
            if (!visibleRow(rowY)) continue;
            rows.add(new Row("", "", null, cue, rowY));
            g.fill(panel.x(), rowY, graphMode ? gridX : panel.right(), rowY + 15, 0xFF232323);
            eye(g, panel.x() + 6, rowY + 8, cue.enabled() ? EffectUi.TEXT : 0xFF535353);
            int color = cue.kind().equals("sound") || cue.kind().equals("nbs") ? 0xFF73A074 : 0xFFD5A458;
            g.fill(panel.x() + 43, rowY + 4, panel.x() + 49, rowY + 11, color);
            EffectUi.text(g, font, EffectUi.fit(font, cue.id(), labelWidth - 67), panel.x() + 62, rowY + 4, cue.enabled() ? EffectUi.TEXT : 0xFF656565);
            if (!graphMode) {
                int frame = draggingCue != null && draggingCue.id().equals(cue.id()) ? dragFrame : cue.frame();
                int end = cue.end() < 0 ? -1 : cue.end() + frame - cue.frame(), px = frameX(frame);
                double audioEnd = cue.kind().equals("sound") ? EffectAudioTimeline.clipEnd(p, cue, EffectAudioPreview.INSTANCE.durationSeconds(cue.source())) : Double.NaN;
                double clipEnd = Double.isFinite(audioEnd) ? audioEnd + frame - cue.frame() : end;
                if (clipEnd >= 0) bar(g, px, frameX(clipEnd), rowY + 3, rowY + 13, dim(color));
                if (Double.isFinite(audioEnd)) waveform(g, p, cue, frame, clipEnd, rowY, cue.enabled() ? 0xFF9ACDA0 : 0xFF626B63);
                if (px >= gridX && px < panel.right() - 5) diamond(g, px, rowY + 8, color);
            }
        }
        rowScroll = Math.max(0, Math.min(rowScroll, Math.max(0, index - Math.max(1, (panel.height() - 69) / 16))));
        g.fill(gridX - 1, panel.y() + 20, gridX, panel.bottom() - 14, EffectUi.BORDER);
        if (graphMode) drawGraph(g, font); else drawTicks(g);
        int px = frameX(scrubbing ? dragFrame : state.playhead());
        if (px >= gridX && px <= panel.right() - 5) {
            g.fill(px, panel.y() + 31, px + 1, panel.bottom() - 14, EffectUi.ACCENT);
            g.fill(px - 3, panel.y() + 28, px + 4, panel.y() + 33, EffectUi.ACCENT);
            triangle(g, px - 2, panel.y() + 33, true, EffectUi.ACCENT);
        }
        if (marquee) {
            int left = (int) Math.min(pointerOriginX, marqueeX), top = (int) Math.min(pointerOriginY, marqueeY);
            int right = (int) Math.max(pointerOriginX, marqueeX), bottom = (int) Math.max(pointerOriginY, marqueeY);
            g.fill(left, top, right, bottom, 0x263D8FF5); g.outline(left, top, Math.max(1, right - left), Math.max(1, bottom - top), EffectUi.ACCENT);
        }
        drawFooter(g, font, mouseX, mouseY);
    }
    private void waveform(GuiGraphicsExtractor g, EffectProtocol.Snapshot snapshot, EffectProtocol.Cue cue, int start, double end, int y, int color) {
        int left = Math.max(gridX, frameX(start)), right = Math.min(panel.right() - 5, frameX(end));
        for (int x = left; x < right; x += 2) {
            double a = Math.max(0, (offset + (x - gridX) / (double) gridWidth * range() - start) / snapshot.fps() * cue.pitch());
            double b = Math.max(a, (offset + (x + 2 - gridX) / (double) gridWidth * range() - start) / snapshot.fps() * cue.pitch());
            int amplitude = Math.min(4, (int) Math.ceil(EffectAudioPreview.INSTANCE.peak(cue.source(), a, b) * 4));
            if (amplitude > 0) g.fill(x, y + 8 - amplitude, Math.min(right, x + 1), y + 9 + amplitude, color);
        }
    }
    private void drawRuler(GuiGraphicsExtractor g, Font font) {
        var p = state.snapshot();
        g.fill(gridX, panel.y() + 20, panel.right(), gridTop, 0xFF222222);
        int workA = frameX(trimming == 4 ? trimStart : p.workStart()), workB = frameX(trimming == 5 ? trimEnd : p.workEnd());
        bar(g, workA, workB, panel.y() + 23, panel.y() + 27, 0xFF696969);
        if (workA >= gridX && workA < panel.right() - 5) g.fill(workA, panel.y() + 22, workA + 2, panel.y() + 29, 0xFFB5B5B5);
        if (workB >= gridX && workB < panel.right() - 5) g.fill(workB - 2, panel.y() + 22, workB, panel.y() + 29, 0xFFB5B5B5);
        int step = rulerStep();
        for (int frame = Math.max(0, (int) offset / step * step); frame <= Math.min(p.duration(), offset + range()); frame += step) {
            int px = frameX(frame); if (px < gridX || px >= panel.right() - 5) continue;
            g.fill(px, panel.y() + 47, px + 1, gridTop, 0xFF777777);
            EffectUi.mono(g, font, rulerLabel(frame, p.fps()), px + 3, panel.y() + 36, EffectUi.MUTED);
            if (step >= 4) for (int j = 1; j < 4; j++) { int tick = frameX(frame + step * j / 4.0); if (tick < panel.right() - 5) g.fill(tick, panel.y() + 50, tick + 1, gridTop, 0xFF494949); }
        }
        for (var marker : p.markers()) {
            int px = frameX(draggingMarker == marker ? dragFrame : marker.frame());
            if (px >= gridX && px < panel.right() - 5) { diamond(g, px, panel.y() + 31, 0xFFCAA973); EffectUi.text(g, font, EffectUi.fit(font, marker.label(), 48), px + 5, panel.y() + 26, 0xFFCAA973); }
        }
    }
    private void drawTicks(GuiGraphicsExtractor g) {
        int step = rulerStep();
        for (int frame = Math.max(0, (int) offset / step * step); frame <= Math.min(state.snapshot().duration(), offset + range()); frame += step) { int px = frameX(frame); if (px >= gridX && px < panel.right() - 5) g.fill(px, gridTop, px + 1, panel.bottom() - 14, 0x183F3F3F); }
    }
    private void drawFooter(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int top = panel.bottom() - 14;
        g.fill(panel.x(), top, panel.right(), panel.bottom(), EffectUi.PANEL);
        String hint = controls.stream().filter(control -> control.rect.contains(mouseX, mouseY)).map(Control::hint).findFirst().orElse(EditorI18n.text("arcmenu_editor.effect.hint"));
        EffectUi.text(g, font, EffectUi.fit(font, hint, labelWidth - 8), panel.x() + 5, top + 4, EffectUi.MUTED);
        if (graphMode) {
            int x = gridX + 6;
            for (var action : List.of("value-graph", "speed-graph", "fit-graph")) {
                String label = EffectUi.label(action.replace('-', '_')); int width = EffectUi.width(font, label) + 10;
                var rect = new EditorLayout.Rect(x, top + 1, width, 12);
                boolean current = action.equals(speedGraph ? "speed-graph" : "value-graph");
                EffectUi.text(g, font, label, x + 3, top + 4, current ? EffectUi.ACCENT : EffectUi.MUTED);
                controls.add(new Control(action, rect, label)); x += width;
            }
        } else {
            String label = EffectUi.label("snapping"); int width = EffectUi.width(font, label) + 16;
            var rect = new EditorLayout.Rect(gridX + 5, top + 1, width, 12);
            g.outline(rect.x() + 2, top + 3, 7, 7, snapping ? EffectUi.ACCENT : EffectUi.MUTED);
            if (snapping) g.fill(rect.x() + 4, top + 5, rect.x() + 7, top + 8, EffectUi.ACCENT);
            EffectUi.text(g, font, label, rect.x() + 13, top + 4, EffectUi.MUTED);
            controls.add(new Control("snapping", rect, label));
            int left = gridX + width + 15, right = panel.right() - 8;
            if (right > left + 20) {
                g.fill(left, top + 6, right, top + 8, 0xFF111111);
                int thumb = Math.max(10, (int) ((right - left) / zoom)), pos = left + (int) (offset / state.snapshot().duration() * (right - left));
                g.fill(pos, top + 4, Math.min(right, pos + thumb), top + 10, 0xFF696969);
            }
        }
    }
    private void drawGraph(GuiGraphicsExtractor g, Font font) {
        g.fill(gridX, gridTop, panel.right() - 5, panel.bottom() - 14, 0xFF181818);
        if (!gestureActive()) { graphTop = gridTop + 9; graphBottom = Math.max(graphTop + 10, panel.bottom() - 24); }
        graphTrack = resolveGraphTrack();
        if (graphTrack == null) { EffectUi.text(g, font, EffectUi.fit(font, EffectUi.label("graph_empty"), gridWidth - 20), gridX + 10, gridTop + 18, EffectUi.MUTED); return; }
        var preview = previewTrack(graphTrack);
        if (graphAutoZoom && !gestureActive()) {
            graphMin = Double.POSITIVE_INFINITY; graphMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i <= 192; i++) {
                double frame = offset + range() * i / 192.0, value = graphSample(preview, frame);
                if (Double.isFinite(value)) { graphMin = Math.min(graphMin, value); graphMax = Math.max(graphMax, value); }
            }
            if (speedGraph) { graphMin = Math.min(graphMin, 0); graphMax = Math.max(graphMax, 0); }
            if (!Double.isFinite(graphMin) || !Double.isFinite(graphMax)) { graphMin = 0; graphMax = 1; }
            double margin = Math.max(.1, (graphMax - graphMin) * .15); graphMin -= margin; graphMax += margin;
        }
        g.enableScissor(gridX + 1, gridTop, panel.right() - 5, panel.bottom() - 14);
        for (int i = 0; i <= 4; i++) {
            double value = graphMin + (graphMax - graphMin) * i / 4; int y = graphY(value);
            g.fill(gridX, y, panel.right() - 5, y + 1, Math.abs(value) < 1e-8 ? 0xFF454545 : 0xFF2C2C2C);
            EffectUi.mono(g, font, shortNumber(value), gridX + 3, y - 8, 0xFF777777);
        }
        drawTicks(g);
        int color = graphProperty.startsWith("offset.x") || graphProperty.equals("rotation.z") ? 0xFFE46A61 : graphProperty.startsWith("offset.y") ? 0xFF77B777 : 0xFF60A8E8;
        int count = Math.min(gridWidth, 1024), previousX = gridX, previousY = graphY(graphSample(preview, offset));
        for (int i = 1; i <= count; i++) {
            double frame = offset + range() * i / count; int x = frameX(frame), y = graphY(graphSample(preview, frame));
            EffectUi.line(g, previousX, previousY, x, y, color); previousX = x; previousY = y;
        }
        for (var key : graphTrack.keys()) {
            int frame = dragging != null && moving(graphTrack, key) ? key.frame() + dragFrame - dragging.key.frame() : key.frame();
            int x = frameX(frame);
            double value = draggingValue && dragging != null && same(graphTrack, dragging) && moving(graphTrack, key) ? Double.parseDouble(key.value()) + dragValue - Double.parseDouble(dragging.key.value()) : Double.parseDouble(key.value());
            int y = graphY(speedGraph ? EffectCurvePreview.speed(preview, frame, state.snapshot().fps()) : value);
            if (x >= gridX && x <= panel.right() - 5) diamond(g, x, y, chosen(graphTrack, key) ? 0xFFFFD568 : color);
        }
        if (!speedGraph) drawHandles(g, preview);
        g.disableScissor();
    }
    private void drawHandles(GuiGraphicsExtractor g, EffectProtocol.Track track) {
        KeyRef ref = handleKey != null ? handleKey : selected.stream().filter(candidate -> same(track, candidate)).findFirst().orElse(null);
        if (ref == null) return;
        int index = -1; for (int i = 0; i < track.keys().size(); i++) if (track.keys().get(i).frame() == ref.key.frame()) index = i;
        if (index < 0 || index + 1 >= track.keys().size()) return;
        var key = EffectCurvePreview.explicitBezier(track.keys().get(index)); if (!key.interpolation().equals("bezier")) return;
        var next = track.keys().get(index + 1);
        double a = Double.parseDouble(key.value()), b = Double.parseDouble(next.value()), span = next.frame() - key.frame();
        int x = frameX(key.frame()), y = graphY(a), nx = frameX(next.frame()), ny = graphY(b);
        int hx1 = frameX(key.frame() + key.x1() * span), hy1 = graphY(a + key.y1() * (b - a));
        int hx2 = frameX(key.frame() + key.x2() * span), hy2 = graphY(a + key.y2() * (b - a));
        EffectUi.line(g, x, y, hx1, hy1, 0xFFBDA15F); EffectUi.line(g, nx, ny, hx2, hy2, 0xFFBDA15F);
        handleOne = new EditorLayout.Rect(hx1 - 3, hy1 - 3, 7, 7); handleTwo = new EditorLayout.Rect(hx2 - 3, hy2 - 3, 7, 7);
        g.outline(handleOne.x(), handleOne.y(), 7, 7, 0xFFFFD568); g.outline(handleTwo.x(), handleTwo.y(), 7, 7, 0xFFFFD568);
    }
    private EffectProtocol.Track resolveGraphTrack() {
        var tracks = state.snapshot().tracks();
        var explicit = tracks.stream().filter(track -> track.target().equals(graphTarget) && track.property().equals(graphProperty) && EffectCurvePreview.numeric(track)).findFirst().orElse(null);
        if (explicit != null) return explicit;
        var ref = selected.stream().findFirst().orElse(null);
        var result = tracks.stream().filter(EffectCurvePreview::numeric).filter(track -> ref != null ? same(track, ref) : scene.selected(track.target())).findFirst().orElse(null);
        if (result == null) result = tracks.stream().filter(EffectCurvePreview::numeric).findFirst().orElse(null);
        if (result != null) { graphTarget = result.target(); graphProperty = result.property(); }
        return result;
    }
    private EffectProtocol.Track previewTrack(EffectProtocol.Track track) {
        if (handlePreview == null && dragging == null) return track;
        var keys = new ArrayList<EffectProtocol.Key>();
        for (var key : track.keys()) {
            if (handlePreview != null && handleKey != null && same(track, handleKey) && key.frame() == handleKey.key.frame()) keys.add(handlePreview);
            else if (dragging != null && same(track, dragging) && moving(track, key)) {
                int frame = key.frame() + dragFrame - dragging.key.frame();
                double value = Double.parseDouble(key.value()) + (draggingValue ? dragValue - Double.parseDouble(dragging.key.value()) : 0);
                keys.add(new EffectProtocol.Key(frame, Double.toString(value), key.interpolation(), key.x1(), key.y1(), key.x2(), key.y2()));
            } else keys.add(key);
        }
        keys.sort(Comparator.comparingInt(EffectProtocol.Key::frame));
        for (int i = 1; i < keys.size(); i++) if (keys.get(i).frame() == keys.get(i - 1).frame()) return track;
        return new EffectProtocol.Track(track.target(), track.property(), track.enabled(), keys);
    }
    private double graphSample(EffectProtocol.Track track, double frame) { return speedGraph ? EffectCurvePreview.speed(track, frame, state.snapshot().fps()) : EffectCurvePreview.value(track, frame); }
    private int graphY(double value) { double unit = (value - graphMin) / Math.max(1e-12, graphMax - graphMin); return (int) Math.round(graphBottom - Math.max(-1, Math.min(2, unit)) * (graphBottom - graphTop)); }
    private double graphValue(double y) { return graphMin + (graphBottom - y) / Math.max(1, graphBottom - graphTop) * (graphMax - graphMin); }
    private double range() { return gestureMapping == null ? Math.max(1, state.snapshot().duration() / zoom) : gestureMapping.frameRange(); }
    private int rulerStep() { double wanted = range() / Math.max(2, gridWidth / 56); int step = Math.max(1, (int) Math.pow(10, Math.floor(Math.log10(wanted)))); if (wanted / step > 5) step *= 10; else if (wanted / step > 2) step *= 5; else if (wanted / step > 1) step *= 2; return step; }
    private void updateHierarchy(String projectId, Map<String, EditorProtocol.NodeSnapshot> hierarchy) {
        if (!hierarchyProject.equals(projectId)) {
            hierarchyProject = projectId; collapsed.clear(); seenGroups.clear(); previousSelection = Set.of(); rowScroll = 0;
        }
        collapsed.retainAll(hierarchy.keySet());
        seenGroups.removeIf(id -> !hierarchy.containsKey(id) || hierarchy.get(id).kind() != EditorProtocol.KIND_GROUP);
        for (var node : hierarchy.values()) if (node.kind() == EditorProtocol.KIND_GROUP && seenGroups.add(node.id())) collapsed.add(node.id());
        var selection = Set.copyOf(scene.selectedIds());
        if (!selection.equals(previousSelection)) {
            for (String id : selection) {
                var node = hierarchy.get(id); var visited = new HashSet<String>();
                while (node != null && !node.parentId().isEmpty() && visited.add(node.parentId())) {
                    node = hierarchy.get(node.parentId());
                    if (node != null && node.kind() == EditorProtocol.KIND_GROUP) collapsed.remove(node.id());
                }
            }
            previousSelection = selection;
        }
    }
    private boolean hiddenByGroup(EditorProtocol.NodeSnapshot node, Map<String, EditorProtocol.NodeSnapshot> hierarchy) {
        var visited = new HashSet<String>();
        while (!node.parentId().isEmpty() && visited.add(node.parentId())) {
            node = hierarchy.get(node.parentId());
            if (node == null) return false;
            if (node.kind() == EditorProtocol.KIND_GROUP && collapsed.contains(node.id())) return true;
        }
        return false;
    }
    private static int hierarchyDepth(EditorProtocol.NodeSnapshot node, Map<String, EditorProtocol.NodeSnapshot> hierarchy) {
        int depth = 0; var visited = new HashSet<String>();
        while (!node.parentId().isEmpty() && visited.add(node.parentId())) {
            node = hierarchy.get(node.parentId()); if (node == null) break; depth++;
        }
        return depth;
    }
    private boolean visibleRow(int y) { return y >= gridTop && y + 16 <= panel.bottom() - 14; }
    private List<EffectProtocol.Track> visibleTracks(EditorProtocol.NodeSnapshot node, List<EffectProtocol.Track> tracks) {
        var result = new ArrayList<>(tracks.stream().filter(track -> track.target().equals(node.id()) &&
            (propertyFilters.isEmpty() || propertyFilters.stream().anyMatch(track.property()::startsWith)) && (!animatedOnly || !track.keys().isEmpty())).toList());
        if (!propertyFilters.isEmpty() && !animatedOnly && scene.selected(node.id())) {
            var existing = result.stream().map(EffectProtocol.Track::property).collect(Collectors.toSet());
            for (var property : node.properties()) if (propertyFilters.stream().anyMatch(property.key()::startsWith) && !existing.contains(property.key()))
                result.add(new EffectProtocol.Track(node.id(), property.key(), true, List.of()));
        }
        return result;
    }
    private boolean matches(String value) { return query.isEmpty() || value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
    private boolean chosen(EffectProtocol.Track track, EffectProtocol.Key key) { return selected.stream().anyMatch(ref -> same(track, ref) && ref.key.frame() == key.frame()); }
    private boolean moving(EffectProtocol.Track track, EffectProtocol.Key key) { return gestureKeys.stream().anyMatch(ref -> same(track, ref) && ref.key.frame() == key.frame()); }
    private static boolean same(EffectProtocol.Track track, KeyRef ref) { return track.target().equals(ref.target) && track.property().equals(ref.property); }
    private EffectProtocol.Layer layer(String target) { return state.snapshot().layers().stream().filter(layer -> layer.target().equals(target)).findFirst().orElse(new EffectProtocol.Layer(target, 0, state.snapshot().duration(), true, false)); }
    private static int nodeColor(byte kind) { return switch (kind) { case EditorProtocol.KIND_GROUP -> 0xFFB6A777; case EditorProtocol.KIND_TEXT -> 0xFFD24F54; case EditorProtocol.KIND_IMAGE, EditorProtocol.KIND_GIF -> 0xFF7671CC; default -> 0xFF7395B4; }; }
    private static int dim(int color) { return 0xFF000000 | ((color >> 16 & 255) * 3 / 5 << 16) | ((color >> 8 & 255) * 3 / 5 << 8) | (color & 255) * 3 / 5; }
    private static String timecode(double frame, double fps) { int total = (int) Math.floor(frame / fps), residual = (int) (frame - total * fps); return String.format(Locale.ROOT, "%d:%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60, residual); }
    private static String rulerLabel(int frame, double fps) { int second = (int) (frame / fps); return frame < fps ? frame + "f" : String.format(Locale.ROOT, "%02d:%02d", second, (int) (frame - second * fps)); }
    private static String shortNumber(double value) { return Math.abs(value) >= 10000 ? String.format(Locale.ROOT, "%.1e", value) : String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", ""); }
    private static void eye(GuiGraphicsExtractor g, int x, int y, int color) { g.fill(x - 3, y, x + 4, y + 1, color); g.fill(x - 2, y - 1, x + 3, y + 2, color); g.fill(x, y - 2, x + 1, y + 3, color); g.fill(x, y, x + 1, y + 1, 0xFF232323); }
    private static void triangle(GuiGraphicsExtractor g, int x, int y, boolean down, int color) { for (int i = 0; i < 3; i++) if (down) g.fill(x + i, y + i, x + 5 - i, y + i + 1, color); else g.fill(x + i, y - 2 + i, x + i + 1, y + 3 - i, color); }
    private static void icon(GuiGraphicsExtractor g, String name, int x, int y, boolean playing, int color) {
        switch (name) {
            case "play" -> { if (playing) { g.fill(x, y, x + 2, y + 8, color); g.fill(x + 5, y, x + 7, y + 8, color); } else triangle(g, x + 2, y + 4, false, color); }
            case "stop" -> g.fill(x + 1, y + 1, x + 7, y + 7, color);
            case "key" -> diamond(g, x + 4, y + 4, color);
            case "cue" -> { g.fill(x + 3, y, x + 4, y + 8, color); g.fill(x, y + 3, x + 8, y + 4, color); }
            case "marker" -> { g.fill(x + 1, y, x + 2, y + 8, color); g.fill(x + 2, y, x + 7, y + 4, color); }
            case "graph" -> { EffectUi.line(g, x, y + 7, x + 3, y + 6, color); EffectUi.line(g, x + 3, y + 6, x + 5, y + 1, color); EffectUi.line(g, x + 5, y + 1, x + 8, y, color); }
            case "auto-key" -> { diamond(g, x + 4, y + 4, color); g.fill(x + 3, y + 3, x + 6, y + 6, 0xFF1F1F1F); }
            case "undo", "redo" -> { boolean left = name.equals("undo"); EffectUi.line(g, x + (left ? 1 : 7), y + 3, x + (left ? 6 : 2), y + 3, color); g.fill(x + (left ? 5 : 2), y + 3, x + (left ? 7 : 4), y + 7, color); triangle(g, x + (left ? 1 : 5), y + 3, false, color); }
            case "save" -> { g.outline(x, y, 8, 8, color); g.fill(x + 2, y + 1, x + 6, y + 3, color); g.outline(x + 2, y + 5, 4, 3, color); }
            default -> { for (int i = 0; i < 3; i++) g.fill(x + i * 3, y + 3, x + i * 3 + 2, y + 5, color); }
        }
    }
    private int frameX(double frame) { var map = mapping(); return (int) map.left() + (int) ((frame - map.firstFrame()) / Math.max(1, map.frameRange()) * map.width()); }
    private void bar(GuiGraphicsExtractor g, int start, int end, int y, int bottom, int color) { start = Math.max(gridX, start); end = Math.min(panel.right() - 5, end); if (end > start) g.fill(start, y, end, bottom, color); }
    private int at(double x) { return mapping().boundedFrame(x); }
    int frameAt(double x) {return at(x);}
    JsonObject workspaceMetadata() {
        var result=new JsonObject();result.addProperty("gridX",gridX);result.addProperty("gridWidth",gridWidth);
        result.add("searchBounds",new Gson().toJsonTree(searchBox));result.addProperty("searchQuery",query);result.addProperty("searchFocused",searchFocused());
        result.addProperty("offset",offset);result.addProperty("range",range());result.addProperty("graph",graphMode);
        result.addProperty("graphTarget",graphTarget);result.addProperty("graphProperty",graphProperty);result.addProperty("graphTop",graphTop);result.addProperty("graphBottom",graphBottom);result.addProperty("graphMin",graphMin);result.addProperty("graphMax",graphMax);result.addProperty("speedGraph",speedGraph);
        var list=new JsonArray();for(var row:rows){var entry=new JsonObject();entry.addProperty("target",row.target);entry.addProperty("property",row.property);entry.addProperty("y",row.y);list.add(entry);}result.add("rows",list);return result;
    }
    private static void diamond(GuiGraphicsExtractor g, int x, int y, int color) { g.fill(x - 3, y, x + 4, y + 1, color); g.fill(x - 2, y - 1, x + 3, y + 2, color); g.fill(x - 1, y - 2, x + 2, y + 3, color); g.fill(x, y - 3, x + 1, y + 4, color); }
    int selectedKeyCount() { return selected.size(); }
    boolean keyEditingContext() { return graphMode || keysFocused || !selected.isEmpty(); }
    boolean easingAvailable() {
        if (selected.isEmpty()) return false;
        for (var ref : selected) {
            if (locked(ref.target)) return false;
            if (ref.property.equals("color")) continue;
            try { if (!Double.isFinite(Double.parseDouble(ref.key.value()))) return false; }
            catch (NumberFormatException ignored) { return false; }
        }
        return true;
    }
    private boolean gestureActive() { return dragging != null || graphHandle != 0 || marquee || trimming != 0 || scrubbing || draggingCue != null || draggingMarker != null; }
    private EffectKeyframeGestures.Mapping mapping() {
        return gestureMapping != null ? gestureMapping : new EffectKeyframeGestures.Mapping(gridX, gridWidth, offset, range(), graphTop, graphBottom, graphMin, graphMax, state.snapshot().duration());
    }
    private void beginGesture(MouseButtonEvent event) {
        gestureMapping = new EffectKeyframeGestures.Mapping(gridX, gridWidth, offset, range(), graphTop, graphBottom, graphMin, graphMax, state.snapshot().duration());
        pointerOriginX = event.x(); pointerOriginY = event.y(); gestureKeys = selected.stream().filter(ref -> !locked(ref.target)).toList();
    }
    private void cancelGesture() {
        dragging = null; draggingValue = false; draggingCue = null; draggingMarker = null; draggingLayer = null;
        graphHandle = 0; handlePreview = null; handleKey = null; trimming = 0; scrubbing = false; marquee = false;
        gestureMapping = null; gestureKeys = List.of();
    }
    private boolean locked(String target) {
        var node = scene.find(target); return scene.locked(target) || node != null && node.locked();
    }
    private void selectLayer(String target, MouseButtonEvent event) {
        if (event.hasShiftDown()) scene.selectRange(scene.snapshot().frontend().stream().map(EditorProtocol.NodeSnapshot::id).toList(), target);
        else if (event.hasControlDown()) scene.toggleSelection(target);
        else if (!scene.selected(target)) scene.selectOnly(target);
        screen.serverStateChanged();
    }
    private void selectKey(KeyRef ref, MouseButtonEvent event) {
        if (event.hasShiftDown() && selectionAnchor != null && selectionAnchor.target.equals(ref.target) && selectionAnchor.property.equals(ref.property)) {
            int first = Math.min(selectionAnchor.key.frame(), ref.key.frame()), last = Math.max(selectionAnchor.key.frame(), ref.key.frame());
            state.snapshot().tracks().stream().filter(track -> same(track, ref)).findFirst().ifPresent(track -> track.keys().stream()
                .filter(key -> key.frame() >= first && key.frame() <= last).forEach(key -> selected.add(new KeyRef(ref.target, ref.property, key))));
        } else if (event.hasControlDown()) {
            if (!selected.remove(ref)) selected.add(ref);
            selectionAnchor = ref;
        } else {
            if (!selected.contains(ref)) { selected.clear(); selected.add(ref); }
            selectionAnchor = ref;
        }
    }
    private void beginMarquee(MouseButtonEvent event, boolean layers) {
        beginGesture(event); marquee = true; marqueeLayers = layers;
        marqueeAdd = event.hasShiftDown() || event.hasControlDown(); marqueeX = event.x(); marqueeY = event.y();
    }
    boolean click(MouseButtonEvent event, boolean doubleClick) {
        if (!active() || panel == null || !panel.contains(event.x(), event.y())) { blurSearch(); return false; }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        if (searchBox != null && searchBox.contains(event.x(), event.y())) {
            if (searchField != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                screen.timelineSearchFocus(searchField); searchField.mouseClicked(event, doubleClick);
            }
            return true;
        }
        blurSearch();
        for (var control : controls) if (control.rect.contains(event.x(), event.y())) { if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) control(control.action); return true; }
        if (event.y() < gridTop && event.x() >= gridX) {
            int frame = at(event.x());
            var marker = state.snapshot().markers().stream().filter(value -> Math.abs(event.x() - frameX(value.frame())) <= 5).findFirst().orElse(null);
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                var menu = new ArrayList<MenuItem>();
                menu.add(item("add_marker", () -> screen.effectDialog("marker", "", "", frame, null, null)));
                if (marker != null) {
                    menu.add(item("edit_marker", () -> screen.effectDialog("marker", "", "", marker.frame(), null, null)));
                    menu.add(item("delete_marker", () -> state.submit("delete-marker", "", "", marker.frame(), 0, "", "linear", 0, 0, 0, 0)));
                }
                menu.add(item("work_start", () -> workRange(frame, state.snapshot().workEnd())));
                menu.add(item("work_end", () -> workRange(state.snapshot().workStart(), Math.max(1, frame))));
                showMenu(event, menu); return true;
            }
            if (event.y() >= panel.y() + 21 && event.y() <= panel.y() + 28) {
                if (Math.abs(event.x() - frameX(state.snapshot().workStart())) <= 6) trimming = 4;
                else if (Math.abs(event.x() - frameX(state.snapshot().workEnd())) <= 6) trimming = 5;
                if (trimming != 0) { beginGesture(event); trimStart = state.snapshot().workStart(); trimEnd = state.snapshot().workEnd(); return true; }
            }
            if (event.y() >= panel.y() + 28 && event.y() <= panel.y() + 34 && marker != null) {
                if (doubleClick) screen.effectDialog("marker", "", "", marker.frame(), null, null);
                else { beginGesture(event); draggingMarker = marker; dragFrame = marker.frame(); }
                return true;
            }
            beginGesture(event); dragFrame = frame; scrubbing = true; state.scrub(dragFrame); return true;
        }
        if (graphMode && event.x() >= gridX && event.y() < panel.bottom() - 14) return graphClick(event, doubleClick);
        Row row = rows.stream().filter(r -> event.y() >= r.y && event.y() < r.y + 16).findFirst().orElse(null);
        if (row == null) {
            keysFocused = event.x() >= gridX;
            if (event.y() < panel.bottom() - 14) {
                if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, emptyMenu(at(event.x())));
                else beginMarquee(event, event.x() < gridX);
            }
            return true;
        }
        if (row.cue != null) {
            keysFocused = false;
            var cue = row.cue;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                showMenu(event, List.of(item("edit_cue", () -> screen.effectDialog("cue", cue.id(), "", cue.frame(), null, cue)),
                    item("duplicate_cue", () -> state.submit("cue", uniqueCueId(cue.id()), cue.kind(), (int) state.playhead(), cue.end() < 0 ? -1 : Math.min(state.snapshot().duration(), (int) state.playhead() + cue.end() - cue.frame()), cue.source(), cue.enabled() ? "linear" : "muted", cue.volume(), cue.pitch(), 0, 0)),
                    item(cue.enabled() ? "mute_cue" : "unmute_cue", () -> toggleCue(cue)),
                    item("delete_cue", () -> state.submit("delete-cue", cue.id(), "", 0, 0, "", "linear", 0, 0, 0, 0))));
            } else if (event.x() < panel.x() + 16) toggleCue(cue);
            else if (doubleClick || event.x() < gridX) screen.effectDialog("cue", cue.id(), "", cue.frame(), null, cue);
            else { beginGesture(event); draggingCue = cue; dragOrigin = at(event.x()); dragFrame = cue.frame(); }
            return true;
        }
        if (row.track == null) {
            keysFocused = false;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !event.hasShiftDown() && !event.hasControlDown()) selected.clear();
            selectLayer(row.target, event);
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { showMenu(event, layerMenu(row.target)); return true; }
            if (doubleClick) screen.effectDialog("layer", row.target, "", 0, null, null);
            else if (event.x() < panel.x() + 16) { var old = layer(row.target); setLayer(old, old.start(), old.end(), !old.enabled(), old.solo()); }
            else if (event.x() < panel.x() + 29) { var old = layer(row.target); setLayer(old, old.start(), old.end(), old.enabled(), !old.solo()); }
            else if (event.x() < panel.x() + 43) scene.toggleLocked(row.target);
            else if (event.x() >= panel.x() + 50 && event.x() < panel.x() + 62) { if (!collapsed.remove(row.target)) collapsed.add(row.target); }
            else if (event.x() >= gridX && !locked(row.target)) {
                var old = layer(row.target);
                if (Math.abs(event.x() - frameX(old.start())) <= 5) trimming = 1;
                else if (Math.abs(event.x() - frameX(old.end())) <= 5) trimming = 2;
                else if (event.hasAltDown()) trimming = 3;
                if (trimming != 0) { beginGesture(event); draggingLayer = old; trimStart = old.start(); trimEnd = old.end(); dragOrigin = at(event.x()); }
                else beginMarquee(event, false);
            }
            return true;
        }
        if (!scene.selected(row.target)) { scene.selectOnly(row.target); screen.serverStateChanged(); }
        keysFocused = true;
        graphTarget = row.target; graphProperty = row.property;
        if (event.x() >= panel.x() + 29 && event.x() < panel.x() + 43 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (row.track.keys().isEmpty()) {
                var node = scene.find(row.target); var value = node == null ? null : node.properties().stream().filter(property -> property.key().equals(row.property)).findFirst().orElse(null);
                if (value != null) state.key(row.target, row.property, new EffectProtocol.Key((int) state.playhead(), value.value(), "linear", 1.0 / 3, 0, 2.0 / 3, 1), (int) state.playhead());
            } else state.submit("track", row.target, row.property, 0, 0, Boolean.toString(!row.track.enabled()), "linear", 0, 0, 0, 0);
            return true;
        }
        if (event.x() < gridX) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, trackMenu(row));
            else if (doubleClick) selectTrackKeys(row.track);
            return true;
        }
        var key = row.track.keys().stream().min(Comparator.comparingDouble(k -> Math.abs(frameX(k.frame()) - event.x()))).orElse(null);
        if (key == null || Math.abs(frameX(key.frame()) - event.x()) > 7) {
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, trackMenu(row));
            else if (doubleClick) screen.effectDialog("key", row.target, row.property, at(event.x()), null, null);
            else beginMarquee(event, false);
            return true;
        }
        var ref = new KeyRef(row.target, row.property, key);
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || !selected.contains(ref)) selectKey(ref, event);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, keyMenu(ref));
        else if (doubleClick) screen.effectDialog("key", row.target, row.property, key.frame(), key, null);
        else if (selected.contains(ref) && !locked(ref.target)) { beginGesture(event); dragging = ref; dragOrigin = at(event.x()); dragFrame = key.frame(); }
        return true;
    }
    private boolean graphClick(MouseButtonEvent event, boolean doubleClick) {
        keysFocused = true;
        if (graphTrack == null) { if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, emptyMenu(at(event.x()))); else beginMarquee(event, false); return true; }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !speedGraph && (handleOne != null && handleOne.contains(event.x(), event.y()) || handleTwo != null && handleTwo.contains(event.x(), event.y()))) {
            var ref = selected.stream().filter(candidate -> same(graphTrack, candidate)).findFirst().orElse(null);
            if (ref != null && !locked(ref.target)) { beginGesture(event); handleKey = ref; handlePreview = EffectCurvePreview.explicitBezier(ref.key); graphHandle = handleOne != null && handleOne.contains(event.x(), event.y()) ? 1 : 2; return true; }
        }
        var key = graphTrack.keys().stream().min(Comparator.comparingDouble(candidate -> Math.hypot(frameX(candidate.frame()) - event.x(), graphY(speedGraph ? graphSample(graphTrack, candidate.frame()) : Double.parseDouble(candidate.value())) - event.y()))).orElse(null);
        if (key != null && Math.hypot(frameX(key.frame()) - event.x(), graphY(speedGraph ? graphSample(graphTrack, key.frame()) : Double.parseDouble(key.value())) - event.y()) <= 8) {
            var ref = new KeyRef(graphTrack.target(), graphTrack.property(), key);
            if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || !selected.contains(ref)) selectKey(ref, event);
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, keyMenu(ref));
            else if (doubleClick) screen.effectDialog("key", graphTrack.target(), graphTrack.property(), key.frame(), key, null);
            else if (selected.contains(ref) && !locked(ref.target)) { beginGesture(event); dragging = ref; dragOrigin = at(event.x()); dragFrame = key.frame(); draggingValue = !speedGraph; dragValueOrigin = mapping().value(event.y()); dragValue = Double.parseDouble(key.value()); }
        } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) showMenu(event, emptyMenu(at(event.x())));
        else if (doubleClick) screen.effectDialog("key", graphTrack.target(), graphTrack.property(), at(event.x()), null, null);
        else beginMarquee(event, false);
        return true;
    }
    private void toggleCue(EffectProtocol.Cue cue) { state.submit("cue", cue.id(), cue.kind(), cue.frame(), cue.end(), cue.source(), cue.enabled() ? "muted" : "linear", cue.volume(), cue.pitch(), 0, 0); }
    private String uniqueCueId(String prefix) {
        var ids = state.snapshot().cues().stream().map(EffectProtocol.Cue::id).collect(Collectors.toSet());
        int number = 2; String id = prefix + "_" + number; while (ids.contains(id)) id = prefix + "_" + ++number; return id;
    }
    private MenuItem item(String key, Runnable action) { return new MenuItem("arcmenu_editor.effect.context." + key, action); }
    private MenuItem item(String key, String action, boolean enabled) { return new MenuItem("arcmenu_editor.effect.context." + key, () -> command(action), enabled); }
    private void showMenu(MouseButtonEvent event, List<MenuItem> menu) { screen.timelineContextMenu(event.x(), event.y(), menu); }
    private List<MenuItem> emptyMenu(int frame) {
        var menu = new ArrayList<MenuItem>();
        menu.add(item("add_key", () -> screen.effectDialog("key", scene.selectedId(), graphProperty, frame, null, null)));
        menu.add(item("add_cue", () -> screen.effectDialog("cue", "", "", frame, null, null)));
        menu.add(item("add_marker", () -> screen.effectDialog("marker", "", "", frame, null, null)));
        menu.add(item("paste_keys", "paste-keys", !clipboard.isEmpty())); menu.add(item("select_all_keys", "select-all-keys", !state.snapshot().tracks().isEmpty()));
        menu.add(item("fit_timeline", "fit-graph", true)); menu.add(item("settings", () -> screen.timelineCommand("settings")));
        return menu;
    }
    private List<MenuItem> layerMenu(String target) {
        var old = layer(target); var menu = new ArrayList<MenuItem>();
        menu.add(item("layer_settings", () -> screen.effectDialog("layer", target, "", 0, null, null)));
        menu.add(item("split_layer", () -> screen.timelineCommand("split")));
        menu.add(item("duplicate_layer", () -> screen.timelineCommand("duplicate")));
        menu.add(item("rename_layer", () -> screen.timelineCommand("rename")));
        menu.add(item(old.enabled() ? "hide_layer" : "show_layer", () -> setLayer(old, old.start(), old.end(), !old.enabled(), old.solo())));
        menu.add(item(old.solo() ? "unsolo_layer" : "solo_layer", () -> setLayer(old, old.start(), old.end(), old.enabled(), !old.solo())));
        menu.add(item(locked(target) ? "unlock_layer" : "lock_layer", () -> { scene.toggleLocked(target); screen.serverStateChanged(); }));
        menu.add(item("trim_start", () -> trimSelected(true))); menu.add(item("trim_end", () -> trimSelected(false)));
        menu.add(item("delete_layer", () -> screen.timelineCommand("delete"))); return menu;
    }
    private List<MenuItem> trackMenu(Row row) {
        var menu = new ArrayList<MenuItem>();
        menu.add(item("add_key", () -> screen.effectDialog("key", row.target, row.property, (int) state.playhead(), null, null)));
        menu.add(item("select_track_keys", () -> selectTrackKeys(row.track)));
        if (!row.track.keys().isEmpty()) menu.add(item(row.track.enabled() ? "disable_track" : "enable_track", () -> state.submit("track", row.target, row.property, 0, 0, Boolean.toString(!row.track.enabled()), "linear", 0, 0, 0, 0)));
        menu.add(item("value_graph", () -> graphMode(false))); menu.add(item("speed_graph", () -> graphMode(true)));
        if (!row.track.keys().isEmpty()) menu.add(item("delete_track", () -> state.submit("delete-track", row.target, row.property, 0, 0, "", "linear", 0, 0, 0, 0))); return menu;
    }
    private List<MenuItem> keyMenu(KeyRef ref) {
        var menu = new ArrayList<MenuItem>();
        menu.add(item("edit_key", () -> screen.effectDialog("key", ref.target, ref.property, ref.key.frame(), ref.key, null)));
        for (String action : List.of("copy-keys", "cut-keys", "paste-keys", "ease", "ease-in", "ease-out", "linear", "hold", "bezier", "auto-bezier", "reverse-keys", "select-equal-keys", "select-previous-keys", "select-following-keys", "select-all-keys", "delete-keys"))
            menu.add(item(action.replace('-', '_'), action, action.equals("paste-keys") ? !clipboard.isEmpty() : action.startsWith("ease") ? easingAvailable() : action.equals("reverse-keys") ? reversible() : action.equals("copy-keys") || !locked(ref.target)));
        return menu;
    }
    private void selectTrackKeys(EffectProtocol.Track track) { selected.clear(); track.keys().forEach(key -> selected.add(new KeyRef(track.target(), track.property(), key))); selectionAnchor = selected.stream().findFirst().orElse(null); }
    private int snap(int frame, boolean bypass) {
        if (!snapping || bypass) return frame;
        int best = frame; double distance = 5;
        var candidates = new ArrayList<Integer>(List.of((int) state.playhead(), state.snapshot().workStart(), state.snapshot().workEnd()));
        state.snapshot().markers().forEach(marker -> candidates.add(marker.frame()));
        state.snapshot().layers().forEach(layer -> { candidates.add(layer.start()); candidates.add(layer.end()); });
        for (var track : state.snapshot().tracks()) for (var key : track.keys()) if (!chosen(track, key)) candidates.add(key.frame());
        for (int candidate : candidates) { double delta = Math.abs(candidate - frame) / mapping().frameRange() * mapping().width(); if (delta < distance) { distance = delta; best = candidate; } }
        return best;
    }
    boolean drag(MouseButtonEvent event) {
        if (!active()) return false;
        if (marquee) { marqueeX = Math.max(panel.x(), Math.min(panel.right() - 5, event.x())); marqueeY = Math.max(gridTop, Math.min(panel.bottom() - 15, event.y())); return true; }
        if (graphHandle != 0 && handleKey != null && graphTrack != null) {
            var keys = graphTrack.keys(); int index = keys.indexOf(handleKey.key);
            if (index < 0 || index + 1 >= keys.size()) return true;
            var next = keys.get(index + 1); double a = Double.parseDouble(handleKey.key.value()), b = Double.parseDouble(next.value());
            double frame = mapping().frame(event.x());
            double x = Math.max(0, Math.min(1, (frame - handleKey.key.frame()) / (next.frame() - handleKey.key.frame())));
            double y = Math.abs(b - a) < 1e-12 ? (graphHandle == 1 ? 0 : 1) : Math.max(-1000, Math.min(1000, (mapping().value(event.y()) - a) / (b - a)));
            var key = handlePreview; handlePreview = new EffectProtocol.Key(key.frame(), key.value(), "bezier", graphHandle == 1 ? x : key.x1(), graphHandle == 1 ? y : key.y1(), graphHandle == 2 ? x : key.x2(), graphHandle == 2 ? y : key.y2());
            return true;
        }
        if (trimming != 0) {
            int frame = snap(at(event.x()), event.hasControlDown());
            if (trimming == 1 || trimming == 4) trimStart = Math.min(trimEnd - 1, frame);
            if (trimming == 2 || trimming == 5) trimEnd = Math.max(trimStart + 1, frame);
            if (trimming == 3) { int delta = Math.max(-draggingLayer.start(), Math.min(state.snapshot().duration() - draggingLayer.end(), mapping().delta(event.x(), pointerOriginX))); trimStart = draggingLayer.start() + delta; trimEnd = draggingLayer.end() + delta; }
            return true;
        }
        if (draggingMarker != null) { dragFrame = snap(at(event.x()), event.hasControlDown()); return true; }
        if (scrubbing) { int next = at(event.x()); if (next != dragFrame) { dragFrame = next; state.scrub(next); } return true; }
        if (dragging != null) {
            int delta = mapping().delta(event.x(), pointerOriginX);
            boolean vertical = draggingValue && event.hasShiftDown() && Math.abs(event.y() - pointerOriginY) > Math.abs(event.x() - pointerOriginX);
            if (vertical) delta = 0;
            else delta = snap(dragging.key.frame() + delta, event.hasControlDown()) - dragging.key.frame();
            dragFrame = dragging.key.frame() + EffectKeyframeGestures.boundedDelta(refs(gestureKeys), delta, state.snapshot().duration());
            if (draggingValue) dragValue = Double.parseDouble(dragging.key.value()) + (event.hasShiftDown() && !vertical ? 0 : mapping().value(event.y()) - dragValueOrigin);
            return true;
        }
        if (draggingCue != null) {
            int last = draggingCue.end() < 0 ? draggingCue.frame() + 1 : draggingCue.end();
            int delta = Math.max(-draggingCue.frame(), Math.min(state.snapshot().duration() - last, mapping().delta(event.x(), pointerOriginX)));
            dragFrame = draggingCue.frame() + delta; return true;
        }
        return false;
    }
    boolean release(MouseButtonEvent event) {
        if (marquee) {
            if (Math.hypot(marqueeX - pointerOriginX, marqueeY - pointerOriginY) < 3) {
                if (!marqueeAdd) { selected.clear(); if (marqueeLayers) { scene.clearSelection(); screen.serverStateChanged(); } }
                if (!marqueeLayers) state.seek(at(event.x()));
            } else if (marqueeLayers) {
                var ids = new LinkedHashSet<String>(); if (marqueeAdd) ids.addAll(scene.selectedIds());
                for (var row : rows) if (row.track == null && row.cue == null && EffectKeyframeGestures.inside(panel.x() + 65, row.y + 8, pointerOriginX, pointerOriginY, marqueeX, marqueeY)) ids.add(row.target);
                scene.selectAll(new ArrayList<>(ids)); screen.serverStateChanged();
            } else {
                if (!marqueeAdd) selected.clear();
                if (graphMode && graphTrack != null) {
                    for (var key : graphTrack.keys()) if (EffectKeyframeGestures.inside(frameX(key.frame()), graphY(speedGraph ? graphSample(graphTrack, key.frame()) : Double.parseDouble(key.value())), pointerOriginX, pointerOriginY, marqueeX, marqueeY)) selected.add(new KeyRef(graphTrack.target(), graphTrack.property(), key));
                } else for (var row : rows) if (row.track != null) for (var key : row.track.keys())
                    if (EffectKeyframeGestures.inside(frameX(key.frame()), row.y + 8, pointerOriginX, pointerOriginY, marqueeX, marqueeY)) selected.add(new KeyRef(row.target, row.property, key));
                selectionAnchor = selected.stream().findFirst().orElse(null);
            }
            cancelGesture(); return true;
        }
        if (graphHandle != 0) { if (handlePreview != null && handleKey != null && !handlePreview.equals(handleKey.key)) state.key(handleKey.target, handleKey.property, handlePreview, handlePreview.frame()); cancelGesture(); return true; }
        if (trimming != 0) {
            if (draggingLayer != null && (trimStart != draggingLayer.start() || trimEnd != draggingLayer.end())) setLayer(draggingLayer, trimStart, trimEnd, draggingLayer.enabled(), draggingLayer.solo());
            else if (draggingLayer == null) workRange(trimStart, trimEnd);
            cancelGesture(); return true;
        }
        if (draggingMarker != null) { if (dragFrame != draggingMarker.frame()) state.submit("move-marker", "", "", draggingMarker.frame(), dragFrame, draggingMarker.label(), "linear", 0, 0, 0, 0); cancelGesture(); return true; }
        if (scrubbing) { state.finishScrub(dragFrame); cancelGesture(); return true; }
        if (dragging != null) {
            int delta = dragFrame - dragging.key.frame(); double valueDelta = draggingValue ? dragValue - Double.parseDouble(dragging.key.value()) : 0;
            if (delta != 0 || Math.abs(valueDelta) > 1e-12) {
                submitBatch(event.hasAltDown() ? "copy-keys" : "move-keys", EffectKeyframeGestures.moved(refs(gestureKeys), delta, dragging.target, dragging.property, valueDelta));
                selected.clear();
                for (var ref : gestureKeys) {
                    var key = ref.key; String value = key.value();
                    if (valueDelta != 0 && ref.target.equals(dragging.target) && ref.property.equals(dragging.property)) value = Double.toString(Double.parseDouble(value) + valueDelta);
                    selected.add(new KeyRef(ref.target, ref.property, new EffectProtocol.Key(key.frame() + delta, value, key.interpolation(), key.x1(), key.y1(), key.x2(), key.y2())));
                }
                selectionAnchor = selected.stream().findFirst().orElse(null);
            }
            cancelGesture(); return true;
        }
        if (draggingCue != null) {
            var cue = draggingCue; if (dragFrame != cue.frame()) state.submit("cue", cue.id(), cue.kind(), dragFrame, cue.end() < 0 ? -1 : cue.end() + dragFrame - cue.frame(), cue.source(), cue.enabled() ? "linear" : "muted", cue.volume(), cue.pitch(), 0, 0);
            cancelGesture(); return true;
        }
        return false;
    }
    boolean scroll(double x, double y, double amount, boolean control, boolean shift) {
        if (!active() || panel == null || !panel.contains(x, y)) return false;
        if (gestureActive()) return true;
        if (control) zoomAt(x, Math.pow(1.25, amount));
        else if (shift) offset = Math.max(0, Math.min(state.snapshot().duration() - range(), offset - amount * range() / 10));
        else rowScroll = Math.max(0, rowScroll - (int) (amount * 3));
        return true;
    }
    private void zoomAt(double x, double factor) {
        double unit = (x - gridX) / gridWidth, frame = offset + unit * range();
        zoom = Math.max(1, Math.min(state.snapshot().duration(), zoom * factor)); offset = Math.max(0, Math.min(state.snapshot().duration() - range(), frame - range() * unit));
    }
    boolean key(KeyEvent event) {
        if (!active()) return false;
        if (searchFocused()) {
            if (event.isEscape() || event.isConfirmation()) { blurSearch(); return true; }
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && gestureActive()) { cancelGesture(); return true; }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && !selected.isEmpty()) return command("deselect-keys");
        if (event.key() == GLFW.GLFW_KEY_F3 && event.hasShiftDown()) return command("graph");
        if (event.key() == GLFW.GLFW_KEY_F2 && event.hasShiftDown()) return command("deselect-keys");
        if (event.hasAltDown() && event.hasShiftDown() && !event.hasControlDown()) {
            String toggle = switch (event.key()) { case GLFW.GLFW_KEY_P -> "toggle-position-key"; case GLFW.GLFW_KEY_S -> "toggle-scale-key"; case GLFW.GLFW_KEY_R -> "toggle-rotation-key"; case GLFW.GLFW_KEY_T -> "toggle-opacity-key"; default -> null; };
            if (toggle != null) return command(toggle);
        }
        if (event.hasControlDown()) {
            if (event.hasAltDown() && event.key() == GLFW.GLFW_KEY_LEFT) return command("first-frame");
            if (event.hasAltDown() && event.key() == GLFW.GLFW_KEY_RIGHT) return command("last-frame");
            if (event.hasAltDown() && event.key() == GLFW.GLFW_KEY_R) return command("reverse-keys");
            if (event.key() == GLFW.GLFW_KEY_D && event.hasShiftDown()) { screen.timelineCommand("split"); return true; }
            if (event.key() == GLFW.GLFW_KEY_Z) return command(event.hasShiftDown() ? "redo" : "undo");
            if (event.key() == GLFW.GLFW_KEY_S) return command("save");
            if (event.key() == GLFW.GLFW_KEY_A) return command(event.hasShiftDown() ? "deselect-keys" : "select-all-keys");
            if (event.key() == GLFW.GLFW_KEY_C && !selected.isEmpty()) return command("copy-keys");
            if (event.key() == GLFW.GLFW_KEY_X && !selected.isEmpty()) return command("cut-keys");
            if (event.key() == GLFW.GLFW_KEY_V && !clipboard.isEmpty()) return command("paste-keys");
        }
        if (event.key() == GLFW.GLFW_KEY_F9) return command(event.hasControlDown() && event.hasShiftDown() ? "ease-out" : event.hasShiftDown() ? "ease-in" : "ease");
        if ((event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) && !selected.isEmpty()) return command("delete-keys");
        if (event.key() == GLFW.GLFW_KEY_PAGE_UP || event.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
            int delta = (event.key() == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * (event.hasShiftDown() ? 10 : 1);
            if (event.hasAltDown()) return moveSelectedLayers(delta); step(delta); return true;
        }
        if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) { step((event.key() == GLFW.GLFW_KEY_LEFT ? -1 : 1) * (event.hasShiftDown() ? 10 : 1)); return true; }
        if (event.key() == GLFW.GLFW_KEY_HOME) return command(event.hasAltDown() ? "align-layer-comp-start" : event.hasControlDown() ? "work-first" : "first-frame");
        if (event.key() == GLFW.GLFW_KEY_END) return command(event.hasAltDown() ? "align-layer-comp-end" : event.hasControlDown() ? "work-last" : "last-frame");
        if (event.key() == GLFW.GLFW_KEY_LEFT_BRACKET || event.key() == GLFW.GLFW_KEY_RIGHT_BRACKET) {
            boolean start = event.key() == GLFW.GLFW_KEY_LEFT_BRACKET; if (event.hasAltDown()) trimSelected(start); else alignSelected(start); return true;
        }
        if (!event.hasControlDown() && !event.hasAltDown()) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_KP_0 -> { return command("play"); }
                case GLFW.GLFW_KEY_G -> { return command("graph"); }
                case GLFW.GLFW_KEY_J -> { return command("previous-key"); }
                case GLFW.GLFW_KEY_K -> { return command("next-key"); }
                case GLFW.GLFW_KEY_B -> { return command("work-start"); }
                case GLFW.GLFW_KEY_N -> { return command("work-end"); }
                case GLFW.GLFW_KEY_I -> { return command("layer-in"); }
                case GLFW.GLFW_KEY_O -> { return command("layer-out"); }
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> { return command("zoom-out"); }
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> { return command("zoom-in"); }
                case GLFW.GLFW_KEY_SEMICOLON -> { return command(event.hasShiftDown() ? "fit-selection" : "fit-graph"); }
                default -> {}
            }
            String next = switch (event.key()) { case GLFW.GLFW_KEY_P -> "offset"; case GLFW.GLFW_KEY_S -> "scale"; case GLFW.GLFW_KEY_R -> "rotation"; case GLFW.GLFW_KEY_T -> "opacity"; case GLFW.GLFW_KEY_U -> ""; default -> null; };
            if (next != null) { reveal(next, event.hasShiftDown()); return true; }
        }
        return false;
    }
    void reveal(String property, boolean additive) {
        if (!additive) propertyFilters.clear();
        if (property.isEmpty()) { propertyFilters.clear(); animatedOnly = true; }
        else { animatedOnly = false; if (additive && propertyFilters.contains(property)) propertyFilters.remove(property); else propertyFilters.add(property); }
        for (String id : scene.selectedIds()) collapsed.remove(id); rowScroll = 0;
    }
    private void step(int delta) { seekVisible(Math.max(0, Math.min(state.snapshot().duration(), (int) Math.floor(state.playhead()) + delta))); }
    private void seekVisible(int frame) {
        state.seek(frame); if (frame < offset) offset = frame; else if (frame > offset + range()) offset = Math.max(0, Math.min(state.snapshot().duration() - range(), frame - range()));
    }
    private List<EffectKeyframeGestures.Ref> refs(Collection<KeyRef> keys) { return keys.stream().filter(ref -> !locked(ref.target)).map(ref -> new EffectKeyframeGestures.Ref(ref.target, ref.property, ref.key)).toList(); }
    private void submitBatch(String action, JsonObject payload) { if (!payload.getAsJsonArray("keys").isEmpty()) state.submit(action, "", "", 0, 0, payload.toString(), "linear", 0, 0, 0, 0); }
    private void workRange(int first, int last) {
        var p = state.snapshot(); first = Math.max(0, Math.min(p.duration() - 1, first)); last = Math.max(first + 1, Math.min(p.duration(), last));
        if (first != p.workStart() || last != p.workEnd()) state.submit("settings", "", Boolean.toString(p.loop()), first, p.duration(), Integer.toString(last), "linear", p.fps(), 0, 0, 0);
    }
    private void setLayer(EffectProtocol.Layer layer, int first, int last, boolean enabled, boolean solo) {
        state.submit("layer", layer.target(), Boolean.toString(solo), first, last, Boolean.toString(enabled), "linear", 0, 0, 0, 0);
    }
    private void trimSelected(boolean start) {
        int frame = (int) state.playhead();
        for (String id : scene.selectedIds()) if (!locked(id)) { var old = layer(id); setLayer(old, start ? Math.min(frame, old.end() - 1) : old.start(), start ? old.end() : Math.max(old.start() + 1, Math.min(state.snapshot().duration(), frame + 1)), old.enabled(), old.solo()); }
    }
    private void alignSelected(boolean start) {
        int frame = (int) state.playhead();
        for (String id : selectedLayerRoots()) { var old = layer(id); int delta = frame - (start ? old.start() : old.end() - 1); if (delta != 0 && layerMoveAllowed(Set.of(id), delta)) state.submit("move-layer", id, "", delta, 0, "", "linear", 0, 0, 0, 0); }
    }
    private Set<String> selectedLayerRoots() {
        var roots = new LinkedHashSet<>(scene.selectedIds()); roots.removeIf(this::locked);
        var nested = new HashSet<String>();
        for (String id : roots) {
            var node = scene.find(id); var visited = new HashSet<String>();
            while (node != null && !node.parentId().isEmpty() && visited.add(node.parentId())) {
                if (roots.contains(node.parentId())) { nested.add(id); break; }
                node = scene.find(node.parentId());
            }
        }
        roots.removeAll(nested); return roots;
    }
    private Set<String> layerSubtree(Set<String> roots) {
        var result = new HashSet<>(roots);
        if (scene.snapshot() != null) for (var node : scene.snapshot().frontend()) {
            var current = node; var visited = new HashSet<String>();
            while (current != null && visited.add(current.id())) {
                if (roots.contains(current.id())) { result.add(node.id()); break; }
                current = scene.find(current.parentId());
            }
        }
        return result;
    }
    private boolean moveSelectedLayers(int delta) {
        var roots = selectedLayerRoots(); if (roots.isEmpty() || delta == 0) return false;
        if (!layerMoveAllowed(roots, delta)) return false;
        var payload = new JsonObject(); var targets = new JsonArray(); roots.forEach(targets::add); payload.add("targets", targets);
        state.submit("move-layers", "", "", delta, 0, payload.toString(), "linear", 0, 0, 0, 0); return true;
    }
    private boolean layerMoveAllowed(Set<String> roots, int delta) {
        var subtree = layerSubtree(roots); int minimum = Integer.MIN_VALUE, maximum = Integer.MAX_VALUE;
        for (String id : subtree) if (roots.contains(id) || state.snapshot().layers().stream().anyMatch(layer -> layer.target().equals(id))) {
            var old = layer(id); minimum = Math.max(minimum, -old.start()); maximum = Math.min(maximum, state.snapshot().duration() - old.end());
        }
        for (var track : state.snapshot().tracks()) if (subtree.contains(track.target())) for (var key : track.keys()) { minimum = Math.max(minimum, -key.frame()); maximum = Math.min(maximum, state.snapshot().duration() - key.frame()); }
        return delta >= minimum && delta <= maximum;
    }
    private void togglePropertyGroup(String prefix) {
        var properties = new ArrayList<EffectKeyframeGestures.Ref>(); int frame = (int) state.playhead();
        for (String id : scene.selectedIds()) {
            var node = scene.find(id); if (node == null || locked(id)) continue;
            for (var property : node.properties()) if (property.key().equals(prefix) || property.key().startsWith(prefix + "."))
                properties.add(new EffectKeyframeGestures.Ref(id, property.key(), new EffectProtocol.Key(frame, property.value(), "linear", 1.0 / 3, 0, 2.0 / 3, 1)));
        }
        submitBatch("toggle-keys", EffectKeyframeGestures.toggled(properties, frame)); reveal(prefix, false);
    }
    private boolean reversible() {
        if (selected.size() < 2) return false;
        try { for (var ref : selected) if (locked(ref.target) || ref.key.interpolation().equals("hold") || !Double.isFinite(Double.parseDouble(ref.key.value()))) return false; }
        catch (NumberFormatException ignored) { return false; }
        return true;
    }
    private void selectRelated(String mode) {
        var original = List.copyOf(selected);
        for (var ref : original) state.snapshot().tracks().stream().filter(track -> same(track, ref)).findFirst().ifPresent(track -> track.keys().stream()
            .filter(key -> switch (mode) { case "select-equal-keys" -> key.value().equals(ref.key.value()); case "select-previous-keys" -> key.frame() < ref.key.frame(); default -> key.frame() > ref.key.frame(); })
            .forEach(key -> selected.add(new KeyRef(ref.target, ref.property, key))));
    }
    boolean command(String action) {
        if (!active()) return false;
        switch (action) {
            case "copy-keys" -> { if (selected.isEmpty()) return false; clipboard = List.copyOf(selected); }
            case "cut-keys" -> { if (selected.isEmpty()) return false; clipboard = List.copyOf(selected); command("delete-keys"); }
            case "paste-keys" -> {
                if (clipboard.isEmpty()) return false;
                var available = clipboard.stream().filter(ref -> scene.find(ref.target) != null && !locked(ref.target)).toList();
                int first = available.stream().mapToInt(ref -> ref.key.frame()).min().orElse(0), last = available.stream().mapToInt(ref -> ref.key.frame()).max().orElse(0);
                if (last - first > state.snapshot().duration()) return false;
                submitBatch("set-keys", EffectKeyframeGestures.pasted(refs(available), (int) state.playhead(), state.snapshot().duration()));
            }
            case "delete-keys" -> { if (selected.isEmpty()) return false; submitBatch(action, EffectKeyframeGestures.references(refs(selected))); selected.clear(); }
            case "select-all-keys" -> {
                selected.clear(); var targetIds = scene.selectedIds();
                for (var track : state.snapshot().tracks()) if (targetIds.isEmpty() || targetIds.contains(track.target())) for (var key : track.keys()) selected.add(new KeyRef(track.target(), track.property(), key));
                selectionAnchor = selected.stream().findFirst().orElse(null);
            }
            case "deselect-keys" -> { selected.clear(); selectionAnchor = null; }
            case "select-equal-keys", "select-previous-keys", "select-following-keys" -> selectRelated(action);
            case "ease", "ease-in", "ease-out" -> {
                if (!easingAvailable()) return false;
                submitBatch("move-keys", EffectKeyframeGestures.eased(refs(selected), state.snapshot().tracks(), action));
            }
            case "linear", "hold", "bezier", "auto-bezier" -> {
                if (selected.isEmpty()) return false;
                var payload = EffectKeyframeGestures.references(refs(selected)); payload.addProperty("interpolation", action);
                payload.addProperty("x1", 1.0 / 3); payload.addProperty("y1", 0); payload.addProperty("x2", 2.0 / 3); payload.addProperty("y2", 1); submitBatch("interpolation-keys", payload);
            }
            case "reverse-keys" -> {
                if (!reversible()) return false;
                submitBatch("move-keys", EffectKeyframeGestures.reversed(refs(selected))); selected.clear();
            }
            case "previous-key", "next-key" -> {
                double current = state.playhead(); var frames = new TreeSet<Integer>();
                for (var track : state.snapshot().tracks()) if (scene.selectedIds().isEmpty() || scene.selected(track.target())) track.keys().forEach(key -> frames.add(key.frame()));
                state.snapshot().markers().forEach(marker -> frames.add(marker.frame()));
                Integer frame = action.equals("previous-key") ? frames.lower((int) Math.ceil(current)) : frames.higher((int) Math.floor(current)); if (frame != null) seekVisible(frame);
            }
            case "first-frame" -> seekVisible(0);
            case "last-frame" -> seekVisible(Math.max(0, state.snapshot().duration() - 1));
            case "previous-frame" -> step(-1);
            case "next-frame" -> step(1);
            case "previous-ten-frames" -> step(-10);
            case "next-ten-frames" -> step(10);
            case "work-first" -> seekVisible(state.snapshot().workStart());
            case "work-last" -> seekVisible(state.snapshot().workEnd() - 1);
            case "work-start" -> workRange((int) state.playhead(), state.snapshot().workEnd());
            case "work-end" -> workRange(state.snapshot().workStart(), Math.min(state.snapshot().duration(), (int) state.playhead() + 1));
            case "layer-in", "layer-out" -> { var old = layer(scene.selectedId()); seekVisible(action.equals("layer-in") ? old.start() : old.end() - 1); }
            case "trim-start" -> trimSelected(true);
            case "trim-end" -> trimSelected(false);
            case "align-layer-start" -> alignSelected(true);
            case "align-layer-end" -> alignSelected(false);
            case "align-layer-comp-start", "align-layer-comp-end" -> {
                var layers = selectedLayerRoots().stream().map(this::layer).toList(); if (layers.isEmpty()) return false;
                int delta = action.equals("align-layer-comp-start") ? -layers.stream().mapToInt(EffectProtocol.Layer::start).min().orElse(0) : state.snapshot().duration() - layers.stream().mapToInt(EffectProtocol.Layer::end).max().orElse(state.snapshot().duration());
                return moveSelectedLayers(delta);
            }
            case "move-layers-back" -> { return moveSelectedLayers(-1); }
            case "move-layers-forward" -> { return moveSelectedLayers(1); }
            case "move-layers-back-ten" -> { return moveSelectedLayers(-10); }
            case "move-layers-forward-ten" -> { return moveSelectedLayers(10); }
            case "toggle-position-key", "toggle-scale-key", "toggle-rotation-key", "toggle-opacity-key" -> togglePropertyGroup(switch (action) { case "toggle-position-key" -> "offset"; case "toggle-scale-key" -> "scale"; case "toggle-rotation-key" -> "rotation"; default -> "opacity"; });
            case "reveal-position", "reveal-scale", "reveal-rotation", "reveal-opacity", "reveal-animated" -> reveal(switch (action) { case "reveal-position" -> "offset"; case "reveal-scale" -> "scale"; case "reveal-rotation" -> "rotation"; case "reveal-opacity" -> "opacity"; default -> ""; }, false);
            case "add-position", "add-scale", "add-rotation", "add-opacity" -> reveal(switch (action) { case "add-position" -> "offset"; case "add-scale" -> "scale"; case "add-rotation" -> "rotation"; default -> "opacity"; }, true);
            case "zoom-in", "zoom-out" -> { if (!gestureActive()) zoomAt(gridX + gridWidth / 2.0, action.equals("zoom-in") ? 1.25 : .8); }
            case "fit-selection" -> {
                if (selected.isEmpty()) return false;
                int first = selected.stream().mapToInt(ref -> ref.key.frame()).min().orElse(0), last = selected.stream().mapToInt(ref -> ref.key.frame()).max().orElse(0);
                double span = Math.max(10, last - first), wanted = Math.min(state.snapshot().duration(), span * 1.25); zoom = state.snapshot().duration() / wanted; offset = Math.max(0, Math.min(state.snapshot().duration() - wanted, first - span * .125));
                if (graphMode && !speedGraph) {
                    var values = selected.stream().filter(ref -> ref.target.equals(graphTarget) && ref.property.equals(graphProperty)).mapToDouble(ref -> Double.parseDouble(ref.key.value())).summaryStatistics();
                    if (values.getCount() > 0) { double pad = Math.max(.1, (values.getMax() - values.getMin()) * .15); graphMin = values.getMin() - pad; graphMax = values.getMax() + pad; graphAutoZoom = false; }
                }
            }
            case "expand-all" -> { collapsed.clear(); propertyFilters.clear(); animatedOnly = false; }
            case "collapse-all" -> { if (scene.snapshot() != null) scene.snapshot().frontend().forEach(node -> collapsed.add(node.id())); }
            case "key", "cue", "marker", "graph", "value-graph", "speed-graph", "fit-graph", "snapping", "settings", "auto-key", "play", "stop", "save", "undo", "redo" -> control(action);
            default -> { return false; }
        }
        return true;
    }
    static Map<String, String> shortcuts() {
        var bindings = new LinkedHashMap<String, String>();
        bindings.put("split", "Ctrl+Shift+D"); bindings.put("play", "Space / Num 0"); bindings.put("previous-key", "J"); bindings.put("next-key", "K");
        bindings.put("first-frame", "Home / Ctrl+Alt+Left"); bindings.put("last-frame", "End / Ctrl+Alt+Right"); bindings.put("previous-frame", "Page Up / Left"); bindings.put("next-frame", "Page Down / Right");
        bindings.put("previous-ten-frames", "Shift+Page Up / Shift+Left"); bindings.put("next-ten-frames", "Shift+Page Down / Shift+Right");
        bindings.put("work-start", "B"); bindings.put("work-end", "N"); bindings.put("layer-in", "I"); bindings.put("layer-out", "O");
        bindings.put("align-layer-start", "["); bindings.put("align-layer-end", "]"); bindings.put("trim-start", "Alt+["); bindings.put("trim-end", "Alt+]");
        bindings.put("reveal-position", "P"); bindings.put("reveal-scale", "S"); bindings.put("reveal-rotation", "R"); bindings.put("reveal-opacity", "T");
        bindings.put("reveal-animated", "U"); bindings.put("add-position", "Shift+P"); bindings.put("add-scale", "Shift+S"); bindings.put("add-rotation", "Shift+R"); bindings.put("add-opacity", "Shift+T");
        bindings.put("ease", "F9"); bindings.put("ease-in", "Shift+F9"); bindings.put("ease-out", "Ctrl+Shift+F9");
        bindings.put("copy-keys", "Ctrl+C"); bindings.put("cut-keys", "Ctrl+X"); bindings.put("paste-keys", "Ctrl+V"); bindings.put("delete-keys", "Delete / Backspace");
        bindings.put("select-all-keys", "Ctrl+A"); bindings.put("deselect-keys", "Ctrl+Shift+A / Shift+F2"); bindings.put("graph", "G / Shift+F3");
        bindings.put("toggle-position-key", "Alt+Shift+P"); bindings.put("toggle-scale-key", "Alt+Shift+S"); bindings.put("toggle-rotation-key", "Alt+Shift+R"); bindings.put("toggle-opacity-key", "Alt+Shift+T");
        bindings.put("move-layers-back", "Alt+Page Up"); bindings.put("move-layers-forward", "Alt+Page Down"); bindings.put("move-layers-back-ten", "Alt+Shift+Page Up"); bindings.put("move-layers-forward-ten", "Alt+Shift+Page Down");
        bindings.put("align-layer-comp-start", "Alt+Home"); bindings.put("align-layer-comp-end", "Alt+End"); bindings.put("reverse-keys", "Ctrl+Alt+R");
        bindings.put("zoom-in", "+"); bindings.put("zoom-out", "-"); bindings.put("fit-graph", ";"); bindings.put("fit-selection", "Shift+;");
        return Collections.unmodifiableMap(bindings);
    }
    private void control(String action) {
        switch (action) {
            case "key" -> screen.effectDialog("key", scene.selectedId(), graphProperty, (int) state.playhead(), null, null);
            case "cue" -> screen.effectDialog("cue", "", "", (int) state.playhead(), null, null);
            case "marker" -> screen.effectDialog("marker", "", "", (int) state.playhead(), null, null);
            case "graph" -> graphVisible(!graphMode);
            case "value-graph" -> graphMode(false);
            case "speed-graph" -> graphMode(true);
            case "fit-graph" -> { cancelGesture(); zoom = 1; offset = 0; graphAutoZoom = true; }
            case "snapping" -> snapping = !snapping;
            case "settings" -> screen.effectDialog("settings", "", "", 0, null, null);
            case "auto-key" -> state.submit("auto-key", "", "", 0, 0, Boolean.toString(!state.snapshot().autoKey()), "linear", 0, 0, 0, 0);
            default -> state.action(action);
        }
    }
}

package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol;
import com.fentai.arcmenu.editor.mcp.McpScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Properties;

/** Dedicated composition workspace. Ordinary menu editor state and preferences remain independent. */
public final class EffectEditorScreen extends Screen implements EffectTimeline.Host {
    private final EditorState scene;
    private final EffectState effect = ArcMenuEditorClient.EFFECT;
    private final EffectTimeline timeline;
    private final EffectWorkspaceLayout geometry = new EffectWorkspaceLayout();
    private final ElementSelectionCalibration calibration = new ElementSelectionCalibration();
    private EffectWorkspaceLayout.Layout panels;
    private final EffectPopup popup = new EffectPopup();
    private final ArrayDeque<Runnable> deferredUI=new ArrayDeque<>();
    private final ArrayDeque<DeferredInput> deferredPointerEvents = new ArrayDeque<>();
    private boolean deferredPointer;
    private record DeferredInput(int type, MouseButtonEvent event, boolean doubleClick, double dx, double dy) {}
    private double viewZoom=1, viewPanX, viewPanY, lastMouseX, lastMouseY;
    private int syntheticModifiers=-1;
    private boolean panning, safeGuides, marquee, drawShape;
    private double marqueeX, marqueeY, marqueeEndX, marqueeEndY;
    private String projectAnchor="", draggedAsset="", rotationTarget="";
    private String propertyHint="";
    private double rotationOrigin,rotationBase,rotationValue;
    private Field scrubField;
    private double scrubStartX, scrubValue;
    private final List<Control> controls = new ArrayList<>();
    private final List<Field> fields = new ArrayList<>();
    private final List<ProjectEntry> entries = new ArrayList<>();
    private String projectTab = "project", openedMenu = "", menuTarget = "", tool = "select";
    private int projectScroll, propertyScroll, divider;
    private boolean showGuides = true, timelineFocused, fitView;
    private List<String> copied = List.of();
    private String copiedProject = "", projectQuery = "", hierarchySelection = "";
    private EditBox projectSearch;
    private String dragging = "";
    private boolean resizing, released, awaiting, finalSent;
    private long nextGesture = 1, gesture, lastSend;
    private double grabX, grabY;
    private EditorProtocol.Pointer pending, sent;
    private record Control(EditorLayout.Rect rect, String label, String hint, Runnable action, boolean active) {}
    private record Field(String target, EditorProtocol.PropertySnapshot property, EditBox box, int y) {}
    private record ProjectEntry(String id, String kind, EditorLayout.Rect rect, Runnable action) {}

    public EffectEditorScreen(EditorState state) {
        super(EditorI18n.component("arcmenu_editor.workspace.title"));
        scene = state; timeline = new EffectTimeline(this, state);
        calibration.load(ArcMenuEditorClient.selectionCalibrationFile());
        loadWorkspace();
    }
    @Override protected void init() { rebuildWidgets(); }
    @Override protected void rebuildWidgets() {
        boolean restoreTimelineSearch=timeline.searchFocused();
        clearWidgets(); fields.clear();
        panels = geometry.calculate(width, height); EditorViewport.update(panels.viewport());
        var projectPanel = panels.project();
        projectSearch = new EditBox(font, projectPanel.x()+12, projectPanel.y()+81, projectPanel.width()-24, 12, EffectUi.component(EffectUi.label("search_project")));
        projectSearch.setBordered(false); projectSearch.setTextColor(EffectUi.TEXT); projectSearch.setMaxLength(64);
        projectSearch.addFormatter((value,position)->EffectUi.component(value).getVisualOrderText());
        projectSearch.setValue(projectQuery); projectSearch.setResponder(value->{projectQuery=value;projectScroll=0;});
        addRenderableWidget(projectSearch);
        addRenderableWidget(timeline.rebuildSearch(font,panels.timeline(),restoreTimelineSearch));
        var node = scene.find(scene.selectedId());
        if (node == null) return;
        var p = panels.properties(); int index = 0;
        for (var property : node.properties()) {
            int y = p.y() + 56 + index++ * 21 - propertyScroll;
            if (y < p.y() + 52 || y + 19 > p.bottom() - 16) continue;
            if (property.type() == EditorProtocol.PROPERTY_BOOLEAN || property.type() == EditorProtocol.PROPERTY_CHOICE) {
                fields.add(new Field(node.id(), property, null, y)); continue;
            }
            int labelWidth = Math.min(84, p.width() * 43 / 100);
            EditBox box = new EditBox(font, p.x() + labelWidth + 13, y + 5,
                    Math.max(16, p.width() - labelWidth - 24 - (property.type()==EditorProtocol.PROPERTY_COLOR || property.key().equals("source") && EditorImageTypes.imageKind(node.kind())?14:0)), 12, EffectUi.component(property.key()));
            box.setMaxLength(EditorProtocol.MAX_STRING_BYTES / 2);
            box.setValue(display(property)); box.setBordered(false); box.setTextColor(EffectUi.ACCENT);
            box.setTextShadow(false); box.addFormatter((value, position) -> EffectUi.component(value).getVisualOrderText());
            addRenderableWidget(box); fields.add(new Field(node.id(), property, box, y));
        }
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float partial) {}
    @Override public void removed() { saveWorkspace(); EditorViewport.clear(); super.removed(); }
    @Override public void onClose() { EffectAudioPreview.INSTANCE.stop(); EditorViewport.clear(); ArcMenuEditorClient.send(new EditorProtocol.ClosePacket()); super.onClose(); }
    public void serverStateChanged() {
        boolean projectFocused = projectSearch != null && projectSearch.isFocused();
        int projectCursor = projectFocused ? projectSearch.getCursorPosition() : 0;
        Field focused = fields.stream().filter(f -> f.box != null && f.box.isFocused()).findFirst().orElse(null);
        String value = focused == null ? "" : focused.box.getValue();
        int cursor = focused == null ? 0 : focused.box.getCursorPosition();
        rebuildWidgets();
        if(projectFocused) { setFocused(projectSearch); projectSearch.setCursorPosition(projectCursor); projectSearch.setHighlightPos(projectCursor); }
        if (focused != null && focused.target.equals(scene.selectedId())) fields.stream()
                .filter(f -> f.box != null && f.property.key().equals(focused.property.key())).findFirst().ifPresent(f -> {
                    f.box.setValue(value); setFocused(f.box); f.box.setCursorPosition(cursor); f.box.setHighlightPos(cursor);
                });
        drainDeferredUI();
    }
    public void serverOperationCompleted(byte operation, long id) {
        if ((operation == EditorProtocol.OP_MOVE || operation == EditorProtocol.OP_RESIZE) && id == gesture && awaiting) {
            awaiting = false;
            if (finalSent) finishDrag(); else if (released || !Objects.equals(pending, sent)) flushDrag();
        }
        serverStateChanged();
        drainDeferredUI();
    }
    private void drainDeferredUI() { while (!effect.busy() && !deferredUI.isEmpty()) deferredUI.removeFirst().run(); }
    public void serverOperationFailed() { finishDrag(); deferredUI.clear(); deferredPointerEvents.clear(); deferredPointer = false; serverStateChanged(); }
    @Override public void tick() { super.tick(); if (gesture != 0 && !awaiting && (released || !Objects.equals(pending, sent))) flushDrag(); }
    public boolean fitView() { return fitView; }
    public double viewZoom() {return viewZoom;}
    public double viewPanX() {return viewPanX;}
    public double viewPanY() {return viewPanY;}
    public void graphView(String mode) { timeline.graphVisible(!mode.equals("timeline")); if (!mode.equals("timeline")) timeline.graphMode(mode.equals("speed-graph")); }
    @Override public void effectDialog(String mode, String target, String property, int frame, EffectProtocol.Key key, EffectProtocol.Cue cue) {
        openedMenu = ""; minecraft.setScreen(new EffectDialog(this, scene, mode, target, property, frame, key, cue));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        EffectAudioPreview.INSTANCE.update(true);
        panels = geometry.calculate(width, height); EditorViewport.update(panels.viewport()); controls.clear(); entries.clear();
        propertyHint="";
        mask(g); header(g, mx, my); project(g, mx, my); composition(g, mx, my); preview(g, mx, my); properties(g, mx, my);
        timeline.draw(g, font, panels.timeline(), mx, my);
        super.extractRenderState(g, mx, my, partial);
        menu(g, mx, my);
        popup.draw(g,font,width,height,mx,my);
        lastMouseX=mx;lastMouseY=my;
        if (openedMenu.isEmpty() && !popup.active()) controls.stream().filter(c -> c.rect.contains(mx, my) && !c.hint.isEmpty()).findFirst().ifPresent(c -> {
            EditorTooltips.draw(g,font,EffectUi.component(c.hint),mx+8,my+13,width,height,0xF0171717,EffectUi.BORDER,EffectUi.TEXT);
        });
        if (openedMenu.isEmpty() && !popup.active() && !propertyHint.isEmpty())
            EditorTooltips.draw(g,font,EffectUi.component(propertyHint),mx+8,my+13,width,height,0xF0171717,EffectUi.BORDER,EffectUi.TEXT);
    }
    private void mask(GuiGraphicsExtractor g) {
        var v = panels.viewport();
        g.fill(0, 0, width, v.y(), EffectUi.BACKGROUND); g.fill(0, v.bottom(), width, height, EffectUi.BACKGROUND);
        g.fill(0, v.y(), v.x(), v.bottom(), EffectUi.BACKGROUND); g.fill(v.right(), v.y(), width, v.bottom(), EffectUi.BACKGROUND);
        EffectUi.panel(g, panels.project()); EffectUi.panel(g, panels.preview()); EffectUi.panel(g, panels.properties());
        var c = panels.composition();
        g.fill(c.x(), c.y(), c.right(), v.y(), EffectUi.PANEL); g.fill(c.x(), v.bottom(), c.right(), c.bottom(), EffectUi.PANEL);
        g.fill(c.x(), v.y(), v.x(), v.bottom(), EffectUi.PANEL); g.fill(v.right(), v.y(), c.right(), v.bottom(), EffectUi.PANEL);
        g.outline(c.x(), c.y(), c.width(), c.height(), EffectUi.BORDER);
    }
    private void header(GuiGraphicsExtractor g, int mx, int my) {
        g.fill(0, 0, width, 17, EffectUi.HEADER); int x = 7;
        for (String name : List.of("file", "edit", "composition", "layer", "animation", "view", "window", "help")) {
            String label = EffectUi.label(name); int w = EffectUi.width(font, label) + 13;
            button(g, new EditorLayout.Rect(x, 1, w, 15), label, "", () -> {popup.close();openedMenu = openedMenu.equals(name) ? "" : name;}, openedMenu.equals(name), mx, my); x += w;
        }
        String id = effect.active() ? effect.snapshot().id() : "";
        String title = "ArcMenu Effect  /  " + id + (effect.active() && effect.snapshot().dirty() ? " *" : "");
        if (width - x > 120) EffectUi.text(g, font, EffectUi.fit(font, title, width - x - 10), x + 8, 5, EffectUi.MUTED);
        g.fill(0, 17, width, 38, EffectUi.PANEL); g.fill(0, 38, width, 39, EffectUi.BORDER);
        int tx = 8;
        String[] actions = {"select", "rectangle", "frame", "text", "item", "block", "image", "gif", "line", "group"};
        byte[] kinds = {-1, 1, 2, 3, 4, 5, 7, 9, 8, 0};
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i]; byte kind = kinds[i];
            button(g, new EditorLayout.Rect(tx, 20, 20, 16), "", kind < 0 ? EffectUi.label("select") : EditorI18n.text("arcmenu_editor.tool." + toolKey(kind)),
                    () -> { tool = action; if (kind >= 0 && kind != 1 && kind != 2 && kind != 3 && kind != 8) create(kind, ""); }, tool.equals(action), mx, my); EffectToolIcons.draw(g,action,tx+4,22,tool.equals(action)?EffectUi.ACCENT:EffectUi.TEXT); tx += 24;
        }
        g.fill(tx + 2, 22, tx + 3, 34, EffectUi.BORDER);
        button(g, new EditorLayout.Rect(tx + 9, 20, 20, 16), "", EditorI18n.text("arcmenu_editor.effect.undo"), () -> workspaceCommand("undo"), false, mx, my);
        button(g, new EditorLayout.Rect(tx + 32, 20, 20, 16), "", EditorI18n.text("arcmenu_editor.effect.redo"), () -> workspaceCommand("redo"), false, mx, my);
        EffectToolIcons.draw(g,"undo",tx+13,22,EffectUi.TEXT); EffectToolIcons.draw(g,"redo",tx+36,22,EffectUi.TEXT);
        button(g, new EditorLayout.Rect(width - 64, 20, 56, 16), "MCP", EditorI18n.text("arcmenu_editor.mcp.title"), () -> minecraft.setScreen(new McpScreen(this)), false, mx, my);
        button(g, new EditorLayout.Rect(width - 180, 20, 54, 16), EditorI18n.text("arcmenu_editor.projects.projects"), "", () -> WorkspaceClient.open(this, false), false, mx, my);
        button(g, new EditorLayout.Rect(width - 122, 20, 54, 16), EditorI18n.text("arcmenu_editor.projects.market"), "", () -> WorkspaceClient.open(this, true), false, mx, my);
    }
    private void project(GuiGraphicsExtractor g, int mx, int my) {
        var p = panels.project(); int x = p.x() + 8;
        for (String tab : List.of("project", "assets")) {
            String label = EffectUi.label(tab); int w = EffectUi.width(font, label) + 12;
            EffectUi.tab(g, font, label, x, p.y() + 6, projectTab.equals(tab));
            control(new EditorLayout.Rect(x - 3, p.y() + 2, w, 18), label, "", () -> { projectTab = tab; projectScroll = 0; }, false); x += w;
        }
        g.fill(p.x(), p.y() + 21, p.right(), p.y() + 22, EffectUi.BORDER);
        var s = scene.snapshot(); var e = effect.snapshot(); if (s == null || e == null) return;
        if(!hierarchySelection.equals(scene.selectedId())) {
            hierarchySelection=scene.selectedId(); var selected=scene.find(hierarchySelection); Set<String> visited=new HashSet<>();
            while(selected!=null&&!selected.parentId().isEmpty()&&visited.add(selected.parentId())) {scene.expanded().add(selected.parentId());selected=scene.find(selected.parentId());}
        }
        int y = p.y() + 30;
        EffectUi.text(g, font, EffectUi.fit(font, e.id(), p.width() - 18), p.x() + 9, y, EffectUi.TEXT);
        EffectUi.mono(g, font, String.format(Locale.ROOT, "%.0f × %.0f", s.canvasWidth(), s.canvasHeight()), p.x() + 9, y + 16, EffectUi.MUTED);
        EffectUi.mono(g, font, String.format(Locale.ROOT, "%.2f fps / %s", e.fps(), timecode(e.duration())), p.x() + 9, y + 30, EffectUi.MUTED);
        g.fill(p.x()+6,p.y()+77,p.right()-6,p.y()+96,0xFF171717);
        g.outline(p.x()+6,p.y()+77,p.width()-12,19,projectSearch!=null&&projectSearch.isFocused()?EffectUi.ACCENT:EffectUi.BORDER);
        if(projectQuery.isEmpty()&&(projectSearch==null||!projectSearch.isFocused())) EffectUi.text(g,font,EffectUi.label("search_project"),p.x()+12,p.y()+82,EffectUi.MUTED);
        EffectUi.text(g, font, EffectUi.label("name"), p.x() + 8, y + 75, EffectUi.MUTED);
        List<ProjectEntry> items = new ArrayList<>();
        if (projectTab.equals("project")) {
            items.add(new ProjectEntry(e.id(), "composition", null, () -> { scene.clearSelection(); rebuildWidgets(); }));
            for (var node : s.frontend()) if (!projectQuery.isEmpty() || !projectHidden(node)) items.add(new ProjectEntry(node.id(), "layer", null, () -> select(node.id())));
            for (var template : s.templates()) items.add(new ProjectEntry(template.id(), "template", null, () -> ArcMenuEditorClient.send(new EditorProtocol.InstantiateTemplatePacket(s.revision(), template.id()))));
        } else {
            for (String asset : e.assets()) items.add(new ProjectEntry(asset, asset.endsWith(".nbs") ? "nbs" : "audio", null, () -> effectDialog("cue", "", "", (int) effect.playhead(), null,
                    new EffectProtocol.Cue("cue-" + e.cues().size(), (int) effect.playhead(), -1, asset.endsWith(".nbs") ? "nbs" : "sound", asset, 1, 1, true))));
            for (var image : s.images()) items.add(new ProjectEntry(image.path(), EditorImageTypes.gif(image.path()) ? "gif" : "image", null, () -> create(EditorImageTypes.kind(image.path()), image.path())));
        }
        if(!projectQuery.isEmpty()) items.removeIf(item -> !item.id.toLowerCase(Locale.ROOT).contains(projectQuery.toLowerCase(Locale.ROOT)));
        int start = y + 91, visible = Math.max(1, (p.bottom() - start - 21) / 19);
        projectScroll = Math.min(projectScroll, Math.max(0, items.size() - visible));
        for (int i = projectScroll; i < Math.min(items.size(), projectScroll + visible); i++) {
            var item = items.get(i); int rowY = start + (i - projectScroll) * 19;
            var rect = new EditorLayout.Rect(p.x() + 3, rowY, p.width() - 6, 18);
            if (scene.selected(item.id) || rect.contains(mx, my)) g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), scene.selected(item.id) ? 0xFF343C49 : 0xFF303030);
            int color = switch (item.kind) { case "audio", "nbs" -> 0xFF6EAF8D; case "composition" -> 0xFFAF86C7; default -> 0xFFCAA96B; };
            var node = item.kind.equals("layer") ? scene.find(item.id) : null; int depth = node == null ? 0 : depth(node), inset=Math.min(p.width()/3,depth*8);
            if(node!=null&&node.kind()==EditorProtocol.KIND_GROUP) {
                int ax=rect.x()+5+inset, ay=rowY+7; boolean expanded=scene.expanded().contains(node.id());
                if(expanded) {EffectUi.line(g,ax,ay,ax+3,ay+3,color);EffectUi.line(g,ax+3,ay+3,ax+6,ay,color);}
                else {EffectUi.line(g,ax+1,ay-2,ax+4,ay+1,color);EffectUi.line(g,ax+4,ay+1,ax+1,ay+4,color);}
            } else g.outline(rect.x()+5+inset,rowY+5,7,8,color);
            EffectUi.text(g, font, EffectUi.fit(font, item.id, p.width() - 30 - inset), p.x() + 20 + inset, rowY + 5, EffectUi.TEXT);
            entries.add(new ProjectEntry(item.id, item.kind, rect, item.action));
        }
        button(g, new EditorLayout.Rect(p.x() + 5, p.bottom() - 19, 19, 15), "+", EffectUi.label("add_layer"), () -> openedMenu = "layer", false, mx, my);
        button(g, new EditorLayout.Rect(p.x() + 28, p.bottom() - 19, 19, 15), "×", EffectUi.label("delete"), this::delete, false, mx, my);
        EffectUi.text(g, font, EffectUi.fit(font, projectTab.equals("assets") ? EffectUi.label("asset_hint") : EffectUi.label("project_hint"), p.width() - 57), p.x() + 53, p.bottom() - 15, EffectUi.MUTED);
    }
    private void composition(GuiGraphicsExtractor g, int mx, int my) {
        var p = panels.composition(); var v = panels.viewport();
        String id = effect.active() ? effect.snapshot().id() : "";
        EffectUi.tab(g, font, EffectUi.label("composition") + "  " + id, p.x() + 9, p.y() + 6, true);
        g.fill(p.x(), p.y() + 21, p.right(), p.y() + 22, EffectUi.BORDER);
        g.outline(v.x() - 1, v.y() - 1, v.width() + 2, v.height() + 2, 0xFF383838);
        if (showGuides && !EditorScreen.captureClean && scene.snapshot() != null) {
            g.enableScissor(v.x(), v.y(), v.right(), v.bottom());
            for (String idSelected : scene.selectedIds()) {
                var node = scene.find(idSelected); if (node == null) continue;
                var c = calibration.get(node.kind());
                int left = sx(c.proxyX(node.x(), node.width()) - c.proxyWidth(node.width()) / 2);
                int right = sx(c.proxyX(node.x(), node.width()) + c.proxyWidth(node.width()) / 2);
                int top = sy(c.proxyY(node.y(), node.height()) + c.proxyHeight(node.height()) / 2);
                int bottom = sy(c.proxyY(node.y(), node.height()) - c.proxyHeight(node.height()) / 2);
                g.outline(left, top, Math.max(1, right - left), Math.max(1, bottom - top), EffectUi.ACCENT);
                for (int[] point : new int[][]{{left,top},{right,top},{left,bottom},{right,bottom}}) g.fill(point[0]-2, point[1]-2, point[0]+3, point[1]+3, EffectUi.ACCENT);
                int cx = sx(node.x()), cy = sy(node.y()); EffectUi.line(g, cx-4, cy, cx+4, cy, EffectUi.ACCENT); EffectUi.line(g, cx, cy-4, cx, cy+4, EffectUi.ACCENT);
            }
            g.disableScissor();
        }
        if(safeGuides && !EditorScreen.captureClean) {g.outline(v.x()+v.width()/20,v.y()+v.height()/20,v.width()*9/10,v.height()*9/10,0x9970A5A0);g.outline(v.x()+v.width()/10,v.y()+v.height()/10,v.width()*4/5,v.height()*4/5,0x9970A5A0);}
        if(marquee || drawShape) {int lx=(int)Math.min(marqueeX,marqueeEndX),ty=(int)Math.min(marqueeY,marqueeEndY),rw=(int)Math.abs(marqueeEndX-marqueeX),rh=(int)Math.abs(marqueeEndY-marqueeY);g.fill(lx,ty,lx+rw,ty+rh,0x202D8CEB);g.outline(lx,ty,rw,rh,EffectUi.ACCENT);}
        if(!draggedAsset.isEmpty()) EffectUi.text(g,font,EffectUi.fit(font,draggedAsset,160),mx+8,my+8,EffectUi.ACCENT);
        int y = p.bottom() - 17;
        String viewLabel = fitView ? EditorI18n.text("arcmenu_editor.workspace.fit_zoom", previewZoom())
                : EffectUi.label("playback_view");
        int viewButtonWidth = Math.max(72, Math.min(120, EffectUi.width(font, viewLabel) + 12));
        button(g, new EditorLayout.Rect(p.x()+5,y,viewButtonWidth,15), viewLabel, EffectUi.label("fit_view"),
                () -> {fitView = !fitView;viewZoom=1;viewPanX=viewPanY=0;}, fitView, mx, my);
        EffectUi.mono(g, font, timecode((int) effect.playhead()), p.x() + viewButtonWidth + 14, y + 4, EffectUi.ACCENT);
        button(g, new EditorLayout.Rect(p.right() - 47, y, 18, 15), "#", EffectUi.label("guides"), () -> showGuides = !showGuides, showGuides, mx, my);
        button(g, new EditorLayout.Rect(p.right() - 25, y, 18, 15), "…", EditorI18n.text("arcmenu_editor.effect.settings"), () -> effectDialog("settings", "", "", 0, null, null), false, mx, my);
    }
    private void preview(GuiGraphicsExtractor g, int mx, int my) {
        var p = panels.preview(); title(g, p, "preview");
        var audio = EffectAudioPreview.INSTANCE; int ax = p.right() - 24, ay = p.y() + 3;
        button(g, new EditorLayout.Rect(ax, ay, 18, 14), "", EffectUi.label(audio.enabled() ? "audio_on" : "audio_off") + "  " + EffectUi.label("audio_hint"), () -> audio.enabled(!audio.enabled()), audio.enabled(), mx, my);
        int audioColor = audio.enabled() ? 0xFFFFFFFF : EffectUi.MUTED;
        g.fill(ax + 4, ay + 5, ax + 7, ay + 9, audioColor); g.fill(ax + 7, ay + 3, ax + 9, ay + 11, audioColor);
        EffectUi.line(g, ax + 11, ay + 4, ax + 13, ay + 7, audioColor); EffectUi.line(g, ax + 13, ay + 7, ax + 11, ay + 10, audioColor);
        if (!audio.enabled()) EffectUi.line(g, ax + 3, ay + 12, ax + 14, ay + 2, 0xFFD5A458);
        int y = p.y() + 29, x = p.x() + Math.max(6, (p.width() - 102) / 2);
        String[] glyphs = {"|◀", "◀", effect.active() && effect.snapshot().playing() ? "Ⅱ" : "▶", "▶|", "■"};
        Runnable[] actions = {() -> effect.seek(effect.snapshot().workStart()), () -> effect.seek(Math.max(0, (int) effect.playhead() - 1)),
                () -> effect.action("play"), () -> effect.seek(Math.min(effect.snapshot().duration(), (int) effect.playhead()+1)), () -> effect.action("stop")};
        for (int i = 0; i < glyphs.length; i++) button(g, new EditorLayout.Rect(x + i * 21, y, 19, 18), glyphs[i], "", actions[i], false, mx, my);
        if (!effect.active()) return; var e = effect.snapshot();
        EffectUi.text(g, font, EffectUi.label("shortcut") + "  " + EditorI18n.text("key.keyboard.space"), p.x() + 8, y + 29, EffectUi.MUTED);
        EffectUi.text(g, font, EffectUi.label("range") + "  " + e.workStart() + " – " + e.workEnd(), p.x() + 8, y + 45, EffectUi.TEXT);
        if (y + 81 < p.bottom()) {
            button(g, new EditorLayout.Rect(p.x() + 7, y + 64, p.width() - 14, 16), EffectUi.label(e.loop() ? "loop_on" : "loop_off"), "", () -> effect.submit("settings", "", Boolean.toString(!e.loop()), e.workStart(), e.duration(), Integer.toString(e.workEnd()), "linear", e.fps(), 0, 0, 0), e.loop(), mx, my);
        }
        String audioStatus = EffectAudioPreview.INSTANCE.statusKey();
        int audioStatusY = y + (y + 81 < p.bottom() ? 85 : 61);
        if (!audioStatus.isEmpty() && audioStatusY + 9 <= p.bottom()) EffectUi.text(g, font, EffectUi.fit(font, EffectUi.label(audioStatus), p.width() - 16), p.x() + 8, audioStatusY, audioStatus.equals("audio_missing") ? 0xFFD5A458 : EffectUi.MUTED);
    }
    private void properties(GuiGraphicsExtractor g, int mx, int my) {
        var p = panels.properties(); title(g, p, "properties");
        var node = scene.find(scene.selectedId());
        EffectUi.text(g, font, EffectUi.fit(font, node == null ? EffectUi.label("no_selection") : node.id(), p.width() - 18), p.x() + 8, p.y() + 29, EffectUi.TEXT);
        if (node == null) {
            if (scene.snapshot() != null) {
                EffectUi.text(g, font, EffectUi.label("composition"), p.x()+8, p.y()+56, EffectUi.MUTED);
                EffectUi.mono(g, font, timecode((int) effect.playhead()), p.x()+8, p.y()+74, EffectUi.ACCENT);
                EffectUi.text(g, font, EffectUi.label("select_hint"), p.x()+8, p.y()+97, EffectUi.MUTED);
            }
            return;
        }
        EffectUi.text(g, font, EditorI18n.text("arcmenu_editor.tool." + toolKey(node.kind())), p.x() + 8, p.y() + 43, EffectUi.MUTED);
        int labelWidth = Math.min(84, p.width() * 43 / 100);
        for (var field : fields) {
            var property = field.property;
            String label = EditorI18n.property(property.key());
            if (mx >= p.x() && mx < p.x()+labelWidth+8 && my >= field.y && my < field.y+21) {
                propertyHint=EditorImageTypes.hint(node.kind(),property.key());
                if (propertyHint.isEmpty()) propertyHint = EditorI18n.propertyHint(property.key());
            }
            if (label.startsWith("arcmenu_editor.")) label = property.key();
            EffectUi.text(g, font, EffectUi.fit(font, label, labelWidth - 8), p.x() + 17, field.y + 6, EffectUi.MUTED);
            if (EffectPropertyRules.animated(node.kind(),property.key())) {
                boolean animated = effect.active() && effect.snapshot().tracks().stream().anyMatch(t -> t.target().equals(node.id()) && t.property().equals(property.key()));
                int color = animated ? EffectUi.ACCENT : 0xFF757575;
                g.outline(p.x()+5, field.y+7, 5, 6, color);
                control(new EditorLayout.Rect(p.x()+2, field.y, 12, 19), "", EffectUi.label("add_key"), () -> togglePropertyKey(node.id(),property.key()), false);
            }
            int valueX = p.x() + labelWidth + 8;
            g.fill(valueX, field.y + 2, p.right()-6, field.y+19, 0xFF171717);
            if(property.type()==EditorProtocol.PROPERTY_COLOR){try{g.fill(p.right()-18,field.y+5,p.right()-9,field.y+15,EffectColorPicker.parse(property.value()));}catch(IllegalArgumentException ignored){}control(new EditorLayout.Rect(p.right()-21,field.y+2,16,17),"",EffectUi.label("color_picker"),()->colorPicker(field),false);}
            if(property.key().equals("source") && EditorImageTypes.imageKind(node.kind())) {
                EffectToolIcons.draw(g,toolKey(node.kind()),p.right()-18,field.y+4,EffectUi.ACCENT);
                control(new EditorLayout.Rect(p.right()-21,field.y+2,16,17),"",EditorI18n.text("arcmenu_editor.images.choose_source"),()->sourcePicker(field),false);
            }
            if (field.box == null) {
                EffectUi.text(g, font, EffectUi.fit(font, property.value(), p.right()-valueX-12), valueX+5, field.y+6, EffectUi.ACCENT);
                control(new EditorLayout.Rect(valueX, field.y+2, p.right()-valueX-6, 17), "", "", () -> {
                    String value = property.type() == EditorProtocol.PROPERTY_BOOLEAN ? Boolean.toString(!Boolean.parseBoolean(property.value()))
                            : (property.choices().isEmpty() ? property.value() : property.choices().get((Math.max(0, property.choices().indexOf(property.value()))+1) % property.choices().size()));
                    property(field, value);
                }, false);
            }
        }
        EffectUi.text(g, font, EffectUi.fit(font, scene.status(), p.width()-14), p.x()+7, p.bottom()-12, scene.errorStatus()?0xFFE58D8D:EffectUi.MUTED);
    }
    private void title(GuiGraphicsExtractor g, EditorLayout.Rect p, String key) { EffectUi.tab(g, font, EffectUi.label(key), p.x()+8, p.y()+6, true); g.fill(p.x(), p.y()+21, p.right(), p.y()+22, EffectUi.BORDER); }
    private void control(EditorLayout.Rect rect, String label, String hint, Runnable action, boolean active) { controls.add(new Control(rect,label,hint,action,active)); }
    private void button(GuiGraphicsExtractor g, EditorLayout.Rect rect, String label, String hint, Runnable action, boolean active, int mx, int my) {
        if (active || rect.contains(mx,my)) g.fill(rect.x(),rect.y(),rect.right(),rect.bottom(),active?0xFF33465C:0xFF383838);
        EffectUi.text(g,font,EffectUi.fit(font,label,rect.width()-3),rect.x()+Math.max(2,(rect.width()-EffectUi.width(font,label))/2),rect.y()+4,active?EffectUi.ACCENT:EffectUi.TEXT);
        control(rect,label,hint,action,active);
    }
    private EffectPopup.Item commandItem(String id) {
        String label=EffectCommands.list().stream().filter(c->c.id().equals(id)).map(c->EffectUi.label(c.label())).findFirst().orElse(EffectUi.label(id.replace('-','_')));
        String shortcut=EffectCommands.shortcut(id);if(shortcut.isEmpty())shortcut=EffectTimeline.shortcuts().getOrDefault(id,"");
        return new EffectPopup.Item(label,shortcut,()->workspaceCommand(id),commandEnabled(id));
    }
    private List<EffectPopup.Item> commandItems(String... ids) {return Arrays.stream(ids).map(this::commandItem).toList();}
    private void menu(GuiGraphicsExtractor g,int mx,int my) {
        if(openedMenu.isEmpty())return;
        if(!popup.active()) {
            List<EffectPopup.Item> items=switch(openedMenu) {
                case "file"->commandItems("save","mcp","close");
                case "edit"->commandItems("undo","redo","cut","copy","paste","duplicate","split","delete","select-all","deselect","command-search");
                case "composition"->commandItems("settings","first-frame","last-frame","work-start","work-end");
                case "layer"->commandItems("new-text","new-rectangle","new-gif","new-group","group","ungroup","duplicate","split","rename","lock","unlock-all","raise","lower","to-front","to-back","layer-settings");
                case "animation"->commandItems("add-key","cue","marker","auto-key","ease","ease-in","ease-out","linear","hold","auto-bezier","previous-key","next-key");
                case "view"->commandItems("fit","actual-size","guides","safe-guides","timeline","value-graph","speed-graph","zoom-in","zoom-out","fit-graph");
                case "window"->commandItems("assets","expand-all","collapse-all","reset-workspace");
                default->commandItems("shortcuts","command-search","mcp");
            };
            var anchor=controls.stream().filter(c->c.label.equals(EffectUi.label(openedMenu))).findFirst().orElse(null);
            popup.open(anchor==null?8:anchor.rect.x(),18,items);
        }
    }
    @Override public void timelineContextMenu(double x,double y,List<EffectTimeline.MenuItem> items) {
        openedMenu="";popup.open((int)x,(int)y,items.stream().map(i->new EffectPopup.Item(EditorI18n.text(i.translationKey()),"",i.action(),i.enabled())).toList());
    }
    @Override public void timelineCommand(String action) {workspaceCommand(action);}
    @Override public void timelineSearchFocus(EditBox field) {setFocused(field);}
    private void layerMenu(double x,double y) {openedMenu="";popup.open((int)x,(int)y,commandItems("rename","cut","copy","paste","duplicate","split","group","ungroup","lock","raise","lower","to-front","to-back","layer-settings","delete"));}
    private void emptyMenu(double x,double y) {openedMenu="";popup.open((int)x,(int)y,commandItems("new-text","new-rectangle","new-gif","new-group","paste","settings","cue","marker","expand-all","collapse-all"));}

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (deferredPointer) { queuePointer(new DeferredInput(0, e, doubleClick, 0, 0)); return true; }
        if(panels==null) return super.mouseClicked(e,doubleClick);
        if(popup.active()) {openedMenu="";return popup.click(e.x(),e.y(),e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT);}
        if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT)for(int ci=controls.size()-1;ci>=0;ci--) {var c=controls.get(ci); if(c.rect.contains(e.x(),e.y())) {commitFocused();if(effect.busy())deferredUI.add(c.action);else c.action.run();return true;}}
        if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT&&projectSearch!=null&&new EditorLayout.Rect(panels.project().x()+6,panels.project().y()+77,panels.project().width()-12,19).contains(e.x(),e.y())) {
            if(getFocused()!=projectSearch)commitFocused();
            setFocused(projectSearch);timelineFocused=false;
            if(!projectSearch.mouseClicked(e,doubleClick))projectSearch.moveCursorToEnd(false);
            return true;
        }
        if(panels.properties().contains(e.x(),e.y())) {
            for(var f:fields)if(e.y()>=f.y&&e.y()<f.y+21) {
                if(e.button()==GLFW.GLFW_MOUSE_BUTTON_RIGHT) {commitFocused();var node=scene.find(f.target);boolean animatable=node!=null&&EffectPropertyRules.animated(node.kind(),f.property.key());boolean tracked=effect.snapshot().tracks().stream().anyMatch(t->t.target().equals(f.target)&&t.property().equals(f.property.key()));popup.open((int)e.x(),(int)e.y(),List.of(new EffectPopup.Item(EffectUi.label("add_key"),"",()->togglePropertyKey(f.target,f.property.key()),animatable),new EffectPopup.Item(EffectUi.label("key_settings"),"",()->effectDialog("key",f.target,f.property.key(),(int)effect.playhead(),keyAt(f.target,f.property.key()),null),animatable),new EffectPopup.Item(EffectUi.label("remove_animation"),"",()->effect.submit("delete-track",f.target,f.property.key(),0,0,"","linear",0,0,0,0),tracked)));return true;}
                int valueX=panels.properties().x()+Math.min(84,panels.properties().width()*43/100)+8;
                if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT&&f.box!=null&&e.x()>=valueX&&e.x()<panels.properties().right()-6&&e.y()>=f.y+2&&e.y()<f.y+19) {
                    if(getFocused()!=f.box)commitFocused();
                    setFocused(f.box);timelineFocused=false;
                    if(!f.box.mouseClicked(e,doubleClick))f.box.moveCursorToEnd(false);
                    return true;
                }
                if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT&&e.x()<panels.properties().x()+Math.min(84,panels.properties().width()*43/100)+8&&(f.property.type()==EditorProtocol.PROPERTY_NUMBER||f.property.type()==EditorProtocol.PROPERTY_INTEGER)) {
                    try{commitFocused();scrubValue=Double.parseDouble(f.box==null?f.property.value():f.box.getValue());scrubStartX=e.x();scrubField=f;setFocused(null);return true;}catch(NumberFormatException ignored){}
                }
            }
        }
        if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if(Math.abs(e.x()-panels.composition().x()+2)<4 && e.y()>40 && e.y()<panels.timeline().y()) divider=1;
            else if(Math.abs(e.x()-panels.preview().x()+2)<4 && e.y()>40) divider=2;
            else if(Math.abs(e.y()-panels.timeline().y())<3 && e.x()<panels.timeline().right()) divider=3;
            if(divider!=0) return true;
        }
        timelineFocused = panels.timeline().contains(e.x(),e.y());
        if(timelineFocused){commitFocused();setFocused(null);if(effect.sceneBusy()){deferPointer(e,doubleClick);return true;}}
        if(timeline.click(e,doubleClick))return true;
        for(var entry:entries) if(entry.rect.contains(e.x(),e.y())) {
            commitFocused();setFocused(null);
            if(entry.kind.equals("layer")) {
                var node=scene.find(entry.id);
                if(e.button()==GLFW.GLFW_MOUSE_BUTTON_RIGHT) {if(!scene.selected(entry.id))select(entry.id);layerMenu(e.x(),e.y());return true;}
                if(node!=null&&node.kind()==EditorProtocol.KIND_GROUP&&e.x()<panels.project().x()+19+Math.min(panels.project().width()/3,depth(node)*8)) {if(!scene.expanded().remove(node.id()))scene.expanded().add(node.id());return true;}
                if(shift()&&!projectAnchor.isEmpty()) {List<String> ids=scene.nodes().stream().map(EditorProtocol.NodeSnapshot::id).toList();int a=ids.indexOf(projectAnchor),b=ids.indexOf(entry.id);if(a>=0&&b>=0)scene.selectAll(ids.subList(Math.min(a,b),Math.max(a,b)+1));rebuildWidgets();}
                else if(control()){scene.toggleSelection(entry.id);rebuildWidgets();}else {entry.action.run();projectAnchor=entry.id;}
                if(doubleClick)workspaceCommand("rename");
            } else if(e.button()==GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                var options=new ArrayList<EffectPopup.Item>();options.add(new EffectPopup.Item(EffectUi.label(entry.kind.equals("composition")?"settings":"add_asset"),"",entry.kind.equals("composition")?()->workspaceCommand("settings"):entry.action));
                if(entry.kind.equals("template"))options.add(new EffectPopup.Item(EffectUi.label("delete"),"",()->ArcMenuEditorClient.send(new EditorProtocol.DeleteTemplatePacket(scene.snapshot().revision(),entry.id))));
                popup.open((int)e.x(),(int)e.y(),options);
            } else if(doubleClick)entry.action.run();else if(!entry.kind.equals("composition"))draggedAsset=entry.id;
            return true;
        }
        if(e.button()==GLFW.GLFW_MOUSE_BUTTON_RIGHT&&panels.project().contains(e.x(),e.y())) {emptyMenu(e.x(),e.y());return true;}
        if(panels.viewport().contains(e.x(),e.y())&&scene.snapshot()!=null) {
            commitFocused(); setFocused(null);
            if(e.button()==GLFW.GLFW_MOUSE_BUTTON_MIDDLE||tool.equals("hand")) {panning=true;lastMouseX=e.x();lastMouseY=e.y();return true;}
            if(tool.equals("zoom")&&e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT){viewZoom=Math.min(8,viewZoom*(shift()?1/1.25:1.25));viewZoom=Math.max(1,viewZoom);return true;}
            if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT&&List.of("rectangle","frame","text","line").contains(tool)){drawShape=true;marqueeX=marqueeEndX=e.x();marqueeY=marqueeEndY=e.y();return true;}
            var node=hit(e.x(),e.y());
            if(e.button()==GLFW.GLFW_MOUSE_BUTTON_RIGHT) {if(node!=null&&!scene.selected(node.id()))select(node.id());if(node==null)emptyMenu(e.x(),e.y());else layerMenu(e.x(),e.y());return true;}
            if(node==null) {if(!shift())scene.clearSelection();marquee=true;marqueeX=marqueeEndX=e.x();marqueeY=marqueeEndY=e.y();rebuildWidgets();return true;}
            if(shift()||control()) {scene.toggleSelection(node.id());rebuildWidgets();return true;}
            if(!scene.selected(node.id()))scene.selectOnly(node.id());rebuildWidgets();
            if(scene.locked(node.id())||node.locked())return true;
            if(tool.equals("rotate")){rotationTarget=node.id();rotationOrigin=Math.atan2(menuY(e.y())-node.y(),menuX(e.x())-node.x());rotationBase=rotationValue=EffectTransforms.number(node,"rotation.z",0);return true;}
            dragging=node.id(); var c=calibration.get(node.kind());
            resizing=Math.abs(e.x()-sx(c.proxyX(node.x(),node.width())+c.proxyWidth(node.width())/2))<7&&Math.abs(e.y()-sy(c.proxyY(node.y(),node.height())-c.proxyHeight(node.height())/2))<7;
            grabX=menuX(e.x())-node.x(); grabY=menuY(e.y())-node.y(); gesture=nextGesture++; released=false; awaiting=false; finalSent=false; pending=null; sent=null;
            return true;
        }
        return super.mouseClicked(e,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy) {
        if (deferredPointer) { queuePointer(new DeferredInput(1, e, false, dx, dy)); return true; }
        if(divider!=0) { if(divider==1) geometry.left=(int)e.x(); if(divider==2) geometry.right=width-(int)e.x(); if(divider==3) geometry.bottom=height-(int)e.y(); rebuildWidgets(); return true; }
        if(!rotationTarget.isEmpty()){var n=scene.find(rotationTarget);if(n!=null){double angle=Math.atan2(menuY(e.y())-n.y(),menuX(e.x())-n.x());rotationValue=rotationBase+Math.toDegrees(Math.atan2(Math.sin(angle-rotationOrigin),Math.cos(angle-rotationOrigin)));if(shift())rotationValue=Math.rint(rotationValue/15)*15;scene.status(EditorI18n.text("arcmenu_editor.workspace.rotation_preview",formatNumber(rotationValue)));}return true;}
        if(scrubField!=null) {double step=shift()?.1:control()?10:1,value=scrubValue+(e.x()-scrubStartX)*step;if(scrubField.property.type()==EditorProtocol.PROPERTY_INTEGER)value=Math.rint(value);if(scrubField.box!=null)scrubField.box.setValue(formatNumber(value));return true;}
        if(panning){viewPanX-=(e.x()-lastMouseX)/Math.max(1,panels.viewport().width())/viewZoom;viewPanY+=(e.y()-lastMouseY)/Math.max(1,panels.viewport().height())/viewZoom;lastMouseX=e.x();lastMouseY=e.y();return true;}
        if(marquee||drawShape){marqueeEndX=e.x();marqueeEndY=e.y();return true;}
        if(!draggedAsset.isEmpty())return true;
        if(timeline.drag(e)) return true;
        if(gesture!=0) { pending=pointer(e.x(),e.y()); flushDrag(); return true; }
        return super.mouseDragged(e,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (deferredPointer) { queuePointer(new DeferredInput(2, e, false, 0, 0)); return true; }
        if(divider!=0) { divider=0; return true; }
        if(!rotationTarget.isEmpty()){String id=rotationTarget;rotationTarget="";var root=new JsonObject();var changes=new JsonArray();var c=new JsonObject();c.addProperty("target",id);c.addProperty("property","rotation.z");c.addProperty("value",formatNumber(rotationValue));changes.add(c);root.add("changes",changes);effect.submit("properties","","",(int)effect.playhead(),0,root.toString(),"linear",0,0,0,0);return true;}
        if(scrubField!=null){var f=scrubField;scrubField=null;if(f.box!=null)property(f,f.box.getValue());return true;}
        if(panning){panning=false;saveWorkspace();return true;}
        if(drawShape){drawShape=false;createShape();return true;}
        if(marquee){marquee=false;double lx=Math.min(marqueeX,marqueeEndX),rx=Math.max(marqueeX,marqueeEndX),ty=Math.min(marqueeY,marqueeEndY),by=Math.max(marqueeY,marqueeEndY);for(var n:scene.nodes())if(n.visible()&&!scene.locked(n.id())&&!n.locked()&&sx(n.x())>=lx&&sx(n.x())<=rx&&sy(n.y())>=ty&&sy(n.y())<=by&&!scene.selected(n.id()))scene.toggleSelection(n.id());rebuildWidgets();return true;}
        if(!draggedAsset.isEmpty()){
            String asset=draggedAsset;draggedAsset="";
            if(EditorImageTypes.image(asset)) {
                if(panels.viewport().contains(e.x(),e.y())||panels.timeline().contains(e.x(),e.y()))create(EditorImageTypes.kind(asset),asset);
            } else if(panels.timeline().contains(e.x(),e.y())||panels.viewport().contains(e.x(),e.y()))effectDialog("cue","","",panels.timeline().contains(e.x(),e.y())?timeline.frameAt(e.x()):(int)effect.playhead(),null,new EffectProtocol.Cue("cue-"+System.currentTimeMillis(),panels.timeline().contains(e.x(),e.y())?timeline.frameAt(e.x()):(int)effect.playhead(),-1,asset.endsWith(".nbs")?"nbs":"sound",asset,1,1,true));
            return true;
        }
        if(timeline.release(e)) return true;
        if(gesture!=0) { released=true; if(pending==null) finishDrag(); else flushDrag(); return true; }
        return super.mouseReleased(e);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(popup.wheel(vertical))return true;
        if(panels.viewport().contains(x,y)&&control()){viewZoom=Math.max(1,Math.min(8,viewZoom*Math.pow(1.15,vertical)));return true;}
        if(timeline.scroll(x,y,vertical,control(),shift())) return true;
        if(panels.properties().contains(x,y)) { commitFocused(); propertyScroll=Math.max(0,Math.min(Math.max(0,scene.find(scene.selectedId())==null?0:scene.find(scene.selectedId()).properties().size()*21-panels.properties().height()+75),propertyScroll-(int)(vertical*21))); rebuildWidgets(); return true; }
        if(panels.project().contains(x,y)) { projectScroll=Math.max(0,projectScroll-(int)vertical); return true; }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if(e.isEscape()&&deferredPointer){deferredPointer=false;deferredPointerEvents.clear();deferredUI.clear();return true;}
        if(popup.active()){if(e.isEscape()||e.isConfirmation())openedMenu="";return popup.key(e.key()==GLFW.GLFW_KEY_DOWN?1:e.key()==GLFW.GLFW_KEY_UP?-1:0,e.isConfirmation(),e.isEscape());}
        if(projectSearch!=null&&projectSearch.isFocused()) {if(e.isEscape()){setFocused(null);return true;}return super.keyPressed(e);}
        for(var f:fields)if(f.box!=null&&f.box.isFocused()) {if(e.isConfirmation()){property(f,submit(f.property,f.box.getValue()));setFocused(null);return true;}if(e.isEscape()){f.box.setValue(display(f.property));setFocused(null);return true;}return super.keyPressed(e);}
        if(timeline.searchFocused())return timeline.key(e)||super.keyPressed(e);
        if(e.isEscape()&&(drawShape||marquee||panning||scrubField!=null||!rotationTarget.isEmpty()||!draggedAsset.isEmpty())){drawShape=marquee=panning=false;scrubField=null;rotationTarget=draggedAsset="";rebuildWidgets();return true;}
        if(e.isEscape()&&timeline.key(e))return true;
        String key=keyName(e.key()),action=EffectCommands.lookup(key,e.hasControlDown(),e.hasShiftDown(),e.hasAltDown());
        if(!action.isEmpty()) {
            if(timelineFocused&&timeline.keyEditingContext()&&Set.of("copy","cut","paste","delete","select-all").contains(action)&&timeline.command(switch(action){case "copy"->"copy-keys";case "cut"->"cut-keys";case "paste"->"paste-keys";case "delete"->"delete-keys";default->"select-all-keys";}))return true;
            workspaceCommand(action);return true;
        }
        if(e.key()==GLFW.GLFW_KEY_BACKSPACE){if(timelineFocused&&timeline.selectedKeyCount()>0)timeline.command("delete-keys");else delete();return true;}
        if(!timelineFocused&&!e.hasControlDown()&&!e.hasAltDown()&&(e.key()==GLFW.GLFW_KEY_LEFT||e.key()==GLFW.GLFW_KEY_RIGHT||e.key()==GLFW.GLFW_KEY_UP||e.key()==GLFW.GLFW_KEY_DOWN)){nudge(e.key(),e.hasShiftDown()?10:1);return true;}
        if(timeline.key(e))return true;
        return super.keyPressed(e);
    }
    public static String keyName(int key){return switch(key){case GLFW.GLFW_KEY_A->"A";case GLFW.GLFW_KEY_B->"B";case GLFW.GLFW_KEY_C->"C";case GLFW.GLFW_KEY_D->"D";case GLFW.GLFW_KEY_E->"E";case GLFW.GLFW_KEY_F->"F";case GLFW.GLFW_KEY_G->"G";case GLFW.GLFW_KEY_H->"H";case GLFW.GLFW_KEY_I->"I";case GLFW.GLFW_KEY_J->"J";case GLFW.GLFW_KEY_K->"K";case GLFW.GLFW_KEY_L->"L";case GLFW.GLFW_KEY_M->"M";case GLFW.GLFW_KEY_N->"N";case GLFW.GLFW_KEY_O->"O";case GLFW.GLFW_KEY_P->"P";case GLFW.GLFW_KEY_Q->"Q";case GLFW.GLFW_KEY_R->"R";case GLFW.GLFW_KEY_S->"S";case GLFW.GLFW_KEY_T->"T";case GLFW.GLFW_KEY_U->"U";case GLFW.GLFW_KEY_V->"V";case GLFW.GLFW_KEY_W->"W";case GLFW.GLFW_KEY_X->"X";case GLFW.GLFW_KEY_Y->"Y";case GLFW.GLFW_KEY_Z->"Z";case GLFW.GLFW_KEY_ENTER->"Enter";case GLFW.GLFW_KEY_ESCAPE->"Esc";case GLFW.GLFW_KEY_DELETE->"Delete";case GLFW.GLFW_KEY_F2->"F2";case GLFW.GLFW_KEY_F3->"F3";case GLFW.GLFW_KEY_SLASH->"/";case GLFW.GLFW_KEY_APOSTROPHE->"\'";case GLFW.GLFW_KEY_UP->"Up";case GLFW.GLFW_KEY_DOWN->"Down";default->"";};}
    public boolean commandEnabled(String id) {
        if(!effect.active())return false;
        if(Set.of("delete","duplicate","split","rename","copy","cut","group","group-layers","ungroup","lock","raise","lower","to-front","to-back","layer-settings").contains(id))return !selection().isEmpty();
        if(id.equals("paste"))return !copied.isEmpty()&&copiedProject.equals(scene.snapshot().menuId());
        if(Set.of("ease","ease-in","ease-out").contains(id))return timeline.easingAvailable();
        if(Set.of("linear","hold","auto-bezier","delete-keys","reverse-keys").contains(id))return timeline.selectedKeyCount()>0;
        return true;
    }
    public boolean workspaceCommand(String id) {
        if(!id.equals("close")&&effect.busy()){deferredUI.add(()->workspaceCommand(id));return true;}
        if(!commandEnabled(id))return false;
        switch(id) {
            case "save","undo","redo"->effect.action(id);
            case "copy"->copy();case "cut"->{copySelection("cut-layers");}case "paste"->paste();case "duplicate"->duplicate();case "delete"->delete();
            case "split"->{var data=new JsonObject();data.add("targets",new Gson().toJsonTree(selection()));effect.submit("split-layer",scene.selectedId(),"",(int)effect.playhead(),0,data.toString(),"linear",0,0,0,0);}
            case "select-all"->{scene.selectAll(scene.nodes().stream().map(EditorProtocol.NodeSnapshot::id).toList());rebuildWidgets();}
            case "deselect"->{scene.clearSelection();timeline.command("deselect-keys");rebuildWidgets();}
            case "rename"->{propertyScroll=0;rebuildWidgets();fields.stream().filter(f->f.box!=null&&f.property.key().equals("id")).findFirst().ifPresent(f->{setFocused(f.box);f.box.setCursorPosition(0);f.box.setHighlightPos(f.box.getValue().length());});}
            case "group","group-layers"->group();case "focus-properties"->{fields.stream().filter(f->f.box!=null&&!f.property.key().equals("id")).findFirst().ifPresent(f->setFocused(f.box));}case "ungroup"->effect.submit("ungroup-layer",scene.selectedId(),"",0,0,"","linear",0,0,0,0);
            case "lock"->{for(String target:selection()){scene.toggleLocked(target);}}
            case "unlock-all"->scene.unlockAll();
            case "raise","lower","to-front","to-back"->arrange(id);
            case "settings"->effectDialog("settings","","",0,null,null);case "layer-settings"->effectDialog("layer",scene.selectedId(),"",0,null,null);
            case "new-text"->create(EditorProtocol.KIND_TEXT,"");case "new-rectangle"->create(EditorProtocol.KIND_RECTANGLE,"");case "new-gif"->create(EditorProtocol.KIND_GIF,"");case "new-group"->create(EditorProtocol.KIND_GROUP,"");
            case "tool-select"->tool="select";case "tool-hand"->tool="hand";case "tool-zoom"->tool="zoom";case "tool-rotate"->tool="rotate";case "tool-shape"->tool=tool.equals("rectangle")?"frame":"rectangle";case "tool-text"->tool="text";
            case "guides"->showGuides=!showGuides;case "safe-guides"->safeGuides=!safeGuides;
            case "fit","actual-size"->{fitView=id.equals("fit");viewZoom=1;viewPanX=viewPanY=0;}
            case "command-search","shortcuts"->minecraft.setScreen(new EffectCommandScreen(this));
            case "search-project"->{setFocused(projectSearch);projectSearch.setCursorPosition(projectSearch.getValue().length());}
            case "reset-workspace"->{geometry.left=geometry.right=geometry.bottom=-1;viewZoom=1;viewPanX=viewPanY=0;rebuildWidgets();}
            case "assets"->{projectTab="assets";projectScroll=0;}
            case "audio-preview"->EffectAudioPreview.INSTANCE.enabled(!EffectAudioPreview.INSTANCE.enabled());
            case "add-key"->effectDialog("key",scene.selectedId(),"",(int)effect.playhead(),null,null);
            case "cue","marker"->effectDialog(id,"","",(int)effect.playhead(),null,null);
            case "auto-key"->effect.submit("auto-key","","",0,0,Boolean.toString(!effect.snapshot().autoKey()),"linear",0,0,0,0);
            case "value-graph","speed-graph","timeline"->graphView(id);
            case "mcp"->minecraft.setScreen(new McpScreen(this));case "close"->onClose();
            default->{return timeline.command(id);}
        }
        return true;
    }
    private EffectProtocol.Key keyAt(String target,String property) {return effect.snapshot().tracks().stream().filter(t->t.target().equals(target)&&t.property().equals(property)).flatMap(t->t.keys().stream()).filter(k->k.frame()==(int)effect.playhead()).findFirst().orElse(null);}
    private void togglePropertyKey(String target,String property) {var key=keyAt(target,property);if(key!=null)effect.submit("delete-key",target,property,key.frame(),0,"","linear",0,0,0,0);else {var n=scene.find(target);if(n!=null)n.properties().stream().filter(p->p.key().equals(property)).findFirst().ifPresent(p->effect.key(target,property,new EffectProtocol.Key((int)effect.playhead(),p.value(),"linear",1.0/3,0,2.0/3,1),(int)effect.playhead()));}}
    private void colorPicker(Field field){try{minecraft.setScreen(new EffectColorPicker(this,String.format(Locale.ROOT,"#%02X%06X",(int)Math.max(0,Math.min(255,EffectTransforms.number(scene.find(field.target),"opacity",255))),EffectColorPicker.parse(field.property.value())&0xFFFFFF),value->setColor(field,value)));}catch(IllegalArgumentException error){scene.error(error.getMessage());}}
    private void setColor(Field field,String value){int rgba=EffectColorPicker.parse(value);var root=new JsonObject();var changes=new JsonArray();var color=new JsonObject();color.addProperty("target",field.target);color.addProperty("property",field.property.key());color.addProperty("value",String.format(Locale.ROOT,"#%06X",rgba&0xFFFFFF));changes.add(color);var node=scene.find(field.target);if(node!=null&&EffectPropertyRules.animated(node.kind(),"opacity")){var alpha=new JsonObject();alpha.addProperty("target",field.target);alpha.addProperty("property","opacity");alpha.addProperty("value",Integer.toString(rgba>>>24));changes.add(alpha);}root.add("changes",changes);effect.submit("properties","","",(int)effect.playhead(),0,root.toString(),"linear",0,0,0,0);}
    private static String formatNumber(double value){return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private void nudge(int key,double amount){var data=new JsonObject();var changes=new JsonArray();double x=key==GLFW.GLFW_KEY_LEFT?-amount:key==GLFW.GLFW_KEY_RIGHT?amount:0,y=key==GLFW.GLFW_KEY_UP?amount:key==GLFW.GLFW_KEY_DOWN?-amount:0;
        for(String id:selection()){var n=scene.find(id);if(n==null||scene.locked(id)||n.locked())continue;boolean selectedAncestor=false;String parent=n.parentId();var seen=new HashSet<String>();while(!parent.isEmpty()&&seen.add(parent)){if(scene.selected(parent)){selectedAncestor=true;break;}var p=scene.find(parent);parent=p==null?"":p.parentId();}if(selectedAncestor)continue;
            try{var delta=EffectTransforms.localDelta(n,scene.nodes(),x,y);double[] values={delta.x,delta.y,delta.z};String[] axes={"offset.x","offset.y","offset.z"};for(int i=0;i<3;i++)if(Math.abs(values[i])>1e-9){var c=new JsonObject();c.addProperty("target",id);c.addProperty("property",axes[i]);c.addProperty("value",formatNumber(EffectTransforms.number(n,axes[i],0)+values[i]));changes.add(c);}}
            catch(IllegalArgumentException invalid){scene.error(invalid.getMessage());return;}
        }if(changes.isEmpty())return;data.add("changes",changes);effect.submit("properties","","",(int)effect.playhead(),0,data.toString(),"linear",0,0,0,0);}
    private void ungroup(){var group=scene.find(scene.selectedId());if(group==null||group.kind()!=EditorProtocol.KIND_GROUP)return;List<String> children=scene.nodes().stream().filter(n->n.parentId().equals(group.id())).map(EditorProtocol.NodeSnapshot::id).toList();if(!children.isEmpty())ArcMenuEditorClient.send(new EditorProtocol.ReparentPacket(scene.snapshot().revision(),children,group.parentId(),group.id()));}
    private void arrange(String action){var chosen=selection();if(chosen.isEmpty())return;var parent=scene.find(chosen.getFirst()).parentId();if(chosen.stream().anyMatch(id->!scene.find(id).parentId().equals(parent)))return;var siblings=scene.nodes().stream().filter(n->n.parentId().equals(parent)).map(EditorProtocol.NodeSnapshot::id).toList();var rest=new ArrayList<>(siblings);rest.removeAll(chosen);int first=siblings.indexOf(chosen.getFirst()),index=action.equals("to-front")?rest.size():action.equals("to-back")?0:action.equals("raise")?Math.min(rest.size(),first+1):Math.max(0,first-1);String before=index<rest.size()?rest.get(index):"";ArcMenuEditorClient.send(new EditorProtocol.ReorderPacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,chosen,before));}
    private void createShape(){if(scene.snapshot()==null)return;double x1=menuX(marqueeX),x2=menuX(marqueeEndX),y1=menuY(marqueeY),y2=menuY(marqueeEndY);byte kind=switch(tool){case "frame"->EditorProtocol.KIND_FRAME;case "text"->EditorProtocol.KIND_TEXT;case "line"->EditorProtocol.KIND_LINE;default->EditorProtocol.KIND_RECTANGLE;};double w=Math.max(1,Math.abs(x2-x1)),h=Math.max(1,Math.abs(y2-y1));var initial=new JsonObject();initial.addProperty("type",tool);var offset=new JsonObject();offset.addProperty("x",(x1+x2)/2);offset.addProperty("y",(y1+y2)/2);initial.add("offset",offset);initial.addProperty("width",w);initial.addProperty("height",h);if(kind==EditorProtocol.KIND_TEXT)initial.addProperty("content","Text");var selected=scene.find(scene.selectedId());ArcMenuEditorClient.send(new EditorProtocol.CreatePacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,kind,selected!=null&&selected.kind()==0?selected.id():"",initial.toString()));tool="select";}
    public JsonObject workspaceMetadata() {
        var json=new Gson();var result=new JsonObject();result.add("panels",json.toJsonTree(panels));result.addProperty("popupOpen",popup.active());result.addProperty("tool",tool);result.addProperty("fit",fitView);result.addProperty("zoom",viewZoom);result.addProperty("selectedKeys",timeline.selectedKeyCount());
        var commands=new LinkedHashMap<String,String>();EffectCommands.list().forEach(c->commands.put(c.id(),c.shortcut()));EffectTimeline.shortcuts().forEach(commands::putIfAbsent);result.add("commands",json.toJsonTree(commands));result.add("timeline",timeline.workspaceMetadata());
        if(projectSearch!=null){var search=new JsonObject();search.add("bounds",json.toJsonTree(new EditorLayout.Rect(panels.project().x()+6,panels.project().y()+77,panels.project().width()-12,19)));search.addProperty("value",projectQuery);search.addProperty("focused",projectSearch.isFocused());result.add("projectSearch",search);}
        var propertyFields=new JsonArray();for(var field:fields){var entry=new JsonObject();entry.addProperty("target",field.target);entry.addProperty("property",field.property.key());entry.addProperty("value",field.property.value());entry.addProperty("y",field.y);entry.addProperty("type",field.property.type());if(field.box!=null){entry.addProperty("inputValue",field.box.getValue());entry.addProperty("focused",field.box.isFocused());}propertyFields.add(entry);}result.add("propertyFields",propertyFields);return result;
    }
    private static MouseButtonEvent mouseEvent(double x,double y,int button,int mods){return new MouseButtonEvent(x,y,new MouseButtonInfo(button==0?GLFW.GLFW_MOUSE_BUTTON_LEFT:button==1?GLFW.GLFW_MOUSE_BUTTON_RIGHT:GLFW.GLFW_MOUSE_BUTTON_MIDDLE,((mods&1)!=0?GLFW.GLFW_MOD_SHIFT:0)|((mods&2)!=0?GLFW.GLFW_MOD_CONTROL:0)|((mods&4)!=0?GLFW.GLFW_MOD_ALT:0)));}
    public boolean workspaceInput(String operation,String command,String chord,double x,double y,int button,int modifiers,double dx,double dy) {
        syntheticModifiers=modifiers;
        var input=minecraft.screen instanceof EffectWorkspaceChild child&&child.workspace()==this?minecraft.screen:this;
        try {return switch(operation){
            case "command"->workspaceCommand(command);
            case "shortcut"->invokeShortcut(chord);
            case "click"->input.mouseClicked(mouseEvent(x,y,button,modifiers),false);
            case "drag"->input.mouseDragged(mouseEvent(x,y,button,modifiers),dx,dy);
            case "release"->input.mouseReleased(mouseEvent(x,y,button,modifiers));
            case "scroll"->input.mouseScrolled(x,y,dx,dy);
            case "context-menu"->{if(command.equals("layer"))layerMenu(x,y);else if(command.equals("composition")||command.equals("project"))emptyMenu(x,y);else throw new IllegalArgumentException("Unknown context-menu region");yield true;}
            default->throw new IllegalArgumentException("Unknown workspace input operation");
        };}finally{syntheticModifiers=-1;}
    }
    private boolean invokeShortcut(String chord){chord=chord.split(" / ")[0].trim();if(chord.endsWith("+"))chord=chord.substring(0,chord.length()-1)+"=";int modifiers=0;String key="";for(String part:chord.split("\\+")){switch(part.toLowerCase(Locale.ROOT)){case "ctrl"->modifiers|=GLFW.GLFW_MOD_CONTROL;case "shift"->modifiers|=GLFW.GLFW_MOD_SHIFT;case "alt"->modifiers|=GLFW.GLFW_MOD_ALT;default->key=part;}}int code=shortcutCode(key);if(code<0)throw new IllegalArgumentException("Unknown shortcut key");var input=minecraft.screen instanceof EffectWorkspaceChild child&&child.workspace()==this?minecraft.screen:this;return input.keyPressed(new KeyEvent(code,0,modifiers));}
    private static int shortcutCode(String key){String name=key.toUpperCase(Locale.ROOT).replace(" ","");return switch(name){case "A"->GLFW.GLFW_KEY_A;case "B"->GLFW.GLFW_KEY_B;case "C"->GLFW.GLFW_KEY_C;case "D"->GLFW.GLFW_KEY_D;case "E"->GLFW.GLFW_KEY_E;case "F"->GLFW.GLFW_KEY_F;case "G"->GLFW.GLFW_KEY_G;case "H"->GLFW.GLFW_KEY_H;case "I"->GLFW.GLFW_KEY_I;case "J"->GLFW.GLFW_KEY_J;case "K"->GLFW.GLFW_KEY_K;case "L"->GLFW.GLFW_KEY_L;case "M"->GLFW.GLFW_KEY_M;case "N"->GLFW.GLFW_KEY_N;case "O"->GLFW.GLFW_KEY_O;case "P"->GLFW.GLFW_KEY_P;case "Q"->GLFW.GLFW_KEY_Q;case "R"->GLFW.GLFW_KEY_R;case "S"->GLFW.GLFW_KEY_S;case "T"->GLFW.GLFW_KEY_T;case "U"->GLFW.GLFW_KEY_U;case "V"->GLFW.GLFW_KEY_V;case "W"->GLFW.GLFW_KEY_W;case "X"->GLFW.GLFW_KEY_X;case "Y"->GLFW.GLFW_KEY_Y;case "Z"->GLFW.GLFW_KEY_Z;case "SPACE"->GLFW.GLFW_KEY_SPACE;case "ENTER"->GLFW.GLFW_KEY_ENTER;case "ESC"->GLFW.GLFW_KEY_ESCAPE;case "ESCAPE"->GLFW.GLFW_KEY_ESCAPE;case "DELETE"->GLFW.GLFW_KEY_DELETE;case "UP"->GLFW.GLFW_KEY_UP;case "DOWN"->GLFW.GLFW_KEY_DOWN;case "LEFT"->GLFW.GLFW_KEY_LEFT;case "RIGHT"->GLFW.GLFW_KEY_RIGHT;case "HOME"->GLFW.GLFW_KEY_HOME;case "END"->GLFW.GLFW_KEY_END;case "PAGEUP"->GLFW.GLFW_KEY_PAGE_UP;case "PAGEDOWN"->GLFW.GLFW_KEY_PAGE_DOWN;case "NUM0"->GLFW.GLFW_KEY_KP_0;case "/"->GLFW.GLFW_KEY_SLASH;case "\'"->GLFW.GLFW_KEY_APOSTROPHE;case ";"->GLFW.GLFW_KEY_SEMICOLON;case "["->GLFW.GLFW_KEY_LEFT_BRACKET;case "]"->GLFW.GLFW_KEY_RIGHT_BRACKET;case "="->GLFW.GLFW_KEY_EQUAL;case "-"->GLFW.GLFW_KEY_MINUS;case "F1"->GLFW.GLFW_KEY_F1;case "F2"->GLFW.GLFW_KEY_F2;case "F3"->GLFW.GLFW_KEY_F3;case "F4"->GLFW.GLFW_KEY_F4;case "F5"->GLFW.GLFW_KEY_F5;case "F6"->GLFW.GLFW_KEY_F6;case "F7"->GLFW.GLFW_KEY_F7;case "F8"->GLFW.GLFW_KEY_F8;case "F9"->GLFW.GLFW_KEY_F9;case "F10"->GLFW.GLFW_KEY_F10;case "F11"->GLFW.GLFW_KEY_F11;case "F12"->GLFW.GLFW_KEY_F12;default->-1;};}
    private Path workspaceFile(){return ArcMenuEditorClient.selectionCalibrationFile().resolveSibling("arcmenu-effect-workspace.properties");}
    private void loadWorkspace(){var preferences=new Properties();try(var in=Files.newInputStream(workspaceFile())){preferences.load(in);geometry.left=Integer.parseInt(preferences.getProperty("left","-1"));geometry.right=Integer.parseInt(preferences.getProperty("right","-1"));geometry.bottom=Integer.parseInt(preferences.getProperty("bottom","-1"));}catch(IOException|IllegalArgumentException ignored){}}
    private void saveWorkspace(){var preferences=new Properties();preferences.setProperty("left",Integer.toString(geometry.left));preferences.setProperty("right",Integer.toString(geometry.right));preferences.setProperty("bottom",Integer.toString(geometry.bottom));try{Files.createDirectories(workspaceFile().getParent());try(var out=Files.newOutputStream(workspaceFile())){preferences.store(out,"Effect workspace panel layout");}}catch(IOException ignored){}}
    private void sceneUndo(boolean redo) { if(scene.snapshot()!=null) ArcMenuEditorClient.send(redo?new EditorProtocol.RedoPacket(scene.snapshot().revision()):new EditorProtocol.UndoPacket(scene.snapshot().revision())); }
    private void select(String id) { scene.selectOnly(id); propertyScroll=0; rebuildWidgets(); }
    private void create(byte kind,String source) { if(scene.snapshot()==null) return; var selected=scene.find(scene.selectedId()); String parent=selected!=null&&selected.kind()==0?selected.id():""; ArcMenuEditorClient.send(new EditorProtocol.CreatePacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,kind,parent,source)); }
    private List<String> selection() { return scene.nodes().stream().map(EditorProtocol.NodeSnapshot::id).filter(scene::selected).toList(); }
    private void delete() { if(scene.snapshot()!=null&&!selection().isEmpty()) ArcMenuEditorClient.send(new EditorProtocol.DeleteManyPacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,selection())); }
    private void duplicate() { if(scene.snapshot()!=null&&!selection().isEmpty()) ArcMenuEditorClient.send(new EditorProtocol.DuplicatePacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,selection(),"",true)); }
    private void group() { if(scene.snapshot()!=null&&!selection().isEmpty()) ArcMenuEditorClient.send(new EditorProtocol.GroupPacket(scene.snapshot().revision(),selection())); }
    private void copySelection(String action){copied=selection();copiedProject=scene.snapshot()==null?"":scene.snapshot().menuId();var data=new JsonObject();data.add("targets",new Gson().toJsonTree(copied));effect.submit(action,scene.selectedId(),"",0,0,data.toString(),"linear",0,0,0,0);}
    private void copy(){copySelection("copy-layers");}
    private void paste(){if(scene.snapshot()!=null&&copiedProject.equals(scene.snapshot().menuId())&&!copied.isEmpty())effect.submit("paste-layers","","",(int)effect.playhead(),0,"","linear",0,0,0,0);}
    private void property(Field field,String value) { if(scene.snapshot()!=null&&!value.equals(field.property.value())) ArcMenuEditorClient.send(new EditorProtocol.SetPropertyPacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,field.target,field.property.key(),value)); }
    private void sourcePicker(Field field) {
        var node=scene.find(field.target);if(node==null||scene.snapshot()==null)return;
        var choices=new ArrayList<EffectPopup.Item>();
        for(var image:scene.snapshot().images())if(EditorAssetLibrary.isUserImage(image.path())&&EditorImageTypes.matches(node.kind(),image.path()))
            choices.add(new EffectPopup.Item(image.path(),"",()->property(field,image.path())));
        if(choices.isEmpty())choices.add(new EffectPopup.Item(EditorI18n.text("arcmenu_editor.images.none"),"",()->{},false));
        openedMenu="";popup.open(panels.properties().x(),field.y,choices);
    }
    private void commitFocused() { for(var f:fields) if(f.box!=null&&f.box.isFocused()) { property(f,submit(f.property,f.box.getValue())); setFocused(null); break; } }
    private void deferPointer(MouseButtonEvent event, boolean doubleClick) {
        deferredPointer = true; queuePointer(new DeferredInput(0, event, doubleClick, 0, 0));
        deferredUI.add(() -> {
            deferredPointer = false; var events = new ArrayList<>(deferredPointerEvents); deferredPointerEvents.clear();
            for (var input : events) switch (input.type) {
                case 0 -> mouseClicked(input.event, input.doubleClick);
                case 1 -> mouseDragged(input.event, input.dx, input.dy);
                case 2 -> mouseReleased(input.event);
                default -> throw new IllegalArgumentException("Unknown deferred pointer event");
            }
        });
    }
    private void queuePointer(DeferredInput input) {
        if (input.type == 1 && !deferredPointerEvents.isEmpty() && deferredPointerEvents.getLast().type == 1) deferredPointerEvents.removeLast();
        if (deferredPointerEvents.size() >= 128) { deferredPointerEvents.clear(); deferredPointer = false; deferredUI.clear(); return; }
        deferredPointerEvents.addLast(input);
    }
    private static String display(EditorProtocol.PropertySnapshot p) { return p.type()==EditorProtocol.PROPERTY_MULTILINE?p.value().replace("\\","\\\\").replace("\n","\\n"):p.value(); }
    private static String submit(EditorProtocol.PropertySnapshot p,String value) {
        if(p.type()!=EditorProtocol.PROPERTY_MULTILINE) return value.trim(); StringBuilder out=new StringBuilder(); boolean escape=false;
        for(char c:value.toCharArray()) { if(escape) { if(c=='n') out.append('\n'); else if(c=='\\') out.append('\\'); else out.append('\\').append(c); escape=false; } else if(c=='\\') escape=true; else out.append(c); }
        if(escape) out.append('\\'); return out.toString();
    }
    private EditorProtocol.NodeSnapshot hit(double x,double y) { return scene.nodes().stream().filter(n->{ var c=calibration.get(n.kind()); double px=c.proxyX(n.x(),n.width()),py=c.proxyY(n.y(),n.height()); return n.visible()&&Math.abs(menuX(x)-px)<=c.proxyWidth(n.width())/2&&Math.abs(menuY(y)-py)<=c.proxyHeight(n.height())/2; }).max(Comparator.comparingInt(n->scene.nodes().indexOf(n))).orElse(null); }
    private int previewZoom() {
        var window = minecraft.getWindow();
        var source = EditorViewport.sourceFrame(window.getWidth(), window.getHeight());
        return source == null ? 100 : (int) Math.round(window.getWidth() * 100.0 / source.width());
    }
    private EditorLayout.ScreenRect virtualScreen() {
        var snapshot = scene.snapshot(); var view = effect.viewport();
        var window = minecraft.getWindow(); var physical = EditorViewport.physical(window);
        var nominal = panels.viewport();
        var aperture = physical == null
                ? new EditorLayout.ScreenRect(nominal.x(), nominal.y(), nominal.width(), nominal.height())
                : EffectProjection.guiAperture(new EditorLayout.Rect(physical.x(), physical.y(), physical.width(), physical.height()),
                    window.getWidth(), window.getHeight(), window.getGuiScaledWidth(), window.getGuiScaledHeight());
        if (view == null) return aperture;
        var raw = EffectProjection.calculate(aperture, snapshot.canvasWidth(), snapshot.canvasHeight(),
                view.pixelsPerBlock(), view.distance(), view.offsetX(), view.offsetY(), EditorViewport.verticalFov());
        var source = EditorViewport.sourceFrame(window.getWidth(), window.getHeight());
        return source == null ? raw : EffectProjection.cropped(raw, aperture, source, window.getWidth(), window.getHeight());
    }
    private int depth(EditorProtocol.NodeSnapshot node) { int depth=0; Set<String> seen=new HashSet<>(); while(node!=null&&!node.parentId().isEmpty()&&seen.add(node.parentId())) {depth++;node=scene.find(node.parentId());} return depth; }
    private boolean projectHidden(EditorProtocol.NodeSnapshot node) {Set<String> seen=new HashSet<>();while(node!=null&&!node.parentId().isEmpty()&&seen.add(node.parentId())) {if(!scene.expanded().contains(node.parentId()))return true;node=scene.find(node.parentId());}return false;}
    private int sx(double x) { var v=virtualScreen(); return (int)Math.round(v.x()+(x+scene.snapshot().canvasWidth()/2)/scene.snapshot().canvasWidth()*v.width()); }
    private int sy(double y) { var v=virtualScreen(); return (int)Math.round(v.y()+(scene.snapshot().canvasHeight()/2-y)/scene.snapshot().canvasHeight()*v.height()); }
    private double menuX(double x) { var v=virtualScreen(); return (x-v.x())/v.width()*scene.snapshot().canvasWidth()-scene.snapshot().canvasWidth()/2; }
    private double menuY(double y) { var v=virtualScreen(); return scene.snapshot().canvasHeight()/2-(y-v.y())/v.height()*scene.snapshot().canvasHeight(); }
    private EditorProtocol.Pointer pointer(double x,double y) {
        var aperture=panels.viewport(); x=Math.max(aperture.x(),Math.min(aperture.right()-.001,x)); y=Math.max(aperture.y(),Math.min(aperture.bottom()-.001,y));
        var v=virtualScreen(); double px=Math.max(-scene.snapshot().canvasWidth()*1.9,Math.min(scene.snapshot().canvasWidth()*1.9,menuX(x)));
        double py=Math.max(-scene.snapshot().canvasHeight()*1.9,Math.min(scene.snapshot().canvasHeight()*1.9,menuY(y)));
        var n=scene.find(dragging); if(resizing&&n!=null) { var c=calibration.get(n.kind()); px=c.serverRightX(n.x(),px); py=c.serverBottomY(n.y(),py); }
        x=v.x()+(px+scene.snapshot().canvasWidth()/2)/scene.snapshot().canvasWidth()*v.width();
        y=v.y()+(scene.snapshot().canvasHeight()/2-py)/scene.snapshot().canvasHeight()*v.height();
        return new EditorProtocol.Pointer(x,y,v.x(),v.y(),v.width(),v.height(),px,py);
    }
    private void flushDrag() {
        if(awaiting||pending==null||scene.snapshot()==null||gesture==0||(!released&&System.nanoTime()-lastSend<50_000_000L)) return;
        if(!released&&Objects.equals(pending,sent)) return; awaiting=true; finalSent=released; sent=pending; lastSend=System.nanoTime();
        ArcMenuEditorClient.send(resizing?new EditorProtocol.ResizePacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,dragging,pending,gesture,released)
                :new EditorProtocol.MovePacket(scene.snapshot().revision(),EditorProtocol.TAB_FRONTEND,dragging,pending,grabX,grabY,gesture,released));
    }
    private void finishDrag() { gesture=0; dragging=""; pending=null; sent=null; awaiting=false; released=false; finalSent=false; }
    private String timecode(int frame) { double fps=effect.active()?effect.snapshot().fps():30; int second=(int)(frame/fps), f=(int)Math.floor(frame-second*fps); return String.format(Locale.ROOT,"%d:%02d:%02d:%02d",second/3600,second/60%60,second%60,f); }
    private static String toolKey(byte kind) { return kind == EditorProtocol.KIND_REGION ? "region" : ElementSelectionCalibration.key(kind); }
    private boolean control() { if(syntheticModifiers>=0)return (syntheticModifiers&2)!=0;return InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_LCONTROL)||InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_RCONTROL); }
    private boolean shift() { if(syntheticModifiers>=0)return (syntheticModifiers&1)!=0;return InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_LSHIFT)||InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_RSHIFT); }
}

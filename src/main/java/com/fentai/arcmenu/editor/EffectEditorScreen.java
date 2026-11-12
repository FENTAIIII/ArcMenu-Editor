package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.EditorLayout.ScreenRect;
import com.fentai.arcmenu.editor.EffectTimeline.MenuItem;
import com.fentai.arcmenu.editor.mcp.McpScreen;
import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.ClosePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.CreatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DeleteManyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DeleteTemplatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DuplicatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.GroupPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.InstantiateTemplatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.MovePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.NodeSnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.PropertySnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.RedoPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ReorderPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ReparentPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ResizePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SetPropertyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.UndoPacket;
import com.fentai.arcmenu.protocol.EffectProtocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Dedicated composition workspace. Ordinary menu editor state and preferences remain independent. */
public final class EffectEditorScreen extends Screen implements EffectTimeline.Host
{
    private final EditorState scene;
    private final EffectState effect = ArcMenuEditorClient.EFFECT;
    private final EffectTimeline timeline;
    private final EffectWorkspaceLayout geometry = new EffectWorkspaceLayout();
    private final ElementSelectionCalibration calibration = new ElementSelectionCalibration();
    private EffectWorkspaceLayout.Layout panels;
    private final EffectPopup popup = new EffectPopup();
    private final ArrayDeque<Runnable> deferredUI = new ArrayDeque<>();
    private final ArrayDeque<DeferredInput> deferredPointerEvents = new ArrayDeque<>();
    private boolean deferredPointer;

    private record DeferredInput(int type, MouseButtonEvent event, boolean doubleClick, double dx, double dy)
    {}

    private double viewZoom = 1;
    private double viewPanX;
    private double viewPanY;
    private double lastMouseX;
    private double lastMouseY;
    private int syntheticModifiers = -1;
    private boolean panning;
    private boolean safeGuides;
    private boolean marquee;
    private boolean drawShape;
    private double marqueeX;
    private double marqueeY;
    private double marqueeEndX;
    private double marqueeEndY;
    private String draggedAsset = "";
    private String rotationTarget = "";
    private List<String> visibleProjectLayers = List.of();
    private List<String> hierarchyDrag = List.of();
    private double hierarchyStartX;
    private double hierarchyStartY;
    private boolean hierarchyDragging;
    private String hierarchyClick = "";
    private EffectHierarchy.Drop hierarchyDrop;
    private String propertyHint = "";
    private double rotationOrigin;
    private double rotationBase;
    private double rotationValue;
    private Field scrubField;
    private double scrubStartX;
    private double scrubValue;
    private final List<Control> controls = new ArrayList<>();
    private final List<Field> fields = new ArrayList<>();
    private final List<ProjectEntry> entries = new ArrayList<>();
    private String projectTab = "project";
    private String openedMenu = "";
    private String menuTarget = "";
    private String tool = "select";
    private int projectScroll;
    private int propertyScroll;
    private int divider;
    private boolean showGuides = true;
    private boolean timelineFocused;
    private boolean fitView;
    private List<String> copied = List.of();
    private String copiedProject = "";
    private String projectQuery = "";
    private String hierarchySelection = "";
    private EditBox projectSearch;
    private String dragging = "";
    private boolean resizing;
    private boolean released;
    private boolean awaiting;
    private boolean finalSent;
    private long nextGesture = 1;
    private long gesture;
    private long lastSend;
    private double grabX;
    private double grabY;
    private EditorProtocol.Pointer pending;
    private EditorProtocol.Pointer sent;

    private record Control(EditorLayout.Rect rect, String label, String hint, Runnable action, boolean active)
    {}

    private record Field(String target, PropertySnapshot property, EditBox box, int y)
    {}

    private record ProjectEntry(String id, String kind, EditorLayout.Rect rect, Runnable action)
    {}

    public EffectEditorScreen(EditorState state)
    {
        super(EditorI18n.component("arcmenu_editor.workspace.title"));
        scene = state;
        timeline = new EffectTimeline(this, state);
        calibration.load(ArcMenuEditorClient.selectionCalibrationFile());
        loadWorkspace();
    }

    @Override
    protected void init()
    {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets()
    {
        boolean restoreTimelineSearch = timeline.searchFocused();
        clearWidgets();
        fields.clear();
        panels = geometry.calculate(width, height);
        EditorViewport.update(panels.viewport());
        var projectPanel = panels.project();
        projectSearch = new EditBox(font, projectPanel.x() + 12, projectPanel.y() + 81, projectPanel.width() - 24, 12,
                EffectUi.component(EffectUi.label("search_project")));
        projectSearch.setBordered(false);
        projectSearch.setTextColor(EffectUi.TEXT);
        projectSearch.setMaxLength(64);
        projectSearch.addFormatter((value, position) -> EffectUi.component(value).getVisualOrderText());
        projectSearch.setValue(projectQuery);
        projectSearch.setResponder(value ->
        {
            projectQuery = value;
            projectScroll = 0;
        });
        addRenderableWidget(projectSearch);
        addRenderableWidget(timeline.rebuildSearch(font, panels.timeline(), restoreTimelineSearch));
        var node = scene.find(scene.selectedId());
        if (node == null)
        {
            return;
        }
        var panel = panels.properties();
        int index = 0;
        for (var property : node.properties())
        {
            int y = panel.y() + 56 + index++ * 21 - propertyScroll;
            if (y < panel.y() + 52 || y + 19 > panel.bottom() - 16)
            {
                continue;
            }
            if (property.type() == EditorProtocol.PROPERTY_BOOLEAN || property.type() == EditorProtocol.PROPERTY_CHOICE)
            {
                fields.add(new Field(node.id(), property, null, y));
                continue;
            }
            int labelWidth = Math.min(84, panel.width() * 43 / 100);
            EditBox box = new EditBox(font, panel.x() + labelWidth + 13, y + 5, Math.max(16, panel.width() - labelWidth
                    - 24
                    - (property.type() == EditorProtocol.PROPERTY_COLOR
                            || property.key().equals("source") && EditorImageTypes.imageKind(node.kind()) ? 14 : 0)),
                    12, EffectUi.component(property.key()));
            box.setMaxLength(EditorProtocol.MAX_STRING_BYTES / 2);
            box.setValue(PropertyValues.display(property));
            box.setBordered(false);
            box.setTextColor(EffectUi.ACCENT);
            box.setTextShadow(false);
            box.addFormatter((value, position) -> EffectUi.component(value).getVisualOrderText());
            addRenderableWidget(box);
            fields.add(new Field(node.id(), property, box, y));
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public boolean isInGameUi()
    {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float partial)
    {}

    @Override
    public void removed()
    {
        saveWorkspace();
        EditorViewport.clear();
        super.removed();
    }

    @Override
    public void onClose()
    {
        EffectAudioPreview.INSTANCE.stop();
        EditorViewport.clear();
        ArcMenuEditorClient.send(new ClosePacket());
        super.onClose();
    }

    public void serverStateChanged()
    {
        boolean projectFocused = projectSearch != null && projectSearch.isFocused();
        int projectCursor = projectFocused ? projectSearch.getCursorPosition() : 0;
        Field focused = fields.stream().filter(field -> field.box != null && field.box.isFocused()).findFirst()
                .orElse(null);
        String value = focused == null ? "" : focused.box.getValue();
        int cursor = focused == null ? 0 : focused.box.getCursorPosition();
        rebuildWidgets();
        if (projectFocused)
        {
            setFocused(projectSearch);
            projectSearch.setCursorPosition(projectCursor);
            projectSearch.setHighlightPos(projectCursor);
        }
        if (focused != null && focused.target.equals(scene.selectedId()))
        {
            fields.stream().filter(field -> field.box != null && field.property.key().equals(focused.property.key()))
                    .findFirst().ifPresent(field ->
                    {
                        field.box.setValue(value);
                        setFocused(field.box);
                        field.box.setCursorPosition(cursor);
                        field.box.setHighlightPos(cursor);
                    });
        }
        drainDeferredUI();
    }

    public void serverOperationCompleted(byte operation, long id)
    {
        if ((operation == EditorProtocol.OP_MOVE || operation == EditorProtocol.OP_RESIZE) && id == gesture && awaiting)
        {
            awaiting = false;
            if (finalSent)
            {
                finishDrag();
            }
            else if (released || !Objects.equals(pending, sent))
            {
                flushDrag();
            }
        }
        serverStateChanged();
        drainDeferredUI();
    }

    private void drainDeferredUI()
    {
        while (!effect.busy() && !deferredUI.isEmpty())
        {
            deferredUI.removeFirst().run();
        }
    }

    public void serverOperationFailed()
    {
        finishDrag();
        deferredUI.clear();
        deferredPointerEvents.clear();
        deferredPointer = false;
        serverStateChanged();
    }

    @Override
    public void tick()
    {
        super.tick();
        if (gesture != 0 && !awaiting && (released || !Objects.equals(pending, sent)))
        {
            flushDrag();
        }
    }

    public boolean fitView()
    {
        return fitView;
    }

    public double viewZoom()
    {
        return viewZoom;
    }

    public double viewPanX()
    {
        return viewPanX;
    }

    public double viewPanY()
    {
        return viewPanY;
    }

    public void graphView(String mode)
    {
        timeline.graphVisible(!mode.equals("timeline"));
        if (!mode.equals("timeline"))
        {
            timeline.graphMode(mode.equals("speed-graph"));
        }
    }

    @Override
    public void effectDialog(String mode, String target, String property, int frame, EffectProtocol.Key key,
            EffectProtocol.Cue cue)
    {
        openedMenu = "";
        minecraft.setScreen(new EffectDialog(this, scene, mode, target, property, frame, key, cue));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial)
    {
        EffectAudioPreview.INSTANCE.update(true);
        panels = geometry.calculate(width, height);
        EditorViewport.update(panels.viewport());
        controls.clear();
        entries.clear();
        propertyHint = "";
        mask(graphics);
        header(graphics, mouseX, mouseY);
        project(graphics, mouseX, mouseY);
        composition(graphics, mouseX, mouseY);
        preview(graphics, mouseX, mouseY);
        properties(graphics, mouseX, mouseY);
        timeline.draw(graphics, font, panels.timeline(), mouseX, mouseY);
        if (hierarchyDragging && hierarchyDrop != null)
        {
            var rect = hierarchyDrop.bounds();
            if (hierarchyDrop.inside())
            {
                graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(), EffectUi.ACCENT);
            }
            else
            {
                graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), EffectUi.ACCENT);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        menu(graphics, mouseX, mouseY);
        popup.draw(graphics, font, width, height, mouseX, mouseY);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (openedMenu.isEmpty() && !popup.active())
        {
            controls.stream().filter(control -> control.rect.contains(mouseX, mouseY) && !control.hint.isEmpty())
                    .findFirst().ifPresent(control ->
                    {
                        EditorTooltips.draw(graphics, font, EffectUi.component(control.hint), mouseX + 8, mouseY + 13,
                                width, height, 0xF0171717, EffectUi.BORDER, EffectUi.TEXT);
                    });
        }
        if (openedMenu.isEmpty() && !popup.active() && !propertyHint.isEmpty())
        {
            EditorTooltips.draw(graphics, font, EffectUi.component(propertyHint), mouseX + 8, mouseY + 13, width,
                    height, 0xF0171717, EffectUi.BORDER, EffectUi.TEXT);
        }
    }

    private void mask(GuiGraphicsExtractor graphics)
    {
        var viewportPanel = panels.viewport();
        graphics.fill(0, 0, width, viewportPanel.y(), EffectUi.BACKGROUND);
        graphics.fill(0, viewportPanel.bottom(), width, height, EffectUi.BACKGROUND);
        graphics.fill(0, viewportPanel.y(), viewportPanel.x(), viewportPanel.bottom(), EffectUi.BACKGROUND);
        graphics.fill(viewportPanel.right(), viewportPanel.y(), width, viewportPanel.bottom(), EffectUi.BACKGROUND);
        EffectUi.panel(graphics, panels.project());
        EffectUi.panel(graphics, panels.preview());
        EffectUi.panel(graphics, panels.properties());
        var compositionPanel = panels.composition();
        graphics.fill(compositionPanel.x(), compositionPanel.y(), compositionPanel.right(), viewportPanel.y(),
                EffectUi.PANEL);
        graphics.fill(compositionPanel.x(), viewportPanel.bottom(), compositionPanel.right(), compositionPanel.bottom(),
                EffectUi.PANEL);
        graphics.fill(compositionPanel.x(), viewportPanel.y(), viewportPanel.x(), viewportPanel.bottom(),
                EffectUi.PANEL);
        graphics.fill(viewportPanel.right(), viewportPanel.y(), compositionPanel.right(), viewportPanel.bottom(),
                EffectUi.PANEL);
        graphics.outline(compositionPanel.x(), compositionPanel.y(), compositionPanel.width(),
                compositionPanel.height(), EffectUi.BORDER);
    }

    private void header(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        graphics.fill(0, 0, width, 17, EffectUi.HEADER);
        int x = 7;
        for (String name : List.of("file", "edit", "composition", "layer", "animation", "view", "window", "help"))
        {
            String label = EffectUi.label(name);
            int buttonWidth = EffectUi.width(font, label) + 13;
            button(graphics, new EditorLayout.Rect(x, 1, buttonWidth, 15), label, "", () ->
            {
                popup.close();
                openedMenu = openedMenu.equals(name) ? "" : name;
            }, openedMenu.equals(name), mouseX, mouseY);
            x += buttonWidth;
        }
        String id = effect.active() ? effect.snapshot().id() : "";
        String title = "ArcMenu Effect  /  " + id + (effect.active() && effect.snapshot().dirty() ? " *" : "");
        if (width - x > 120)
        {
            EffectUi.text(graphics, font, EffectUi.fit(font, title, width - x - 10), x + 8, 5, EffectUi.MUTED);
        }
        graphics.fill(0, 17, width, 38, EffectUi.PANEL);
        graphics.fill(0, 38, width, 39, EffectUi.BORDER);
        int toolX = 8;
        String[] actions = { "select", "rectangle", "frame", "text", "item", "block", "image", "gif", "line", "group" };
        byte[] kinds = { -1, 1, 2, 3, 4, 5, 7, 9, 8, 0 };
        for (int i = 0; i < actions.length; i++)
        {
            String action = actions[i];
            byte kind = kinds[i];
            button(graphics, new EditorLayout.Rect(toolX, 20, 20, 16), "",
                    kind < 0 ? EffectUi.label("select") : EditorI18n.text("arcmenu_editor.tool." + toolKey(kind)), () ->
                    {
                        tool = action;
                        if (kind >= 0 && kind != 1 && kind != 2 && kind != 3 && kind != 8)
                        {
                            create(kind, "");
                        }
                    }, tool.equals(action), mouseX, mouseY);
            EffectToolIcons.draw(graphics, action, toolX + 4, 22,
                    tool.equals(action) ? EffectUi.ACCENT : EffectUi.TEXT);
            toolX += 24;
        }
        graphics.fill(toolX + 2, 22, toolX + 3, 34, EffectUi.BORDER);
        button(graphics, new EditorLayout.Rect(toolX + 9, 20, 20, 16), "",
                EditorI18n.text("arcmenu_editor.effect.undo"), () -> workspaceCommand("undo"), false, mouseX, mouseY);
        button(graphics, new EditorLayout.Rect(toolX + 32, 20, 20, 16), "",
                EditorI18n.text("arcmenu_editor.effect.redo"), () -> workspaceCommand("redo"), false, mouseX, mouseY);
        EffectToolIcons.draw(graphics, "undo", toolX + 13, 22, EffectUi.TEXT);
        EffectToolIcons.draw(graphics, "redo", toolX + 36, 22, EffectUi.TEXT);
        button(graphics, new EditorLayout.Rect(width - 64, 20, 56, 16), "MCP",
                EditorI18n.text("arcmenu_editor.mcp.title"), () -> minecraft.setScreen(new McpScreen(this)), false,
                mouseX, mouseY);
        button(graphics, new EditorLayout.Rect(width - 180, 20, 54, 16),
                EditorI18n.text("arcmenu_editor.projects.projects"), "", () -> WorkspaceClient.open(this, false), false,
                mouseX, mouseY);
        button(graphics, new EditorLayout.Rect(width - 122, 20, 54, 16),
                EditorI18n.text("arcmenu_editor.projects.market"), "", () -> WorkspaceClient.open(this, true), false,
                mouseX, mouseY);
    }

    private void project(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        var panel = panels.project();
        int x = panel.x() + 8;
        for (String tab : List.of("project", "assets"))
        {
            String label = EffectUi.label(tab);
            int tabWidth = EffectUi.width(font, label) + 12;
            EffectUi.tab(graphics, font, label, x, panel.y() + 6, projectTab.equals(tab));
            control(new EditorLayout.Rect(x - 3, panel.y() + 2, tabWidth, 18), label, "", () ->
            {
                projectTab = tab;
                projectScroll = 0;
            }, false);
            x += tabWidth;
        }
        graphics.fill(panel.x(), panel.y() + 21, panel.right(), panel.y() + 22, EffectUi.BORDER);
        var sceneSnapshot = scene.snapshot();
        var effectSnapshot = effect.snapshot();
        if (sceneSnapshot == null || effectSnapshot == null)
        {
            return;
        }
        if (!hierarchySelection.equals(scene.selectedId()))
        {
            hierarchySelection = scene.selectedId();
            var selected = scene.find(hierarchySelection);
            Set<String> visited = new HashSet<>();
            while (selected != null && !selected.parentId().isEmpty() && visited.add(selected.parentId()))
            {
                scene.expanded().add(selected.parentId());
                selected = scene.find(selected.parentId());
            }
        }
        int y = panel.y() + 30;
        EffectUi.text(graphics, font, EffectUi.fit(font, effectSnapshot.id(), panel.width() - 18), panel.x() + 9, y,
                EffectUi.TEXT);
        EffectUi.mono(graphics, font,
                String.format(Locale.ROOT, "%.0f × %.0f", sceneSnapshot.canvasWidth(), sceneSnapshot.canvasHeight()),
                panel.x() + 9, y + 16, EffectUi.MUTED);
        EffectUi.mono(graphics, font,
                String.format(Locale.ROOT, "%.2f fps / %s", effectSnapshot.fps(), timecode(effectSnapshot.duration())),
                panel.x() + 9, y + 30, EffectUi.MUTED);
        graphics.fill(panel.x() + 6, panel.y() + 77, panel.right() - 6, panel.y() + 96, 0xFF171717);
        graphics.outline(panel.x() + 6, panel.y() + 77, panel.width() - 12, 19,
                projectSearch != null && projectSearch.isFocused() ? EffectUi.ACCENT : EffectUi.BORDER);
        if (projectQuery.isEmpty() && (projectSearch == null || !projectSearch.isFocused()))
        {
            EffectUi.text(graphics, font, EffectUi.label("search_project"), panel.x() + 12, panel.y() + 82,
                    EffectUi.MUTED);
        }
        EffectUi.text(graphics, font, EffectUi.label("name"), panel.x() + 8, y + 75, EffectUi.MUTED);
        List<ProjectEntry> items = new ArrayList<>();
        if (projectTab.equals("project"))
        {
            items.add(new ProjectEntry(effectSnapshot.id(), "composition", null, () ->
            {
                scene.clearSelection();
                rebuildWidgets();
            }));
            for (var node : sceneSnapshot.frontend())
            {
                if (!projectQuery.isEmpty() || !projectHidden(node))
                {
                    items.add(new ProjectEntry(node.id(), "layer", null, () -> select(node.id())));
                }
            }
            for (var template : sceneSnapshot.templates())
            {
                items.add(new ProjectEntry(template.id(), "template", null, () -> ArcMenuEditorClient
                        .send(new InstantiateTemplatePacket(sceneSnapshot.revision(), template.id()))));
            }
        }
        else
        {
            for (String asset : effectSnapshot.assets())
            {
                items.add(new ProjectEntry(asset, asset.endsWith(".nbs") ? "nbs" : "audio", null,
                        () -> effectDialog("cue", "", "", (int) effect.playhead(), null,
                                new EffectProtocol.Cue("cue-" + effectSnapshot.cues().size(), (int) effect.playhead(),
                                        -1, asset.endsWith(".nbs") ? "nbs" : "sound", asset, 1, 1, true))));
            }
            for (var image : sceneSnapshot.images())
            {
                items.add(new ProjectEntry(image.path(), EditorImageTypes.gif(image.path()) ? "gif" : "image", null,
                        () -> create(EditorImageTypes.kind(image.path()), image.path())));
            }
        }
        if (!projectQuery.isEmpty())
        {
            items.removeIf(item -> !item.id.toLowerCase(Locale.ROOT).contains(projectQuery.toLowerCase(Locale.ROOT)));
        }
        visibleProjectLayers = items.stream().filter(item -> item.kind.equals("layer")).map(ProjectEntry::id).toList();
        int start = y + 91;
        int visible = Math.max(1, (panel.bottom() - start - 21) / 19);
        projectScroll = Math.min(projectScroll, Math.max(0, items.size() - visible));
        for (int i = projectScroll; i < Math.min(items.size(), projectScroll + visible); i++)
        {
            var item = items.get(i);
            int rowY = start + (i - projectScroll) * 19;
            var rect = new EditorLayout.Rect(panel.x() + 3, rowY, panel.width() - 6, 18);
            if (scene.selected(item.id) || rect.contains(mouseX, mouseY))
            {
                graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                        scene.selected(item.id) ? 0xFF343C49 : 0xFF303030);
            }
            int color = switch (item.kind)
            {
            case "audio", "nbs" -> 0xFF6EAF8D;
            case "composition" -> 0xFFAF86C7;
            default -> 0xFFCAA96B;
            };
            var node = item.kind.equals("layer") ? scene.find(item.id) : null;
            int depth = node == null ? 0 : depth(node);
            int inset = Math.min(panel.width() / 3, depth * 8);
            if (node != null && node.kind() == EditorProtocol.KIND_GROUP)
            {
                int arrowX = rect.x() + 5 + inset;
                int arrowY = rowY + 7;
                boolean expanded = scene.expanded().contains(node.id());
                if (expanded)
                {
                    EffectUi.line(graphics, arrowX, arrowY, arrowX + 3, arrowY + 3, color);
                    EffectUi.line(graphics, arrowX + 3, arrowY + 3, arrowX + 6, arrowY, color);
                }
                else
                {
                    EffectUi.line(graphics, arrowX + 1, arrowY - 2, arrowX + 4, arrowY + 1, color);
                    EffectUi.line(graphics, arrowX + 4, arrowY + 1, arrowX + 1, arrowY + 4, color);
                }
            }
            else
            {
                graphics.outline(rect.x() + 5 + inset, rowY + 5, 7, 8, color);
            }
            EffectUi.text(graphics, font, EffectUi.fit(font, item.id, panel.width() - 30 - inset),
                    panel.x() + 20 + inset, rowY + 5, EffectUi.TEXT);
            entries.add(new ProjectEntry(item.id, item.kind, rect, item.action));
        }
        button(graphics, new EditorLayout.Rect(panel.x() + 5, panel.bottom() - 19, 19, 15), "+",
                EffectUi.label("add_layer"), () -> openedMenu = "layer", false, mouseX, mouseY);
        button(graphics, new EditorLayout.Rect(panel.x() + 28, panel.bottom() - 19, 19, 15), "×",
                EffectUi.label("delete"), this::delete, false, mouseX, mouseY);
        EffectUi.text(graphics, font,
                EffectUi.fit(font,
                        projectTab.equals("assets") ? EffectUi.label("asset_hint") : EffectUi.label("project_hint"),
                        panel.width() - 57),
                panel.x() + 53, panel.bottom() - 15, EffectUi.MUTED);
    }

    private void composition(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        var panel = panels.composition();
        var viewportPanel = panels.viewport();
        String id = effect.active() ? effect.snapshot().id() : "";
        EffectUi.tab(graphics, font, EffectUi.label("composition") + "  " + id, panel.x() + 9, panel.y() + 6, true);
        graphics.fill(panel.x(), panel.y() + 21, panel.right(), panel.y() + 22, EffectUi.BORDER);
        graphics.outline(viewportPanel.x() - 1, viewportPanel.y() - 1, viewportPanel.width() + 2,
                viewportPanel.height() + 2, 0xFF383838);
        if (showGuides && !EditorScreen.captureClean && scene.snapshot() != null)
        {
            graphics.enableScissor(viewportPanel.x(), viewportPanel.y(), viewportPanel.right(), viewportPanel.bottom());
            for (String idSelected : scene.selectedIds())
            {
                var node = scene.find(idSelected);
                if (node == null)
                {
                    continue;
                }
                var calibration = nodeCalibration(node.kind());
                int left = sx(calibration.proxyX(node.x(), node.width()) - calibration.proxyWidth(node.width()) / 2);
                int right = sx(calibration.proxyX(node.x(), node.width()) + calibration.proxyWidth(node.width()) / 2);
                int top = sy(calibration.proxyY(node.y(), node.height()) + calibration.proxyHeight(node.height()) / 2);
                int bottom = sy(
                        calibration.proxyY(node.y(), node.height()) - calibration.proxyHeight(node.height()) / 2);
                graphics.outline(left, top, Math.max(1, right - left), Math.max(1, bottom - top), EffectUi.ACCENT);
                for (int[] point : new int[][] { { left, top }, { right, top }, { left, bottom }, { right, bottom } })
                {
                    graphics.fill(point[0] - 2, point[1] - 2, point[0] + 3, point[1] + 3, EffectUi.ACCENT);
                }
                int centerX = sx(node.x());
                int centerY = sy(node.y());
                EffectUi.line(graphics, centerX - 4, centerY, centerX + 4, centerY, EffectUi.ACCENT);
                EffectUi.line(graphics, centerX, centerY - 4, centerX, centerY + 4, EffectUi.ACCENT);
            }
            graphics.disableScissor();
        }
        if (safeGuides && !EditorScreen.captureClean)
        {
            graphics.outline(viewportPanel.x() + viewportPanel.width() / 20,
                    viewportPanel.y() + viewportPanel.height() / 20, viewportPanel.width() * 9 / 10,
                    viewportPanel.height() * 9 / 10, 0x9970A5A0);
            graphics.outline(viewportPanel.x() + viewportPanel.width() / 10,
                    viewportPanel.y() + viewportPanel.height() / 10, viewportPanel.width() * 4 / 5,
                    viewportPanel.height() * 4 / 5, 0x9970A5A0);
        }
        if (marquee || drawShape)
        {
            int marqueeLeft = (int) Math.min(marqueeX, marqueeEndX);
            int marqueeTop = (int) Math.min(marqueeY, marqueeEndY);
            int marqueeWidth = (int) Math.abs(marqueeEndX - marqueeX);
            int marqueeHeight = (int) Math.abs(marqueeEndY - marqueeY);
            graphics.fill(marqueeLeft, marqueeTop, marqueeLeft + marqueeWidth, marqueeTop + marqueeHeight, 0x202D8CEB);
            graphics.outline(marqueeLeft, marqueeTop, marqueeWidth, marqueeHeight, EffectUi.ACCENT);
        }
        if (!draggedAsset.isEmpty())
        {
            EffectUi.text(graphics, font, EffectUi.fit(font, draggedAsset, 160), mouseX + 8, mouseY + 8,
                    EffectUi.ACCENT);
        }
        int y = panel.bottom() - 17;
        String viewLabel = fitView ? EditorI18n.text("arcmenu_editor.workspace.fit_zoom", previewZoom())
                : EffectUi.label("playback_view");
        int viewButtonWidth = Math.max(72, Math.min(120, EffectUi.width(font, viewLabel) + 12));
        button(graphics, new EditorLayout.Rect(panel.x() + 5, y, viewButtonWidth, 15), viewLabel,
                EffectUi.label("fit_view"), () ->
                {
                    fitView = !fitView;
                    viewZoom = 1;
                    viewPanX = viewPanY = 0;
                }, fitView, mouseX, mouseY);
        EffectUi.mono(graphics, font, timecode((int) effect.playhead()), panel.x() + viewButtonWidth + 14, y + 4,
                EffectUi.ACCENT);
        button(graphics, new EditorLayout.Rect(panel.right() - 47, y, 18, 15), "#", EffectUi.label("guides"),
                () -> showGuides = !showGuides, showGuides, mouseX, mouseY);
        button(graphics, new EditorLayout.Rect(panel.right() - 25, y, 18, 15), "…",
                EditorI18n.text("arcmenu_editor.effect.settings"),
                () -> effectDialog("settings", "", "", 0, null, null), false, mouseX, mouseY);
    }

    private void preview(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        var panel = panels.preview();
        title(graphics, panel, "preview");
        var audio = EffectAudioPreview.INSTANCE;
        int audioX = panel.right() - 24;
        int audioY = panel.y() + 3;
        button(graphics, new EditorLayout.Rect(audioX, audioY, 18, 14), "",
                EffectUi.label(audio.enabled() ? "audio_on" : "audio_off") + "  " + EffectUi.label("audio_hint"),
                () -> audio.enabled(!audio.enabled()), audio.enabled(), mouseX, mouseY);
        int audioColor = audio.enabled() ? 0xFFFFFFFF : EffectUi.MUTED;
        graphics.fill(audioX + 4, audioY + 5, audioX + 7, audioY + 9, audioColor);
        graphics.fill(audioX + 7, audioY + 3, audioX + 9, audioY + 11, audioColor);
        EffectUi.line(graphics, audioX + 11, audioY + 4, audioX + 13, audioY + 7, audioColor);
        EffectUi.line(graphics, audioX + 13, audioY + 7, audioX + 11, audioY + 10, audioColor);
        if (!audio.enabled())
        {
            EffectUi.line(graphics, audioX + 3, audioY + 12, audioX + 14, audioY + 2, 0xFFD5A458);
        }
        int y = panel.y() + 29;
        int x = panel.x() + Math.max(6, (panel.width() - 102) / 2);
        String[] glyphs = { "|◀", "◀", effect.active() && effect.snapshot().playing() ? "Ⅱ" : "▶", "▶|", "■" };
        Runnable[] actions = { () -> effect.seek(effect.snapshot().workStart()),
                () -> effect.seek(Math.max(0, (int) effect.playhead() - 1)), () -> effect.action("play"),
                () -> effect.seek(Math.min(effect.snapshot().duration(), (int) effect.playhead() + 1)),
                () -> effect.action("stop") };
        for (int i = 0; i < glyphs.length; i++)
        {
            button(graphics, new EditorLayout.Rect(x + i * 21, y, 19, 18), glyphs[i], "", actions[i], false, mouseX,
                    mouseY);
        }
        if (!effect.active())
        {
            return;
        }
        var effectSnapshot = effect.snapshot();
        EffectUi.text(graphics, font, EffectUi.label("shortcut") + "  " + EditorI18n.text("key.keyboard.space"),
                panel.x() + 8, y + 29, EffectUi.MUTED);
        EffectUi.text(graphics, font,
                EffectUi.label("range") + "  " + effectSnapshot.workStart() + " – " + effectSnapshot.workEnd(),
                panel.x() + 8, y + 45, EffectUi.TEXT);
        if (y + 81 < panel.bottom())
        {
            button(graphics, new EditorLayout.Rect(panel.x() + 7, y + 64, panel.width() - 14, 16),
                    EffectUi.label(effectSnapshot.loop() ? "loop_on" : "loop_off"), "",
                    () -> effect.submit("settings", "", Boolean.toString(!effectSnapshot.loop()),
                            effectSnapshot.workStart(), effectSnapshot.duration(),
                            Integer.toString(effectSnapshot.workEnd()), "linear", effectSnapshot.fps(), 0, 0, 0),
                    effectSnapshot.loop(), mouseX, mouseY);
        }
        String audioStatus = EffectAudioPreview.INSTANCE.statusKey();
        int audioStatusY = y + (y + 81 < panel.bottom() ? 85 : 61);
        if (!audioStatus.isEmpty() && audioStatusY + 9 <= panel.bottom())
        {
            EffectUi.text(graphics, font, EffectUi.fit(font, EffectUi.label(audioStatus), panel.width() - 16),
                    panel.x() + 8, audioStatusY, audioStatus.equals("audio_missing") ? 0xFFD5A458 : EffectUi.MUTED);
        }
    }

    private void properties(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        var panel = panels.properties();
        title(graphics, panel, "properties");
        var node = scene.find(scene.selectedId());
        EffectUi.text(graphics, font,
                EffectUi.fit(font, node == null ? EffectUi.label("no_selection") : node.id(), panel.width() - 18),
                panel.x() + 8, panel.y() + 29, EffectUi.TEXT);
        if (node == null)
        {
            if (scene.snapshot() != null)
            {
                EffectUi.text(graphics, font, EffectUi.label("composition"), panel.x() + 8, panel.y() + 56,
                        EffectUi.MUTED);
                EffectUi.mono(graphics, font, timecode((int) effect.playhead()), panel.x() + 8, panel.y() + 74,
                        EffectUi.ACCENT);
                EffectUi.text(graphics, font, EffectUi.label("select_hint"), panel.x() + 8, panel.y() + 97,
                        EffectUi.MUTED);
            }
            return;
        }
        EffectUi.text(graphics, font, EditorI18n.text("arcmenu_editor.tool." + toolKey(node.kind())), panel.x() + 8,
                panel.y() + 43, EffectUi.MUTED);
        int labelWidth = Math.min(84, panel.width() * 43 / 100);
        for (var field : fields)
        {
            var property = field.property;
            String label = EditorI18n.property(property.key());
            if (mouseX >= panel.x() && mouseX < panel.x() + labelWidth + 8 && mouseY >= field.y
                    && mouseY < field.y + 21)
            {
                propertyHint = EditorImageTypes.hint(node.kind(), property.key());
                if (propertyHint.isEmpty())
                {
                    propertyHint = EditorI18n.propertyHint(property.key());
                }
            }
            if (label.startsWith("arcmenu_editor."))
            {
                label = property.key();
            }
            EffectUi.text(graphics, font, EffectUi.fit(font, label, labelWidth - 8), panel.x() + 17, field.y + 6,
                    EffectUi.MUTED);
            if (EffectPropertyRules.animated(node.kind(), property.key()))
            {
                boolean animated = effect.active() && effect.snapshot().tracks().stream()
                        .anyMatch(track -> track.target().equals(node.id()) && track.property().equals(property.key()));
                int color = animated ? EffectUi.ACCENT : 0xFF757575;
                graphics.outline(panel.x() + 5, field.y + 7, 5, 6, color);
                control(new EditorLayout.Rect(panel.x() + 2, field.y, 12, 19), "", EffectUi.label("add_key"),
                        () -> togglePropertyKey(node.id(), property.key()), false);
            }
            int valueX = panel.x() + labelWidth + 8;
            graphics.fill(valueX, field.y + 2, panel.right() - 6, field.y + 19, 0xFF171717);
            if (property.type() == EditorProtocol.PROPERTY_COLOR)
            {
                try
                {
                    graphics.fill(panel.right() - 18, field.y + 5, panel.right() - 9, field.y + 15,
                            EffectColorPicker.parse(property.value()));
                }
                catch (IllegalArgumentException ignored)
                {}
                control(new EditorLayout.Rect(panel.right() - 21, field.y + 2, 16, 17), "",
                        EffectUi.label("color_picker"), () -> colorPicker(field), false);
            }
            if (property.key().equals("source") && EditorImageTypes.imageKind(node.kind()))
            {
                EffectToolIcons.draw(graphics, toolKey(node.kind()), panel.right() - 18, field.y + 4, EffectUi.ACCENT);
                control(new EditorLayout.Rect(panel.right() - 21, field.y + 2, 16, 17), "",
                        EditorI18n.text("arcmenu_editor.images.choose_source"), () -> sourcePicker(field), false);
            }
            if (field.box == null)
            {
                EffectUi.text(graphics, font, EffectUi.fit(font, property.value(), panel.right() - valueX - 12),
                        valueX + 5, field.y + 6, EffectUi.ACCENT);
                control(new EditorLayout.Rect(valueX, field.y + 2, panel.right() - valueX - 6, 17), "", "", () ->
                {
                    String value = property.type() == EditorProtocol.PROPERTY_BOOLEAN
                            ? Boolean.toString(!Boolean.parseBoolean(property.value()))
                            : (property.choices().isEmpty() ? property.value()
                                    : property.choices()
                                            .get((Math.max(0, property.choices().indexOf(property.value())) + 1)
                                                    % property.choices().size()));
                    property(field, value);
                }, false);
            }
        }
        EffectUi.text(graphics, font, EffectUi.fit(font, scene.status(), panel.width() - 14), panel.x() + 7,
                panel.bottom() - 12, scene.errorStatus() ? 0xFFE58D8D : EffectUi.MUTED);
    }

    private void title(GuiGraphicsExtractor graphics, EditorLayout.Rect panel, String key)
    {
        EffectUi.tab(graphics, font, EffectUi.label(key), panel.x() + 8, panel.y() + 6, true);
        graphics.fill(panel.x(), panel.y() + 21, panel.right(), panel.y() + 22, EffectUi.BORDER);
    }

    private void control(EditorLayout.Rect rect, String label, String hint, Runnable action, boolean active)
    {
        controls.add(new Control(rect, label, hint, action, active));
    }

    private void button(GuiGraphicsExtractor graphics, EditorLayout.Rect rect, String label, String hint,
            Runnable action, boolean active, int mouseX, int mouseY)
    {
        if (active || rect.contains(mouseX, mouseY))
        {
            graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), active ? 0xFF33465C : 0xFF383838);
        }
        EffectUi.text(graphics, font, EffectUi.fit(font, label, rect.width() - 3),
                rect.x() + Math.max(2, (rect.width() - EffectUi.width(font, label)) / 2), rect.y() + 4,
                active ? EffectUi.ACCENT : EffectUi.TEXT);
        control(rect, label, hint, action, active);
    }

    private EffectPopup.Item commandItem(String id)
    {
        String label = EffectCommands.list().stream().filter(command -> command.id().equals(id))
                .map(command -> EffectUi.label(command.label())).findFirst()
                .orElse(EffectUi.label(id.replace('-', '_')));
        String shortcut = EffectCommands.shortcut(id);
        if (shortcut.isEmpty())
        {
            shortcut = EffectTimeline.shortcuts().getOrDefault(id, "");
        }
        return new EffectPopup.Item(label, shortcut, () -> workspaceCommand(id), commandEnabled(id));
    }

    private List<EffectPopup.Item> commandItems(String... ids)
    {
        return Arrays.stream(ids).map(this::commandItem).toList();
    }

    private void menu(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        if (openedMenu.isEmpty())
        {
            return;
        }
        if (!popup.active())
        {
            List<EffectPopup.Item> items = switch (openedMenu)
            {
            case "file" -> commandItems("save", "mcp", "close");
            case "edit" -> commandItems("undo", "redo", "cut", "copy", "paste", "duplicate", "split", "delete",
                    "select-all", "deselect", "command-search");
            case "composition" -> commandItems("settings", "first-frame", "last-frame", "work-start", "work-end");
            case "layer" -> layerItems();
            case "animation" -> commandItems("add-key", "cue", "marker", "auto-key", "ease", "ease-in", "ease-out",
                    "linear", "hold", "auto-bezier", "previous-key", "next-key");
            case "view" -> commandItems("fit", "actual-size", "guides", "safe-guides", "timeline", "value-graph",
                    "speed-graph", "zoom-in", "zoom-out", "fit-graph");
            case "window" -> commandItems("assets", "expand-all", "collapse-all", "reset-workspace");
            default -> commandItems("shortcuts", "command-search", "mcp");
            };
            var anchor = controls.stream().filter(control -> control.label.equals(EffectUi.label(openedMenu)))
                    .findFirst().orElse(null);
            popup.open(anchor == null ? 8 : anchor.rect.x(), 18, items);
        }
    }

    @Override
    public void timelineContextMenu(double x, double y, List<MenuItem> items)
    {
        openedMenu = "";
        popup.open((int) x, (int) y,
                items.stream().map(
                        i -> new EffectPopup.Item(EditorI18n.text(i.translationKey()), "", i.action(), i.enabled()))
                        .toList());
    }

    @Override
    public void timelineCommand(String action)
    {
        workspaceCommand(action);
    }

    @Override
    public void timelineSearchFocus(EditBox field)
    {
        setFocused(field);
    }

    private List<EffectPopup.Item> layerItems()
    {
        return commandItems(EffectCommands.layerMenu().toArray(String[]::new));
    }

    private void layerMenu(double x, double y)
    {
        openedMenu = "";
        popup.open((int) x, (int) y, layerItems());
    }

    @Override
    public void timelineLayerMenu(double x, double y)
    {
        layerMenu(x, y);
    }

    @Override
    public void timelineEmptyMenu(double x, double y)
    {
        emptyMenu(x, y);
    }

    @Override
    public void beginLayerDrag(String id, double x, double y, boolean exclusiveClick)
    {
        hierarchyDrag = EffectHierarchy.roots(scene, scene.selectedIds());
        hierarchyStartX = x;
        hierarchyStartY = y;
        hierarchyDragging = false;
        hierarchyDrop = null;
        hierarchyClick = exclusiveClick ? id : "";
    }

    private EffectHierarchy.Drop hierarchyDropAt(double x, double y)
    {
        if (timeline.hierarchyArea(x, y))
        {
            return timeline.hierarchyDrop(hierarchyDrag, x, y);
        }
        var panel = panels.project();
        if (!projectTab.equals("project") || !panel.contains(x, y) || y < panel.y() + 121 || y >= panel.bottom() - 16)
        {
            return null;
        }
        for (var entry : entries)
        {
            if (entry.rect.contains(x, y))
            {
                if (entry.kind.equals("layer"))
                {
                    return EffectHierarchy.drop(scene, hierarchyDrag, new EffectHierarchy.Row(entry.id, entry.rect), y);
                }
                if (!entry.kind.equals("composition"))
                {
                    return null;
                }
            }
        }
        return EffectHierarchy.canMove(scene, hierarchyDrag, "", "")
                ? new EffectHierarchy.Drop("", "", new EditorLayout.Rect(panel.x() + 3, (int) y, panel.width() - 6, 2),
                        false)
                : null;
    }

    private void cancelHierarchyDrag()
    {
        hierarchyDrag = List.of();
        hierarchyDrop = null;
        hierarchyDragging = false;
        hierarchyClick = "";
    }

    private void moveLayers(List<String> ids, String parent, String before)
    {
        if (!EffectHierarchy.canMove(scene, ids, parent, before))
        {
            return;
        }
        scene.expanded().add(parent);
        if (ids.stream().allMatch(id -> scene.find(id).parentId().equals(parent)))
        {
            ArcMenuEditorClient
                    .send(new ReorderPacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, ids, before));
        }
        else
        {
            ArcMenuEditorClient.send(new ReparentPacket(scene.snapshot().revision(), ids, parent, before));
        }
    }

    private void chooseParent()
    {
        var ids = EffectHierarchy.roots(scene, scene.selectedIds());
        var choices = new ArrayList<EffectPopup.Item>();
        for (var node : scene.nodes())
        {
            if (node.kind() == EditorProtocol.KIND_GROUP && EffectHierarchy.canMove(scene, ids, node.id(), ""))
            {
                choices.add(new EffectPopup.Item(node.id(), "", () -> moveLayers(ids, node.id(), "")));
            }
        }
        openedMenu = "";
        popup.open((int) lastMouseX, (int) lastMouseY, choices);
    }

    private void emptyMenu(double x, double y)
    {
        openedMenu = "";
        popup.open((int) x, (int) y, commandItems("new-text", "new-rectangle", "new-gif", "new-group", "paste",
                "settings", "cue", "marker", "expand-all", "collapse-all"));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
    {
        if (deferredPointer)
        {
            queuePointer(new DeferredInput(0, event, doubleClick, 0, 0));
            return true;
        }
        if (panels == null)
        {
            return super.mouseClicked(event, doubleClick);
        }
        if (popup.active())
        {
            openedMenu = "";
            return popup.click(event.x(), event.y(), event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
        {
            for (int controlIndex = controls.size() - 1; controlIndex >= 0; controlIndex--)
            {
                var hitControl = controls.get(controlIndex);
                if (hitControl.rect.contains(event.x(), event.y()))
                {
                    commitFocused();
                    if (effect.busy())
                    {
                        deferredUI.add(hitControl.action);
                    }
                    else
                    {
                        hitControl.action.run();
                    }
                    return true;
                }
            }
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && projectSearch != null
                && new EditorLayout.Rect(panels.project().x() + 6, panels.project().y() + 77,
                        panels.project().width() - 12, 19).contains(event.x(), event.y()))
        {
            if (getFocused() != projectSearch)
            {
                commitFocused();
            }
            setFocused(projectSearch);
            timelineFocused = false;
            if (!projectSearch.mouseClicked(event, doubleClick))
            {
                projectSearch.moveCursorToEnd(false);
            }
            return true;
        }
        if (panels.properties().contains(event.x(), event.y()))
        {
            for (var field : fields)
            {
                if (event.y() >= field.y && event.y() < field.y + 21)
                {
                    if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                    {
                        commitFocused();
                        var node = scene.find(field.target);
                        boolean animatable = node != null
                                && EffectPropertyRules.animated(node.kind(), field.property.key());
                        boolean tracked = effect.snapshot().tracks().stream()
                                .anyMatch(track -> track.target().equals(field.target)
                                        && track.property().equals(field.property.key()));
                        popup.open((int) event.x(), (int) event.y(), List.of(
                                new EffectPopup.Item(EffectUi.label("add_key"), "",
                                        () -> togglePropertyKey(field.target, field.property.key()), animatable),
                                new EffectPopup.Item(EffectUi.label("key_settings"), "",
                                        () -> effectDialog("key", field.target, field.property.key(),
                                                (int) effect.playhead(), keyAt(field.target, field.property.key()),
                                                null),
                                        animatable),
                                new EffectPopup.Item(
                                        EffectUi.label("remove_animation"), "", () -> effect.submit("delete-track",
                                                field.target, field.property.key(), 0, 0, "", "linear", 0, 0, 0, 0),
                                        tracked)));
                        return true;
                    }
                    int valueX = panels.properties().x() + Math.min(84, panels.properties().width() * 43 / 100) + 8;
                    if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && field.box != null && event.x() >= valueX
                            && event.x() < panels.properties().right() - 6 && event.y() >= field.y + 2
                            && event.y() < field.y + 19)
                    {
                        if (getFocused() != field.box)
                        {
                            commitFocused();
                        }
                        setFocused(field.box);
                        timelineFocused = false;
                        if (!field.box.mouseClicked(event, doubleClick))
                        {
                            field.box.moveCursorToEnd(false);
                        }
                        return true;
                    }
                    if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                            && event.x() < panels.properties().x()
                                    + Math.min(84, panels.properties().width() * 43 / 100) + 8
                            && PropertyValues.isNumeric(field.property))
                    {
                        try
                        {
                            commitFocused();
                            scrubValue = Double
                                    .parseDouble(field.box == null ? field.property.value() : field.box.getValue());
                            scrubStartX = event.x();
                            scrubField = field;
                            setFocused(null);
                            return true;
                        }
                        catch (NumberFormatException ignored)
                        {}
                    }
                }
            }
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
        {
            if (Math.abs(event.x() - panels.composition().x() + 2) < 4 && event.y() > 40
                    && event.y() < panels.timeline().y())
            {
                divider = 1;
            }
            else if (Math.abs(event.x() - panels.preview().x() + 2) < 4 && event.y() > 40)
            {
                divider = 2;
            }
            else if (Math.abs(event.y() - panels.timeline().y()) < 3 && event.x() < panels.timeline().right())
            {
                divider = 3;
            }
            if (divider != 0)
            {
                return true;
            }
        }
        timelineFocused = panels.timeline().contains(event.x(), event.y());
        if (timelineFocused)
        {
            commitFocused();
            setFocused(null);
            if (effect.sceneBusy())
            {
                deferPointer(event, doubleClick);
                return true;
            }
        }
        if (timeline.click(event, doubleClick))
        {
            return true;
        }
        for (var entry : entries)
        {
            if (entry.rect.contains(event.x(), event.y()))
            {
                commitFocused();
                setFocused(null);
                if (entry.kind.equals("layer"))
                {
                    var node = scene.find(entry.id);
                    if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                    {
                        if (!scene.selected(entry.id))
                        {
                            select(entry.id);
                        }
                        layerMenu(event.x(), event.y());
                        return true;
                    }
                    if (node != null && node.kind() == EditorProtocol.KIND_GROUP && event.x() < panels.project().x()
                            + 19 + Math.min(panels.project().width() / 3, depth(node) * 8))
                    {
                        if (!scene.expanded().remove(node.id()))
                        {
                            scene.expanded().add(node.id());
                        }
                        return true;
                    }
                    if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    {
                        return true;
                    }
                    EffectHierarchy.select(scene, visibleProjectLayers, entry.id, false, event.hasShiftDown(),
                            event.hasControlDown());
                    rebuildWidgets();
                    if (doubleClick)
                    {
                        workspaceCommand("rename");
                    }
                    else if (scene.selected(entry.id))
                    {
                        beginLayerDrag(entry.id, event.x(), event.y(),
                                !event.hasShiftDown() && !event.hasControlDown());
                    }
                }
                else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                {
                    var options = new ArrayList<EffectPopup.Item>();
                    options.add(new EffectPopup.Item(
                            EffectUi.label(entry.kind.equals("composition") ? "settings" : "add_asset"), "",
                            entry.kind.equals("composition") ? () -> workspaceCommand("settings") : entry.action));
                    if (entry.kind.equals("template"))
                    {
                        options.add(new EffectPopup.Item(EffectUi.label("delete"), "", () -> ArcMenuEditorClient
                                .send(new DeleteTemplatePacket(scene.snapshot().revision(), entry.id))));
                    }
                    popup.open((int) event.x(), (int) event.y(), options);
                }
                else if (doubleClick)
                {
                    entry.action.run();
                }
                else if (!entry.kind.equals("composition"))
                {
                    draggedAsset = entry.id;
                }
                return true;
            }
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && panels.project().contains(event.x(), event.y()))
        {
            emptyMenu(event.x(), event.y());
            return true;
        }
        if (panels.viewport().contains(event.x(), event.y()) && scene.snapshot() != null)
        {
            commitFocused();
            setFocused(null);
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || tool.equals("hand"))
            {
                panning = true;
                lastMouseX = event.x();
                lastMouseY = event.y();
                return true;
            }
            if (tool.equals("zoom") && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
            {
                viewZoom = Math.min(8, viewZoom * (shift() ? 1 / 1.25 : 1.25));
                viewZoom = Math.max(1, viewZoom);
                return true;
            }
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && List.of("rectangle", "frame", "text", "line").contains(tool))
            {
                drawShape = true;
                marqueeX = marqueeEndX = event.x();
                marqueeY = marqueeEndY = event.y();
                return true;
            }
            var node = hit(event.x(), event.y());
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            {
                if (node != null && !scene.selected(node.id()))
                {
                    select(node.id());
                }
                if (node == null)
                {
                    emptyMenu(event.x(), event.y());
                }
                else
                {
                    layerMenu(event.x(), event.y());
                }
                return true;
            }
            if (node == null)
            {
                if (!shift())
                {
                    scene.clearSelection();
                }
                marquee = true;
                marqueeX = marqueeEndX = event.x();
                marqueeY = marqueeEndY = event.y();
                rebuildWidgets();
                return true;
            }
            if (shift() || control())
            {
                scene.toggleSelection(node.id());
                rebuildWidgets();
                return true;
            }
            if (!scene.selected(node.id()))
            {
                scene.selectOnly(node.id());
            }
            rebuildWidgets();
            if (scene.locked(node.id()) || node.locked())
            {
                return true;
            }
            if (tool.equals("rotate"))
            {
                rotationTarget = node.id();
                rotationOrigin = Math.atan2(menuY(event.y()) - node.y(), menuX(event.x()) - node.x());
                rotationBase = rotationValue = EffectTransforms.number(node, "rotation.z", 0);
                return true;
            }
            dragging = node.id();
            var calibration = nodeCalibration(node.kind());
            resizing = Math
                    .abs(event.x() - sx(
                            calibration.proxyX(node.x(), node.width()) + calibration.proxyWidth(node.width()) / 2)) < 7
                    && Math.abs(event.y() - sy(calibration.proxyY(node.y(), node.height())
                            - calibration.proxyHeight(node.height()) / 2)) < 7;
            grabX = menuX(event.x()) - node.x();
            grabY = menuY(event.y()) - node.y();
            gesture = nextGesture++;
            released = false;
            awaiting = false;
            finalSent = false;
            pending = null;
            sent = null;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy)
    {
        if (deferredPointer)
        {
            queuePointer(new DeferredInput(1, event, false, dx, dy));
            return true;
        }
        if (!hierarchyDrag.isEmpty())
        {
            hierarchyDragging |= Math.hypot(event.x() - hierarchyStartX, event.y() - hierarchyStartY) >= 4;
            if (hierarchyDragging)
            {
                hierarchyDrop = hierarchyDropAt(event.x(), event.y());
            }
            return true;
        }
        if (divider != 0)
        {
            if (divider == 1)
            {
                geometry.left = (int) event.x();
            }
            if (divider == 2)
            {
                geometry.right = width - (int) event.x();
            }
            if (divider == 3)
            {
                geometry.bottom = height - (int) event.y();
            }
            rebuildWidgets();
            return true;
        }
        if (!rotationTarget.isEmpty())
        {
            var node = scene.find(rotationTarget);
            if (node != null)
            {
                double angle = Math.atan2(menuY(event.y()) - node.y(), menuX(event.x()) - node.x());
                rotationValue = rotationBase + Math
                        .toDegrees(Math.atan2(Math.sin(angle - rotationOrigin), Math.cos(angle - rotationOrigin)));
                if (shift())
                {
                    rotationValue = Math.rint(rotationValue / 15) * 15;
                }
                scene.status(EditorI18n.text("arcmenu_editor.workspace.rotation_preview", formatNumber(rotationValue)));
            }
            return true;
        }
        if (scrubField != null)
        {
            double step = shift() ? .1 : control() ? 10 : 1;
            double value = scrubValue + (event.x() - scrubStartX) * step;
            if (scrubField.property.type() == EditorProtocol.PROPERTY_INTEGER)
            {
                value = Math.rint(value);
            }
            if (scrubField.box != null)
            {
                scrubField.box.setValue(formatNumber(value));
            }
            return true;
        }
        if (panning)
        {
            viewPanX -= (event.x() - lastMouseX) / Math.max(1, panels.viewport().width()) / viewZoom;
            viewPanY += (event.y() - lastMouseY) / Math.max(1, panels.viewport().height()) / viewZoom;
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        if (marquee || drawShape)
        {
            marqueeEndX = event.x();
            marqueeEndY = event.y();
            return true;
        }
        if (!draggedAsset.isEmpty())
        {
            return true;
        }
        if (timeline.drag(event))
        {
            return true;
        }
        if (gesture != 0)
        {
            pending = pointer(event.x(), event.y());
            flushDrag();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event)
    {
        if (deferredPointer)
        {
            queuePointer(new DeferredInput(2, event, false, 0, 0));
            return true;
        }
        if (!hierarchyDrag.isEmpty())
        {
            if (hierarchyDragging)
            {
                var drop = hierarchyDropAt(event.x(), event.y());
                if (drop != null)
                {
                    moveLayers(hierarchyDrag, drop.parent(), drop.before());
                }
            }
            else if (!hierarchyClick.isEmpty())
            {
                scene.selectOnly(hierarchyClick);
                rebuildWidgets();
            }
            cancelHierarchyDrag();
            return true;
        }
        if (divider != 0)
        {
            divider = 0;
            return true;
        }
        if (!rotationTarget.isEmpty())
        {
            String id = rotationTarget;
            rotationTarget = "";
            var root = new JsonObject();
            var changes = new JsonArray();
            var change = new JsonObject();
            change.addProperty("target", id);
            change.addProperty("property", "rotation.z");
            change.addProperty("value", formatNumber(rotationValue));
            changes.add(change);
            root.add("changes", changes);
            effect.submit("properties", "", "", (int) effect.playhead(), 0, root.toString(), "linear", 0, 0, 0, 0);
            return true;
        }
        if (scrubField != null)
        {
            var field = scrubField;
            scrubField = null;
            if (field.box != null)
            {
                property(field, field.box.getValue());
            }
            return true;
        }
        if (panning)
        {
            panning = false;
            saveWorkspace();
            return true;
        }
        if (drawShape)
        {
            drawShape = false;
            createShape();
            return true;
        }
        if (marquee)
        {
            marquee = false;
            double left = Math.min(marqueeX, marqueeEndX);
            double right = Math.max(marqueeX, marqueeEndX);
            double top = Math.min(marqueeY, marqueeEndY);
            double bottom = Math.max(marqueeY, marqueeEndY);
            for (var node : scene.nodes())
            {
                if (node.visible() && !scene.locked(node.id()) && !node.locked() && sx(node.x()) >= left
                        && sx(node.x()) <= right && sy(node.y()) >= top && sy(node.y()) <= bottom
                        && !scene.selected(node.id()))
                {
                    scene.toggleSelection(node.id());
                }
            }
            rebuildWidgets();
            return true;
        }
        if (!draggedAsset.isEmpty())
        {
            String asset = draggedAsset;
            draggedAsset = "";
            if (EditorImageTypes.image(asset))
            {
                if (panels.viewport().contains(event.x(), event.y())
                        || panels.timeline().contains(event.x(), event.y()))
                {
                    create(EditorImageTypes.kind(asset), asset);
                }
            }
            else if (panels.timeline().contains(event.x(), event.y())
                    || panels.viewport().contains(event.x(), event.y()))
            {
                effectDialog("cue", "", "",
                        panels.timeline().contains(event.x(), event.y()) ? timeline.frameAt(event.x())
                                : (int) effect.playhead(),
                        null,
                        new EffectProtocol.Cue("cue-" + System.currentTimeMillis(),
                                panels.timeline().contains(event.x(), event.y()) ? timeline.frameAt(event.x())
                                        : (int) effect.playhead(),
                                -1, asset.endsWith(".nbs") ? "nbs" : "sound", asset, 1, 1, true));
            }
            return true;
        }
        if (timeline.release(event))
        {
            return true;
        }
        if (gesture != 0)
        {
            released = true;
            if (pending == null)
            {
                finishDrag();
            }
            else
            {
                flushDrag();
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical)
    {
        if (popup.wheel(vertical))
        {
            return true;
        }
        if (panels.viewport().contains(x, y) && control())
        {
            viewZoom = Math.max(1, Math.min(8, viewZoom * Math.pow(1.15, vertical)));
            return true;
        }
        if (timeline.scroll(x, y, vertical, control(), shift()))
        {
            return true;
        }
        if (panels.properties().contains(x, y))
        {
            commitFocused();
            propertyScroll = Math.max(0,
                    Math.min(
                            Math.max(0,
                                    scene.find(scene.selectedId()) == null ? 0
                                            : scene.find(scene.selectedId()).properties().size() * 21
                                                    - panels.properties().height() + 75),
                            propertyScroll - (int) (vertical * 21)));
            rebuildWidgets();
            return true;
        }
        if (panels.project().contains(x, y))
        {
            projectScroll = Math.max(0, projectScroll - (int) vertical);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event)
    {
        if (event.isEscape() && !hierarchyDrag.isEmpty())
        {
            cancelHierarchyDrag();
            return true;
        }
        if (event.isEscape() && deferredPointer)
        {
            deferredPointer = false;
            deferredPointerEvents.clear();
            deferredUI.clear();
            return true;
        }
        if (popup.active())
        {
            if (event.isEscape() || event.isConfirmation())
            {
                openedMenu = "";
            }
            return popup.key(event.key() == GLFW.GLFW_KEY_DOWN ? 1 : event.key() == GLFW.GLFW_KEY_UP ? -1 : 0,
                    event.isConfirmation(), event.isEscape());
        }
        if (projectSearch != null && projectSearch.isFocused())
        {
            if (event.isEscape())
            {
                setFocused(null);
                return true;
            }
            return super.keyPressed(event);
        }
        for (var field : fields)
        {
            if (field.box != null && field.box.isFocused())
            {
                if (event.isConfirmation())
                {
                    property(field, PropertyValues.submitted(field.property, field.box.getValue()));
                    setFocused(null);
                    return true;
                }
                if (event.isEscape())
                {
                    field.box.setValue(PropertyValues.display(field.property));
                    setFocused(null);
                    return true;
                }
                return super.keyPressed(event);
            }
        }
        if (timeline.searchFocused())
        {
            return timeline.key(event) || super.keyPressed(event);
        }
        if (event.isEscape() && (drawShape || marquee || panning || scrubField != null || !rotationTarget.isEmpty()
                || !draggedAsset.isEmpty()))
        {
            drawShape = marquee = panning = false;
            scrubField = null;
            rotationTarget = draggedAsset = "";
            rebuildWidgets();
            return true;
        }
        if (event.isEscape() && timeline.key(event))
        {
            return true;
        }
        String key = keyName(event.key());
        String action = EffectCommands.lookup(key, event.hasControlDown(), event.hasShiftDown(), event.hasAltDown());
        if (!action.isEmpty())
        {
            if (timelineFocused && timeline.keyEditingContext()
                    && Set.of("copy", "cut", "paste", "delete", "select-all").contains(action)
                    && timeline.command(switch (action)
                    {
                    case "copy" -> "copy-keys";
                    case "cut" -> "cut-keys";
                    case "paste" -> "paste-keys";
                    case "delete" -> "delete-keys";
                    default -> "select-all-keys";
                    }))
            {
                return true;
            }
            workspaceCommand(action);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE)
        {
            if (timelineFocused && timeline.selectedKeyCount() > 0)
            {
                timeline.command("delete-keys");
            }
            else
            {
                delete();
            }
            return true;
        }
        if (!timelineFocused && !event.hasControlDown() && !event.hasAltDown()
                && (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT
                        || event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN))
        {
            nudge(event.key(), event.hasShiftDown() ? 10 : 1);
            return true;
        }
        if (timeline.key(event))
        {
            return true;
        }
        return super.keyPressed(event);
    }

    public static String keyName(int key)
    {
        return switch (key)
        {
        case GLFW.GLFW_KEY_A -> "A";
        case GLFW.GLFW_KEY_B -> "B";
        case GLFW.GLFW_KEY_C -> "C";
        case GLFW.GLFW_KEY_D -> "D";
        case GLFW.GLFW_KEY_E -> "E";
        case GLFW.GLFW_KEY_F -> "F";
        case GLFW.GLFW_KEY_G -> "G";
        case GLFW.GLFW_KEY_H -> "H";
        case GLFW.GLFW_KEY_I -> "I";
        case GLFW.GLFW_KEY_J -> "J";
        case GLFW.GLFW_KEY_K -> "K";
        case GLFW.GLFW_KEY_L -> "L";
        case GLFW.GLFW_KEY_M -> "M";
        case GLFW.GLFW_KEY_N -> "N";
        case GLFW.GLFW_KEY_O -> "O";
        case GLFW.GLFW_KEY_P -> "P";
        case GLFW.GLFW_KEY_Q -> "Q";
        case GLFW.GLFW_KEY_R -> "R";
        case GLFW.GLFW_KEY_S -> "S";
        case GLFW.GLFW_KEY_T -> "T";
        case GLFW.GLFW_KEY_U -> "U";
        case GLFW.GLFW_KEY_V -> "V";
        case GLFW.GLFW_KEY_W -> "W";
        case GLFW.GLFW_KEY_X -> "X";
        case GLFW.GLFW_KEY_Y -> "Y";
        case GLFW.GLFW_KEY_Z -> "Z";
        case GLFW.GLFW_KEY_ENTER -> "Enter";
        case GLFW.GLFW_KEY_ESCAPE -> "Esc";
        case GLFW.GLFW_KEY_DELETE -> "Delete";
        case GLFW.GLFW_KEY_F2 -> "F2";
        case GLFW.GLFW_KEY_F3 -> "F3";
        case GLFW.GLFW_KEY_SLASH -> "/";
        case GLFW.GLFW_KEY_APOSTROPHE -> "\'";
        case GLFW.GLFW_KEY_UP -> "Up";
        case GLFW.GLFW_KEY_DOWN -> "Down";
        default -> "";
        };
    }

    public boolean commandEnabled(String id)
    {
        if (!effect.active() || scene.snapshot() == null)
        {
            return false;
        }
        var ids = EffectHierarchy.roots(scene, scene.selectedIds());
        boolean editable = EffectHierarchy.editable(scene, ids);
        if (Set.of("group", "group-layers").contains(id))
        {
            return EffectHierarchy.canGroup(scene);
        }
        if (id.equals("ungroup"))
        {
            return editable && scene.selectedIds().size() == 1
                    && scene.find(ids.getFirst()).kind() == EditorProtocol.KIND_GROUP;
        }
        if (Set.of("rename", "layer-settings").contains(id))
        {
            return scene.selectedIds().size() == 1 && (id.equals("layer-settings") || editable);
        }
        if (Set.of("raise", "lower", "to-front", "to-back").contains(id))
        {
            return editable && EffectHierarchy.sameParent(scene, ids);
        }
        if (id.equals("move-into"))
        {
            return scene.nodes().stream().anyMatch(node -> node.kind() == EditorProtocol.KIND_GROUP
                    && EffectHierarchy.canMove(scene, ids, node.id(), ""));
        }
        if (id.equals("move-root"))
        {
            return editable && ids.stream().anyMatch(target -> !scene.find(target).parentId().isEmpty());
        }
        if (id.equals("move-out"))
        {
            return editable && EffectHierarchy.sameParent(scene, ids)
                    && !scene.find(ids.getFirst()).parentId().isEmpty();
        }
        if (Set.of("delete", "duplicate", "split", "cut", "visibility", "solo", "trim-start", "trim-end").contains(id))
        {
            return editable;
        }
        if (Set.of("copy", "lock").contains(id))
        {
            return !ids.isEmpty();
        }
        if (id.startsWith("new-"))
        {
            String parent = EffectHierarchy.creationParent(scene);
            return parent.isEmpty() || EffectHierarchy.editable(scene, List.of(parent));
        }
        if (id.equals("paste"))
        {
            return !copied.isEmpty() && copiedProject.equals(scene.snapshot().menuId());
        }
        if (Set.of("ease", "ease-in", "ease-out").contains(id))
        {
            return timeline.easingAvailable();
        }
        if (Set.of("linear", "hold", "auto-bezier", "delete-keys", "reverse-keys").contains(id))
        {
            return timeline.selectedKeyCount() > 0;
        }
        return true;
    }

    public boolean workspaceCommand(String id)
    {
        if (!id.equals("close") && effect.busy())
        {
            deferredUI.add(() -> workspaceCommand(id));
            return true;
        }
        if (!commandEnabled(id))
        {
            return false;
        }
        switch (id)
        {
        case "save", "undo", "redo" -> effect.action(id);
        case "copy" -> copy();
        case "cut" ->
        {
            copySelection("cut-layers");
        }
        case "paste" -> paste();
        case "duplicate" -> duplicate();
        case "delete" -> delete();
        case "split" ->
        {
            var data = new JsonObject();
            data.add("targets", new Gson().toJsonTree(selection()));
            effect.submit("split-layer", scene.selectedId(), "", (int) effect.playhead(), 0, data.toString(), "linear",
                    0, 0, 0, 0);
        }
        case "select-all" ->
        {
            scene.selectAll(scene.nodes().stream().map(NodeSnapshot::id).toList());
            rebuildWidgets();
        }
        case "deselect" ->
        {
            scene.clearSelection();
            timeline.command("deselect-keys");
            rebuildWidgets();
        }
        case "rename" ->
        {
            propertyScroll = 0;
            rebuildWidgets();
            fields.stream().filter(field -> field.box != null && field.property.key().equals("id")).findFirst()
                    .ifPresent(field ->
                    {
                        setFocused(field.box);
                        field.box.setCursorPosition(0);
                        field.box.setHighlightPos(field.box.getValue().length());
                    });
        }
        case "group", "group-layers" -> group();
        case "focus-properties" ->
        {
            fields.stream().filter(field -> field.box != null && !field.property.key().equals("id")).findFirst()
                    .ifPresent(field -> setFocused(field.box));
        }
        case "ungroup" -> effect.submit("ungroup-layer", scene.selectedId(), "", 0, 0, "", "linear", 0, 0, 0, 0);
        case "lock" ->
        {
            boolean lock = selection().stream().anyMatch(target -> !scene.locked(target));
            for (String target : selection())
            {
                if (scene.locked(target) != lock)
                {
                    scene.toggleLocked(target);
                }
            }
        }
        case "visibility", "solo", "trim-start", "trim-end" -> timeline.command(id);
        case "move-into" -> chooseParent();
        case "move-root" -> moveLayers(EffectHierarchy.roots(scene, scene.selectedIds()), "", "");
        case "move-out" ->
        {
            var ids = EffectHierarchy.roots(scene, scene.selectedIds());
            var parent = scene.find(scene.find(ids.getFirst()).parentId());
            if (parent != null)
            {
                moveLayers(ids, parent.parentId(), parent.id());
            }
        }
        case "unlock-all" -> scene.unlockAll();
        case "raise", "lower", "to-front", "to-back" -> arrange(id);
        case "settings" -> effectDialog("settings", "", "", 0, null, null);
        case "layer-settings" -> effectDialog("layer", scene.selectedId(), "", 0, null, null);
        case "new-text" -> create(EditorProtocol.KIND_TEXT, "");
        case "new-rectangle" -> create(EditorProtocol.KIND_RECTANGLE, "");
        case "new-gif" -> create(EditorProtocol.KIND_GIF, "");
        case "new-group" -> create(EditorProtocol.KIND_GROUP, "");
        case "tool-select" -> tool = "select";
        case "tool-hand" -> tool = "hand";
        case "tool-zoom" -> tool = "zoom";
        case "tool-rotate" -> tool = "rotate";
        case "tool-shape" -> tool = tool.equals("rectangle") ? "frame" : "rectangle";
        case "tool-text" -> tool = "text";
        case "guides" -> showGuides = !showGuides;
        case "safe-guides" -> safeGuides = !safeGuides;
        case "fit", "actual-size" ->
        {
            fitView = id.equals("fit");
            viewZoom = 1;
            viewPanX = viewPanY = 0;
        }
        case "command-search", "shortcuts" -> minecraft.setScreen(new EffectCommandScreen(this));
        case "search-project" ->
        {
            setFocused(projectSearch);
            projectSearch.setCursorPosition(projectSearch.getValue().length());
        }
        case "reset-workspace" ->
        {
            geometry.left = geometry.right = geometry.bottom = -1;
            viewZoom = 1;
            viewPanX = viewPanY = 0;
            rebuildWidgets();
        }
        case "assets" ->
        {
            projectTab = "assets";
            projectScroll = 0;
        }
        case "audio-preview" -> EffectAudioPreview.INSTANCE.enabled(!EffectAudioPreview.INSTANCE.enabled());
        case "add-key" -> effectDialog("key", scene.selectedId(), "", (int) effect.playhead(), null, null);
        case "cue", "marker" -> effectDialog(id, "", "", (int) effect.playhead(), null, null);
        case "auto-key" -> effect.submit("auto-key", "", "", 0, 0, Boolean.toString(!effect.snapshot().autoKey()),
                "linear", 0, 0, 0, 0);
        case "value-graph", "speed-graph", "timeline" -> graphView(id);
        case "mcp" -> minecraft.setScreen(new McpScreen(this));
        case "close" -> onClose();
        default ->
        {
            return timeline.command(id);
        }
        }
        return true;
    }

    private EffectProtocol.Key keyAt(String target, String property)
    {
        return effect.snapshot().tracks().stream()
                .filter(track -> track.target().equals(target) && track.property().equals(property))
                .flatMap(track -> track.keys().stream()).filter(key -> key.frame() == (int) effect.playhead())
                .findFirst().orElse(null);
    }

    private void togglePropertyKey(String target, String property)
    {
        var key = keyAt(target, property);
        if (key != null)
        {
            effect.submit("delete-key", target, property, key.frame(), 0, "", "linear", 0, 0, 0, 0);
        }
        else
        {
            var node = scene.find(target);
            if (node != null)
            {
                node.properties().stream().filter(nodeProperty -> nodeProperty.key().equals(property)).findFirst()
                        .ifPresent(
                                nodeProperty -> effect.key(
                                        target, property, new EffectProtocol.Key((int) effect.playhead(),
                                                nodeProperty.value(), "linear", 1.0 / 3, 0, 2.0 / 3, 1),
                                        (int) effect.playhead()));
            }
        }
    }

    private void colorPicker(Field field)
    {
        try
        {
            minecraft.setScreen(new EffectColorPicker(this, String.format(Locale.ROOT, "#%02X%06X",
                    (int) Math.max(0, Math.min(255, EffectTransforms.number(scene.find(field.target), "opacity", 255))),
                    EffectColorPicker.parse(field.property.value()) & 0xFFFFFF), value -> setColor(field, value)));
        }
        catch (IllegalArgumentException error)
        {
            scene.error(error.getMessage());
        }
    }

    private void setColor(Field field, String value)
    {
        int rgba = EffectColorPicker.parse(value);
        var root = new JsonObject();
        var changes = new JsonArray();
        var color = new JsonObject();
        color.addProperty("target", field.target);
        color.addProperty("property", field.property.key());
        color.addProperty("value", String.format(Locale.ROOT, "#%06X", rgba & 0xFFFFFF));
        changes.add(color);
        var node = scene.find(field.target);
        if (node != null && EffectPropertyRules.animated(node.kind(), "opacity"))
        {
            var alpha = new JsonObject();
            alpha.addProperty("target", field.target);
            alpha.addProperty("property", "opacity");
            alpha.addProperty("value", Integer.toString(rgba >>> 24));
            changes.add(alpha);
        }
        root.add("changes", changes);
        effect.submit("properties", "", "", (int) effect.playhead(), 0, root.toString(), "linear", 0, 0, 0, 0);
    }

    private static String formatNumber(double value)
    {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void nudge(int key, double amount)
    {
        var data = new JsonObject();
        var changes = new JsonArray();
        double x = key == GLFW.GLFW_KEY_LEFT ? -amount : key == GLFW.GLFW_KEY_RIGHT ? amount : 0;
        double y = key == GLFW.GLFW_KEY_UP ? amount : key == GLFW.GLFW_KEY_DOWN ? -amount : 0;
        for (String id : selection())
        {
            var node = scene.find(id);
            if (node == null || scene.locked(id) || node.locked())
            {
                continue;
            }
            boolean selectedAncestor = false;
            String parent = node.parentId();
            var seen = new HashSet<String>();
            while (!parent.isEmpty() && seen.add(parent))
            {
                if (scene.selected(parent))
                {
                    selectedAncestor = true;
                    break;
                }
                var parentNode = scene.find(parent);
                parent = parentNode == null ? "" : parentNode.parentId();
            }
            if (selectedAncestor)
            {
                continue;
            }
            try
            {
                var delta = EffectTransforms.localDelta(node, scene.nodes(), x, y);
                double[] values = { delta.x, delta.y, delta.z };
                String[] axes = { "offset.x", "offset.y", "offset.z" };
                for (int i = 0; i < 3; i++)
                {
                    if (Math.abs(values[i]) > 1e-9)
                    {
                        var change = new JsonObject();
                        change.addProperty("target", id);
                        change.addProperty("property", axes[i]);
                        change.addProperty("value",
                                formatNumber(EffectTransforms.number(node, axes[i], 0) + values[i]));
                        changes.add(change);
                    }
                }
            }
            catch (IllegalArgumentException invalid)
            {
                scene.error(invalid.getMessage());
                return;
            }
        }
        if (changes.isEmpty())
        {
            return;
        }
        data.add("changes", changes);
        effect.submit("properties", "", "", (int) effect.playhead(), 0, data.toString(), "linear", 0, 0, 0, 0);
    }

    private void arrange(String action)
    {
        var ids = EffectHierarchy.roots(scene, scene.selectedIds());
        if (!EffectHierarchy.editable(scene, ids) || !EffectHierarchy.sameParent(scene, ids))
        {
            return;
        }
        ArcMenuEditorClient.send(new ReorderPacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, ids,
                EffectHierarchy.reorderBefore(scene, ids, action)));
    }

    private void createShape()
    {
        if (scene.snapshot() == null)
        {
            return;
        }
        double startX = menuX(marqueeX);
        double endX = menuX(marqueeEndX);
        double startY = menuY(marqueeY);
        double endY = menuY(marqueeEndY);
        byte kind = switch (tool)
        {
        case "frame" -> EditorProtocol.KIND_FRAME;
        case "text" -> EditorProtocol.KIND_TEXT;
        case "line" -> EditorProtocol.KIND_LINE;
        default -> EditorProtocol.KIND_RECTANGLE;
        };
        double selectionWidth = Math.max(1, Math.abs(endX - startX));
        double selectionHeight = Math.max(1, Math.abs(endY - startY));
        var initial = new JsonObject();
        initial.addProperty("type", tool);
        var offset = new JsonObject();
        offset.addProperty("x", (startX + endX) / 2);
        offset.addProperty("y", (startY + endY) / 2);
        initial.add("offset", offset);
        initial.addProperty("width", selectionWidth);
        initial.addProperty("height", selectionHeight);
        if (kind == EditorProtocol.KIND_TEXT)
        {
            initial.addProperty("content", "Text");
        }
        var selected = scene.find(scene.selectedId());
        ArcMenuEditorClient.send(new CreatePacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, kind,
                selected != null && selected.kind() == 0 ? selected.id() : "", initial.toString()));
        tool = "select";
    }

    public JsonObject workspaceMetadata()
    {
        var json = new Gson();
        var result = new JsonObject();
        result.add("panels", json.toJsonTree(panels));
        result.addProperty("popupOpen", popup.active());
        result.addProperty("tool", tool);
        result.addProperty("fit", fitView);
        result.addProperty("zoom", viewZoom);
        result.addProperty("selectedKeys", timeline.selectedKeyCount());
        var commands = new LinkedHashMap<String, String>();
        EffectCommands.list().forEach(command -> commands.put(command.id(), command.shortcut()));
        EffectTimeline.shortcuts().forEach(commands::putIfAbsent);
        result.add("commands", json.toJsonTree(commands));
        result.add("timeline", timeline.workspaceMetadata());
        if (projectSearch != null)
        {
            var search = new JsonObject();
            search.add("bounds", json.toJsonTree(new EditorLayout.Rect(panels.project().x() + 6,
                    panels.project().y() + 77, panels.project().width() - 12, 19)));
            search.addProperty("value", projectQuery);
            search.addProperty("focused", projectSearch.isFocused());
            result.add("projectSearch", search);
        }
        var propertyFields = new JsonArray();
        for (var field : fields)
        {
            var entry = new JsonObject();
            entry.addProperty("target", field.target);
            entry.addProperty("property", field.property.key());
            entry.addProperty("value", field.property.value());
            entry.addProperty("y", field.y);
            entry.addProperty("type", field.property.type());
            if (field.box != null)
            {
                entry.addProperty("inputValue", field.box.getValue());
                entry.addProperty("focused", field.box.isFocused());
            }
            propertyFields.add(entry);
        }
        result.add("propertyFields", propertyFields);
        return result;
    }

    /** Builds a pointer event for automation; {@code mods} bits are 1 = Shift, 2 = Ctrl, 4 = Alt. */
    static MouseButtonEvent mouseEvent(double x, double y, int button, int mods)
    {
        return new MouseButtonEvent(x, y,
                new MouseButtonInfo(
                        button == 0 ? GLFW.GLFW_MOUSE_BUTTON_LEFT
                                : button == 1 ? GLFW.GLFW_MOUSE_BUTTON_RIGHT : GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
                        ((mods & 1) != 0 ? GLFW.GLFW_MOD_SHIFT : 0) | ((mods & 2) != 0 ? GLFW.GLFW_MOD_CONTROL : 0)
                                | ((mods & 4) != 0 ? GLFW.GLFW_MOD_ALT : 0)));
    }

    public boolean workspaceInput(String operation, String command, String chord, double x, double y, int button,
            int modifiers, double dx, double dy)
    {
        syntheticModifiers = modifiers;
        var input = minecraft.screen instanceof EffectWorkspaceChild child && child.workspace() == this
                ? minecraft.screen
                : this;
        try
        {
            return switch (operation)
            {
            case "command" -> workspaceCommand(command);
            case "shortcut" -> invokeShortcut(chord);
            case "click" -> input.mouseClicked(mouseEvent(x, y, button, modifiers), false);
            case "drag" -> input.mouseDragged(mouseEvent(x, y, button, modifiers), dx, dy);
            case "release" -> input.mouseReleased(mouseEvent(x, y, button, modifiers));
            case "scroll" -> input.mouseScrolled(x, y, dx, dy);
            case "context-menu" ->
            {
                if (command.equals("layer"))
                {
                    layerMenu(x, y);
                }
                else if (command.equals("composition") || command.equals("project"))
                {
                    emptyMenu(x, y);
                }
                else
                {
                    throw new IllegalArgumentException("Unknown context-menu region");
                }
                yield true;
            }
            default -> throw new IllegalArgumentException("Unknown workspace input operation");
            };
        }
        finally
        {
            syntheticModifiers = -1;
        }
    }

    private boolean invokeShortcut(String chord)
    {
        chord = chord.split(" / ")[0].trim();
        if (chord.endsWith("+"))
        {
            chord = chord.substring(0, chord.length() - 1) + "=";
        }
        int modifiers = 0;
        String key = "";
        for (String part : chord.split("\\+"))
        {
            switch (part.toLowerCase(Locale.ROOT))
            {
            case "ctrl" -> modifiers |= GLFW.GLFW_MOD_CONTROL;
            case "shift" -> modifiers |= GLFW.GLFW_MOD_SHIFT;
            case "alt" -> modifiers |= GLFW.GLFW_MOD_ALT;
            default -> key = part;
            }
        }
        int code = shortcutCode(key);
        if (code < 0)
        {
            throw new IllegalArgumentException("Unknown shortcut key");
        }
        var input = minecraft.screen instanceof EffectWorkspaceChild child && child.workspace() == this
                ? minecraft.screen
                : this;
        return input.keyPressed(new KeyEvent(code, 0, modifiers));
    }

    private static int shortcutCode(String key)
    {
        String name = key.toUpperCase(Locale.ROOT).replace(" ", "");
        return switch (name)
        {
        case "A" -> GLFW.GLFW_KEY_A;
        case "B" -> GLFW.GLFW_KEY_B;
        case "C" -> GLFW.GLFW_KEY_C;
        case "D" -> GLFW.GLFW_KEY_D;
        case "E" -> GLFW.GLFW_KEY_E;
        case "F" -> GLFW.GLFW_KEY_F;
        case "G" -> GLFW.GLFW_KEY_G;
        case "H" -> GLFW.GLFW_KEY_H;
        case "I" -> GLFW.GLFW_KEY_I;
        case "J" -> GLFW.GLFW_KEY_J;
        case "K" -> GLFW.GLFW_KEY_K;
        case "L" -> GLFW.GLFW_KEY_L;
        case "M" -> GLFW.GLFW_KEY_M;
        case "N" -> GLFW.GLFW_KEY_N;
        case "O" -> GLFW.GLFW_KEY_O;
        case "P" -> GLFW.GLFW_KEY_P;
        case "Q" -> GLFW.GLFW_KEY_Q;
        case "R" -> GLFW.GLFW_KEY_R;
        case "S" -> GLFW.GLFW_KEY_S;
        case "T" -> GLFW.GLFW_KEY_T;
        case "U" -> GLFW.GLFW_KEY_U;
        case "V" -> GLFW.GLFW_KEY_V;
        case "W" -> GLFW.GLFW_KEY_W;
        case "X" -> GLFW.GLFW_KEY_X;
        case "Y" -> GLFW.GLFW_KEY_Y;
        case "Z" -> GLFW.GLFW_KEY_Z;
        case "SPACE" -> GLFW.GLFW_KEY_SPACE;
        case "ENTER" -> GLFW.GLFW_KEY_ENTER;
        case "ESC" -> GLFW.GLFW_KEY_ESCAPE;
        case "ESCAPE" -> GLFW.GLFW_KEY_ESCAPE;
        case "DELETE" -> GLFW.GLFW_KEY_DELETE;
        case "UP" -> GLFW.GLFW_KEY_UP;
        case "DOWN" -> GLFW.GLFW_KEY_DOWN;
        case "LEFT" -> GLFW.GLFW_KEY_LEFT;
        case "RIGHT" -> GLFW.GLFW_KEY_RIGHT;
        case "HOME" -> GLFW.GLFW_KEY_HOME;
        case "END" -> GLFW.GLFW_KEY_END;
        case "PAGEUP" -> GLFW.GLFW_KEY_PAGE_UP;
        case "PAGEDOWN" -> GLFW.GLFW_KEY_PAGE_DOWN;
        case "NUM0" -> GLFW.GLFW_KEY_KP_0;
        case "/" -> GLFW.GLFW_KEY_SLASH;
        case "\'" -> GLFW.GLFW_KEY_APOSTROPHE;
        case ";" -> GLFW.GLFW_KEY_SEMICOLON;
        case "[" -> GLFW.GLFW_KEY_LEFT_BRACKET;
        case "]" -> GLFW.GLFW_KEY_RIGHT_BRACKET;
        case "=" -> GLFW.GLFW_KEY_EQUAL;
        case "-" -> GLFW.GLFW_KEY_MINUS;
        case "F1" -> GLFW.GLFW_KEY_F1;
        case "F2" -> GLFW.GLFW_KEY_F2;
        case "F3" -> GLFW.GLFW_KEY_F3;
        case "F4" -> GLFW.GLFW_KEY_F4;
        case "F5" -> GLFW.GLFW_KEY_F5;
        case "F6" -> GLFW.GLFW_KEY_F6;
        case "F7" -> GLFW.GLFW_KEY_F7;
        case "F8" -> GLFW.GLFW_KEY_F8;
        case "F9" -> GLFW.GLFW_KEY_F9;
        case "F10" -> GLFW.GLFW_KEY_F10;
        case "F11" -> GLFW.GLFW_KEY_F11;
        case "F12" -> GLFW.GLFW_KEY_F12;
        default -> -1;
        };
    }

    private Path workspaceFile()
    {
        return ArcMenuEditorClient.selectionCalibrationFile().resolveSibling("arcmenu-effect-workspace.properties");
    }

    private void loadWorkspace()
    {
        var preferences = new Properties();
        try (var in = Files.newInputStream(workspaceFile()))
        {
            preferences.load(in);
            geometry.left = Integer.parseInt(preferences.getProperty("left", "-1"));
            geometry.right = Integer.parseInt(preferences.getProperty("right", "-1"));
            geometry.bottom = Integer.parseInt(preferences.getProperty("bottom", "-1"));
        }
        catch (IOException | IllegalArgumentException ignored)
        {}
    }

    private void saveWorkspace()
    {
        var preferences = new Properties();
        preferences.setProperty("left", Integer.toString(geometry.left));
        preferences.setProperty("right", Integer.toString(geometry.right));
        preferences.setProperty("bottom", Integer.toString(geometry.bottom));
        try
        {
            Files.createDirectories(workspaceFile().getParent());
            try (var out = Files.newOutputStream(workspaceFile()))
            {
                preferences.store(out, "Effect workspace panel layout");
            }
        }
        catch (IOException ignored)
        {}
    }

    private void sceneUndo(boolean redo)
    {
        if (scene.snapshot() != null)
        {
            ArcMenuEditorClient.send(
                    redo ? new RedoPacket(scene.snapshot().revision()) : new UndoPacket(scene.snapshot().revision()));
        }
    }

    private void select(String id)
    {
        scene.selectOnly(id);
        propertyScroll = 0;
        rebuildWidgets();
    }

    private void create(byte kind, String source)
    {
        if (scene.snapshot() == null)
        {
            return;
        }
        String parent = EffectHierarchy.creationParent(scene);
        ArcMenuEditorClient
                .send(new CreatePacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, kind, parent, source));
    }

    private List<String> selection()
    {
        return scene.nodes().stream().map(NodeSnapshot::id).filter(scene::selected).toList();
    }

    private void delete()
    {
        if (scene.snapshot() != null && !selection().isEmpty())
        {
            ArcMenuEditorClient
                    .send(new DeleteManyPacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, selection()));
        }
    }

    private void duplicate()
    {
        if (scene.snapshot() != null && !selection().isEmpty())
        {
            ArcMenuEditorClient.send(new DuplicatePacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND,
                    selection(), "", true));
        }
    }

    private void group()
    {
        if (scene.snapshot() != null && !selection().isEmpty())
        {
            ArcMenuEditorClient.send(
                    new GroupPacket(scene.snapshot().revision(), EffectHierarchy.roots(scene, scene.selectedIds())));
        }
    }

    private void copySelection(String action)
    {
        copied = selection();
        copiedProject = scene.snapshot() == null ? "" : scene.snapshot().menuId();
        var data = new JsonObject();
        data.add("targets", new Gson().toJsonTree(copied));
        effect.submit(action, scene.selectedId(), "", 0, 0, data.toString(), "linear", 0, 0, 0, 0);
    }

    private void copy()
    {
        copySelection("copy-layers");
    }

    private void paste()
    {
        if (scene.snapshot() != null && copiedProject.equals(scene.snapshot().menuId()) && !copied.isEmpty())
        {
            effect.submit("paste-layers", "", "", (int) effect.playhead(), 0, "", "linear", 0, 0, 0, 0);
        }
    }

    private void property(Field field, String value)
    {
        if (scene.snapshot() != null && !value.equals(field.property.value()))
        {
            ArcMenuEditorClient.send(new SetPropertyPacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND,
                    field.target, field.property.key(), value));
        }
    }

    private void sourcePicker(Field field)
    {
        var node = scene.find(field.target);
        if (node == null || scene.snapshot() == null)
        {
            return;
        }
        var choices = new ArrayList<EffectPopup.Item>();
        for (var image : scene.snapshot().images())
        {
            if (EditorAssetLibrary.isUserImage(image.path()) && EditorImageTypes.matches(node.kind(), image.path()))
            {
                choices.add(new EffectPopup.Item(image.path(), "", () -> property(field, image.path())));
            }
        }
        if (choices.isEmpty())
        {
            choices.add(new EffectPopup.Item(EditorI18n.text("arcmenu_editor.images.none"), "", () ->
            {}, false));
        }
        openedMenu = "";
        popup.open(panels.properties().x(), field.y, choices);
    }

    private void commitFocused()
    {
        for (var field : fields)
        {
            if (field.box != null && field.box.isFocused())
            {
                property(field, PropertyValues.submitted(field.property, field.box.getValue()));
                setFocused(null);
                break;
            }
        }
    }

    private void deferPointer(MouseButtonEvent event, boolean doubleClick)
    {
        deferredPointer = true;
        queuePointer(new DeferredInput(0, event, doubleClick, 0, 0));
        deferredUI.add(() ->
        {
            deferredPointer = false;
            var events = new ArrayList<>(deferredPointerEvents);
            deferredPointerEvents.clear();
            for (var input : events)
            {
                switch (input.type)
                {
                case 0 -> mouseClicked(input.event, input.doubleClick);
                case 1 -> mouseDragged(input.event, input.dx, input.dy);
                case 2 -> mouseReleased(input.event);
                default -> throw new IllegalArgumentException("Unknown deferred pointer event");
                }
            }
        });
    }

    private ElementSelectionCalibration.Calibration nodeCalibration(byte kind)
    {
        var sceneSnapshot = scene.snapshot();
        return calibration.forNode(kind, sceneSnapshot != null && sceneSnapshot.exactRectangles());
    }

    private NodeSnapshot hit(double x, double y)
    {
        return scene.nodes().stream().filter(node ->
        {
            var calibration = nodeCalibration(node.kind());
            double drawX = calibration.proxyX(node.x(), node.width());
            double drawY = calibration.proxyY(node.y(), node.height());
            return node.visible() && Math.abs(menuX(x) - drawX) <= calibration.proxyWidth(node.width()) / 2
                    && Math.abs(menuY(y) - drawY) <= calibration.proxyHeight(node.height()) / 2;
        }).max(Comparator.comparingInt(node -> scene.nodes().indexOf(node))).orElse(null);
    }

    private int previewZoom()
    {
        var window = minecraft.getWindow();
        var source = EditorViewport.sourceFrame(window.getWidth(), window.getHeight());
        return source == null ? 100 : (int) Math.round(window.getWidth() * 100.0 / source.width());
    }

    private ScreenRect virtualScreen()
    {
        var snapshot = scene.snapshot();
        var view = effect.viewport();
        var window = minecraft.getWindow();
        var physical = EditorViewport.physical(window);
        var nominal = panels.viewport();
        var aperture = physical == null ? new ScreenRect(nominal.x(), nominal.y(), nominal.width(), nominal.height())
                : EffectProjection.guiAperture(
                        new EditorLayout.Rect(physical.x(), physical.y(), physical.width(), physical.height()),
                        window.getWidth(), window.getHeight(), window.getGuiScaledWidth(), window.getGuiScaledHeight());
        if (view == null)
        {
            return aperture;
        }
        var raw = EffectProjection.calculate(aperture, snapshot.canvasWidth(), snapshot.canvasHeight(),
                view.pixelsPerBlock(), view.distance(), view.offsetX(), view.offsetY(), EditorViewport.verticalFov());
        var source = EditorViewport.sourceFrame(window.getWidth(), window.getHeight());
        return source == null ? raw
                : EffectProjection.cropped(raw, aperture, source, window.getWidth(), window.getHeight());
    }

    private int depth(NodeSnapshot node)
    {
        int depth = 0;
        Set<String> seen = new HashSet<>();
        while (node != null && !node.parentId().isEmpty() && seen.add(node.parentId()))
        {
            depth++;
            node = scene.find(node.parentId());
        }
        return depth;
    }

    private boolean projectHidden(NodeSnapshot node)
    {
        Set<String> seen = new HashSet<>();
        while (node != null && !node.parentId().isEmpty() && seen.add(node.parentId()))
        {
            if (!scene.expanded().contains(node.parentId()))
            {
                return true;
            }
            node = scene.find(node.parentId());
        }
        return false;
    }

    private int sx(double x)
    {
        var view = virtualScreen();
        return (int) Math.round(
                view.x() + (x + scene.snapshot().canvasWidth() / 2) / scene.snapshot().canvasWidth() * view.width());
    }

    private int sy(double y)
    {
        var view = virtualScreen();
        return (int) Math.round(
                view.y() + (scene.snapshot().canvasHeight() / 2 - y) / scene.snapshot().canvasHeight() * view.height());
    }

    private double menuX(double x)
    {
        var view = virtualScreen();
        return (x - view.x()) / view.width() * scene.snapshot().canvasWidth() - scene.snapshot().canvasWidth() / 2;
    }

    private double menuY(double y)
    {
        var view = virtualScreen();
        return scene.snapshot().canvasHeight() / 2 - (y - view.y()) / view.height() * scene.snapshot().canvasHeight();
    }

    private EditorProtocol.Pointer pointer(double x, double y)
    {
        var aperture = panels.viewport();
        x = Math.max(aperture.x(), Math.min(aperture.right() - .001, x));
        y = Math.max(aperture.y(), Math.min(aperture.bottom() - .001, y));
        var view = virtualScreen();
        double menuPointX = Math.max(-scene.snapshot().canvasWidth() * 1.9,
                Math.min(scene.snapshot().canvasWidth() * 1.9, menuX(x)));
        double menuPointY = Math.max(-scene.snapshot().canvasHeight() * 1.9,
                Math.min(scene.snapshot().canvasHeight() * 1.9, menuY(y)));
        var node = scene.find(dragging);
        if (resizing && node != null)
        {
            var calibration = nodeCalibration(node.kind());
            menuPointX = calibration.serverRightX(node.x(), menuPointX);
            menuPointY = calibration.serverBottomY(node.y(), menuPointY);
        }
        x = view.x()
                + (menuPointX + scene.snapshot().canvasWidth() / 2) / scene.snapshot().canvasWidth() * view.width();
        y = view.y()
                + (scene.snapshot().canvasHeight() / 2 - menuPointY) / scene.snapshot().canvasHeight() * view.height();
        return new EditorProtocol.Pointer(x, y, view.x(), view.y(), view.width(), view.height(), menuPointX,
                menuPointY);
    }

    private void flushDrag()
    {
        if (awaiting || pending == null || scene.snapshot() == null || gesture == 0
                || (!released && System.nanoTime() - lastSend < 50_000_000L))
        {
            return;
        }
        if (!released && Objects.equals(pending, sent))
        {
            return;
        }
        awaiting = true;
        finalSent = released;
        sent = pending;
        lastSend = System.nanoTime();
        ArcMenuEditorClient.send(resizing
                ? new ResizePacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, dragging, pending, gesture,
                        released)
                : new MovePacket(scene.snapshot().revision(), EditorProtocol.TAB_FRONTEND, dragging, pending, grabX,
                        grabY, gesture, released));
    }

    private void finishDrag()
    {
        gesture = 0;
        dragging = "";
        pending = null;
        sent = null;
        awaiting = false;
        released = false;
        finalSent = false;
    }

    private String timecode(int frame)
    {
        double fps = effect.active() ? effect.snapshot().fps() : 30;
        int second = (int) (frame / fps);
        int frameInSecond = (int) Math.floor(frame - second * fps);
        return String.format(Locale.ROOT, "%d:%02d:%02d:%02d", second / 3600, second / 60 % 60, second % 60,
                frameInSecond);
    }

    private void queuePointer(DeferredInput input)
    {
        if (input.type == 1 && !deferredPointerEvents.isEmpty() && deferredPointerEvents.getLast().type == 1)
        {
            deferredPointerEvents.removeLast();
        }
        if (deferredPointerEvents.size() >= 128)
        {
            deferredPointerEvents.clear();
            deferredPointer = false;
            deferredUI.clear();
            return;
        }
        deferredPointerEvents.addLast(input);
    }

    private static String toolKey(byte kind)
    {
        return kind == EditorProtocol.KIND_REGION ? "region" : ElementSelectionCalibration.key(kind);
    }

    private boolean control()
    {
        if (syntheticModifiers >= 0)
        {
            return (syntheticModifiers & 2) != 0;
        }
        return KeyboardModifiers.controlDown(minecraft);
    }

    private boolean shift()
    {
        if (syntheticModifiers >= 0)
        {
            return (syntheticModifiers & 1) != 0;
        }
        return KeyboardModifiers.shiftDown(minecraft);
    }
}

package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EditorScreen extends Screen {
    private static final long LIVE_DRAG_INTERVAL_NANOS = 50_000_000L;
    private static final int PANEL = 0xFF1A1D24;
    private static final int PANEL_ALT = 0xFF232731;
    private static final int HEADER = 0xFF0F1117;
    private static final int BORDER = 0xFF3B4352;
    private static final int TEXT = 0xFFE8ECF4;
    private static final int MUTED = 0xFF9AA4B2;
    private static final int ACCENT = 0xFF388BFF;
    private static final int SELECTED = 0xFF263F65;
    private static final int DANGER = 0xFFE35D6A;

    private final EditorState state;
    private final EditorLayout layout = new EditorLayout();
    private final ElementSelectionCalibration selectionCalibration = new ElementSelectionCalibration();
    private EditorLayout.Layout currentLayout;
    private final List<NodeRow> nodeRows = new ArrayList<>();
    private final List<PropertyField> propertyFields = new ArrayList<>();
    private final List<ToolButton> toolButtons = new ArrayList<>();
    private final EditorAssetLibrary assetLibrary = new EditorAssetLibrary();
    private final List<AssetCard> assetCards = new ArrayList<>();
    private final List<FolderRow> folderRows = new ArrayList<>();
    private final List<TemplateCard> templateCards = new ArrayList<>();
    private final List<ManagerButton> managerButtons = new ArrayList<>();
    private final List<CalibrationField> calibrationFields = new ArrayList<>();
    private String draggingNode = "";
    private double grabOffsetX;
    private double grabOffsetY;
    private EditorProtocol.Pointer pendingDragPointer;
    private EditorProtocol.Pointer lastSentDragPointer;
    private long nextGestureId = 1;
    private long activeGestureId;
    private long lastDragSendNanos;
    private boolean dragRequestInFlight;
    private boolean dragReleased;
    private boolean finalDragSent;
    private ResizeTarget resizeTarget = ResizeTarget.NONE;
    private boolean resizingNode;
    private int toolsScroll;
    private int toolContentHeight;
    private int managerScroll;
    private int propertyScroll;
    private int templateScroll;
    private int templateContentHeight;
    private int imageScroll;
    private int imageContentHeight;
    private int folderScroll;
    private String dragCandidateNode = "";
    private String draggingTemplate = "";
    private double dragStartX;
    private double dragStartY;
    private String pendingTemplateNode = "";
    private EditBox templateNameBox;
    private ContextMenu contextMenu;
    private List<String> clipboardIds = List.of();
    private byte clipboardTab = -1;
    private String clipboardMenu = "";
    private PropertyDrag propertyDrag;
    private HoverTooltip hoverTooltip;
    private boolean propertyRequestInFlight;
    private boolean propertyFinalSent;
    private boolean propertyReleased;
    private long lastPropertySendNanos;
    private double pendingPropertyValue;
    private double sentPropertyValue = Double.NaN;
    private boolean calibrationSettingsOpen;
    private byte calibrationKind = EditorProtocol.KIND_TEXT;

    public EditorScreen(EditorState state) {
        super(EditorI18n.component("arcmenu_editor.screen.title"));
        this.state = state;
        var preferences = ArcMenuEditorClient.preferencesFile();
        this.layout.load(preferences);
        this.layout.save(preferences);
        var calibration = ArcMenuEditorClient.selectionCalibrationFile();
        this.selectionCalibration.load(calibration);
        this.selectionCalibration.save(calibration);
    }

    public void serverStateChanged() {
        // A full snapshot can arrive while another field is being typed into.
        PropertyField focused = propertyFields.stream().filter(field -> field.box.isFocused()).findFirst().orElse(null);
        String input = focused == null ? "" : focused.box.getValue();
        int cursor = focused == null ? 0 : focused.box.getCursorPosition();
        rebuildWidgets();
        if (focused != null && focused.nodeId.equals(state.selectedId()) && focused.tab == state.activeTab()) {
            propertyField(focused.property.key()).ifPresent(field -> {
                field.box.setValue(input);
                setFocused(field.box);
                field.box.setCursorPosition(cursor);
                field.box.setHighlightPos(cursor);
            });
        }
        if (propertyDrag != null && propertyDrag.nodeId.equals(state.selectedId())) {
            propertyField(propertyDrag.property.key()).ifPresent(field -> field.box.setValue(
                    formatPropertyValue(propertyDrag.property, pendingPropertyValue)));
        }
    }

    public void serverOperationCompleted(byte operation, long gestureId) {
        if (operation == EditorProtocol.OP_PROPERTY && propertyRequestInFlight && propertyDrag != null
                && propertyDrag.gestureId == gestureId) {
            boolean completedFinal = propertyFinalSent;
            propertyRequestInFlight = false;
            propertyFinalSent = false;
            if (completedFinal) finishPropertyGesture();
            else if (propertyReleased) flushPropertyDrag(true);
            else if (Double.compare(pendingPropertyValue, sentPropertyValue) != 0) flushPropertyDrag(false);
            return;
        }
        if ((operation != EditorProtocol.OP_MOVE && operation != EditorProtocol.OP_RESIZE)
                || !dragRequestInFlight || activeGestureId != gestureId) return;
        boolean completedFinal = finalDragSent;
        dragRequestInFlight = false;
        finalDragSent = false;
        if (completedFinal) {
            finishLiveGesture();
        } else if (dragReleased) {
            flushLiveDrag(true);
        } else if (pendingDragPointer != null && !pendingDragPointer.equals(lastSentDragPointer)) {
            flushLiveDrag(false);
        }
    }

    public void serverOperationFailed() {
        if (propertyRequestInFlight) {
            propertyRequestInFlight = false;
            propertyFinalSent = false;
            finishPropertyGesture();
        }
        dragRequestInFlight = false;
        finalDragSent = false;
        if (dragReleased) finishLiveGesture();
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        propertyFields.clear();
        assetLibrary.update(state.snapshot() == null ? List.of() : state.snapshot().images());
        calibrationFields.clear();
        currentLayout = layout.calculate(width, height);
        EditorViewport.update(currentLayout.viewport());
        if (calibrationSettingsOpen) {
            rebuildCalibrationWidgets();
            templateNameBox = null;
            return;
        }
        EditorProtocol.NodeSnapshot node = state.find(state.selectedId());
        if (node != null) {
            int fieldX = currentLayout.properties().x() + 94;
            int fieldWidth = Math.max(70, currentLayout.properties().width() - 105);
            int index = 0;
            for (EditorProtocol.PropertySnapshot property : node.properties()) {
                int y = currentLayout.properties().y() + 28 + index * 24 - propertyScroll;
                if (y >= currentLayout.properties().y() + 21 && y + 19 < currentLayout.properties().bottom() - 22) {
                    boolean numeric = isNumeric(property);
                    int boxInset = numeric ? 18 : 7;
                    int boxRightInset = numeric ? 18 : 15;
                    EditBox box = new EditBox(font, fieldX + boxInset, y + 6,
                            Math.max(20, fieldWidth - boxInset - boxRightInset), 10,
                            Component.literal(property.key()));
                    box.setMaxLength(EditorProtocol.MAX_STRING_BYTES / 2);
                    box.setValue(displayPropertyValue(property));
                    box.setBordered(false);
                    box.setTextShadow(false);
                    box.setTextColor(TEXT);
                    addRenderableWidget(box);
                    propertyFields.add(new PropertyField(node.id(), state.activeTab(), property, box, y,
                            new EditorLayout.Rect(fieldX, y + 1, fieldWidth, 20)));
                }
                index++;
            }
        }
        if (!pendingTemplateNode.isBlank()) {
            int boxWidth = 220;
            templateNameBox = new EditBox(font, (width - boxWidth) / 2, height / 2 - 3, boxWidth, 20,
                    EditorI18n.component("arcmenu_editor.templates.name"));
            templateNameBox.setMaxLength(64);
            templateNameBox.setValue(suggestTemplateId(pendingTemplateNode));
            addRenderableWidget(templateNameBox);
            setInitialFocus(templateNameBox);
        } else templateNameBox = null;
    }

    private void rebuildCalibrationWidgets() {
        EditorLayout.Rect panel = calibrationPanel();
        ElementSelectionCalibration.Calibration value = selectionCalibration.get(calibrationKind);
        String[] values = {format(value.offsetRatioX()), format(value.offsetRatioY()), format(value.scaleX()), format(value.scaleY())};
        int fieldX = panel.x() + Math.min(235, Math.max(170, panel.width() / 2));
        int fieldWidth = Math.max(90, panel.right() - fieldX - 20);
        for (int index = 0; index < values.length; index++) {
            int y = panel.y() + 61 + index * 40;
            EditBox box = new EditBox(font, fieldX, y, fieldWidth, 20,
                    Component.literal(calibrationFieldLabel(index)));
            box.setMaxLength(32);
            box.setValue(values[index]);
            box.setTextColor(TEXT);
            addRenderableWidget(box);
            calibrationFields.add(new CalibrationField(index, box));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    /** Keep the composited camera feed un-tinted inside the 16:9 viewport. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The normal in-game Screen background is a full-window translucent
        // veil. Dock panels already provide their own opaque background, so a
        // veil here would incorrectly darken the real game viewport as well.
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (propertyDrag != null && propertyDrag.dragging && !propertyReleased && !propertyRequestInFlight
                && Double.compare(pendingPropertyValue, sentPropertyValue) != 0) {
            flushPropertyDrag(false);
        }
        if (activeGestureId != 0 && !dragReleased && !dragRequestInFlight
                && pendingDragPointer != null && !pendingDragPointer.equals(lastSentDragPointer)) {
            flushLiveDrag(false);
        }
    }

    @Override
    public void onClose() {
        if (calibrationSettingsOpen) applyCalibrationFields(true);
        layout.save(ArcMenuEditorClient.preferencesFile());
        selectionCalibration.save(ArcMenuEditorClient.selectionCalibrationFile());
        EditorViewport.clear();
        ArcMenuEditorClient.send(new EditorProtocol.ClosePacket());
        super.onClose();
    }

    @Override
    public void removed() {
        EditorViewport.clear();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        currentLayout = layout.calculate(width, height);
        EditorViewport.update(currentLayout.viewport());
        hoverTooltip = null;
        drawWorkspaceMask(graphics);
        drawHeader(graphics, mouseX, mouseY);
        drawTools(graphics, mouseX, mouseY);
        drawViewport(graphics, mouseX, mouseY);
        drawElementManager(graphics, mouseX, mouseY);
        drawProperties(graphics);
        drawTemplates(graphics, mouseX, mouseY);
        if (calibrationSettingsOpen) {
            drawCalibrationSettings(graphics, mouseX, mouseY);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }
        drawDragGhost(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawContextMenu(graphics, mouseX, mouseY);
        drawHoverTooltip(graphics);
    }

    private void drawDragGhost(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String label = !dragCandidateNode.isBlank() ? (state.selectedIds().size() > 1
                ? EditorI18n.text("arcmenu_editor.common.elements", state.selectedIds().size()) : dragCandidateNode)
                : !draggingTemplate.isBlank() ? EditorI18n.text("arcmenu_editor.common.template_drag", draggingTemplate) : "";
        if (label.isBlank() || Math.hypot(mouseX - dragStartX, mouseY - dragStartY) < 4.0) return;
        int ghostWidth = Math.min(180, font.width(label) + 16);
        int x = Math.min(width - ghostWidth - 3, mouseX + 9);
        int y = Math.min(height - 23, mouseY + 9);
        graphics.fill(x, y, x + ghostWidth, y + 20, 0xEE202631);
        graphics.outline(x, y, ghostWidth, 20, ACCENT);
        graphics.text(font, ellipsize(label, 24), x + 7, y + 6, TEXT, false);
    }

    private void drawWorkspaceMask(GuiGraphicsExtractor graphics) {
        var l = currentLayout;
        fill(graphics, l.header(), HEADER);
        fill(graphics, l.tools(), PANEL);
        fill(graphics, l.manager(), PANEL);
        fill(graphics, l.properties(), PANEL_ALT);
        fill(graphics, l.templates(), PANEL);

        var host = l.viewportHost();
        var view = l.viewport();
        graphics.fill(host.x(), host.y(), host.right(), view.y(), PANEL_ALT);
        graphics.fill(host.x(), view.bottom(), host.right(), host.bottom(), PANEL_ALT);
        graphics.fill(host.x(), view.y(), view.x(), view.bottom(), PANEL_ALT);
        graphics.fill(view.right(), view.y(), host.right(), view.bottom(), PANEL_ALT);
        graphics.outline(view.x() - 1, view.y() - 1, view.width() + 2, view.height() + 2, 0xFF657084);
        graphics.fill(layout.toolsWidth() - 1, EditorLayout.HEADER_HEIGHT, layout.toolsWidth() + 1, height, BORDER);
        graphics.fill(width - layout.inspectorWidth() - 1, EditorLayout.HEADER_HEIGHT,
                width - layout.inspectorWidth() + 1, height, BORDER);
        graphics.fill(layout.toolsWidth(), height - layout.templatesHeight() - 1,
                width - layout.inspectorWidth(), height - layout.templatesHeight() + 1, BORDER);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, "ArcMenu", 8, 8, TEXT, false);
        EditorProtocol.SnapshotPacket snapshot = state.snapshot();
        int x = 68;
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.frontend"), state.activeTab() == EditorProtocol.TAB_FRONTEND, mouseX, mouseY);
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.backend"), state.activeTab() == EditorProtocol.TAB_BACKEND, mouseX, mouseY);
        x += 8;
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.undo"), false, mouseX, mouseY);
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.redo"), false, mouseX, mouseY);
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.save"), false, mouseX, mouseY);
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.apply"), false, mouseX, mouseY);
        x = headerButton(graphics, x, EditorI18n.text("arcmenu_editor.header.settings"), calibrationSettingsOpen, mouseX, mouseY);
        String menu = snapshot == null ? EditorI18n.text("arcmenu_editor.header.disconnected")
                : snapshot.menuId() + "  r" + snapshot.revision() + (snapshot.dirty() ? "  *" : "");
        graphics.text(font, menu, Math.min(x + 8, width - 220), 8, snapshot != null && snapshot.dirty() ? 0xFFFFC857 : MUTED, false);
        String status = ellipsize(state.status(), 180);
        graphics.text(font, status, Math.max(x + 110, width - font.width(status) - 28), 8,
                state.errorStatus() ? DANGER : MUTED, false);
        EditorIcons.draw(graphics, EditorIcons.CLOSE, width - 20, 4, 0xFFE7A0A7);
    }

    private int headerButton(GuiGraphicsExtractor graphics, int x, String label, boolean active, int mouseX, int mouseY) {
        int buttonWidth = font.width(label) + 14;
        boolean hover = mouseX >= x && mouseX < x + buttonWidth && mouseY >= 3 && mouseY < 22;
        graphics.fill(x, 3, x + buttonWidth, 22, active ? ACCENT : hover ? 0xFF343B48 : 0xFF252A34);
        graphics.text(font, label, x + 7, 8, TEXT, false);
        return x + buttonWidth + 3;
    }

    private void drawCalibrationSettings(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(0, EditorLayout.HEADER_HEIGHT, width, height, 0xC9000000);
        EditorLayout.Rect panel = calibrationPanel();
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), 0xFF171A21);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), 0xFF657084);
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.y() + 31, HEADER);
        graphics.text(font, EditorI18n.text("arcmenu_editor.calibration.title"), panel.x() + 11, panel.y() + 11, TEXT, false);
        graphics.text(font, EditorI18n.text("arcmenu_editor.calibration.description"),
                panel.x() + 116, panel.y() + 11, MUTED, false);
        EditorIcons.draw(graphics, EditorIcons.CLOSE, panel.right() - 21, panel.y() + 7, 0xFFE7A0A7);

        for (int index = 0; index < ElementSelectionCalibration.KINDS.length; index++) {
            byte kind = ElementSelectionCalibration.KINDS[index];
            EditorLayout.Rect row = calibrationKindRect(index);
            boolean hover = row.contains(mouseX, mouseY);
            boolean selected = kind == calibrationKind;
            graphics.fill(row.x(), row.y(), row.right(), row.bottom(), selected ? SELECTED : hover ? 0xFF2B303A : PANEL);
            if (selected) graphics.fill(row.x(), row.y(), row.x() + 3, row.bottom(), ACCENT);
            EditorIcons.draw(graphics, kindIcon(kind), row.x() + 8, row.y() + 3, kindColor(kind));
            graphics.text(font, calibrationKindLabel(kind), row.x() + 30, row.y() + 7, TEXT, false);
        }

        int labelX = panel.x() + Math.min(165, Math.max(145, panel.width() / 3));
        for (CalibrationField field : calibrationFields) {
            graphics.text(font, calibrationFieldLabel(field.index), labelX,
                    field.box.getY() + 7, MUTED, false);
        }
        graphics.text(font, EditorI18n.text("arcmenu_editor.calibration.offset_help"), labelX, panel.y() + 230, MUTED, false);
        graphics.text(font, EditorI18n.text("arcmenu_editor.calibration.scale_help"), labelX, panel.y() + 244, MUTED, false);
        graphics.text(font, EditorI18n.text("arcmenu_editor.calibration.shared_help"), labelX, panel.y() + 258, 0xFF7F9CC3, false);
        settingButton(graphics, calibrationResetRect(), EditorI18n.text("arcmenu_editor.calibration.reset"), mouseX, mouseY, false);
        settingButton(graphics, calibrationSaveRect(), EditorI18n.text("arcmenu_editor.calibration.save"), mouseX, mouseY, true);
        String status = ellipsize(state.status(), Math.max(20, (panel.width() - 245) / 6));
        graphics.text(font, status, panel.x() + 10, panel.bottom() - 20, MUTED, false);
    }

    private void settingButton(GuiGraphicsExtractor graphics, EditorLayout.Rect rect, String label,
                               int mouseX, int mouseY, boolean primary) {
        boolean hover = rect.contains(mouseX, mouseY);
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                primary ? (hover ? 0xFF4B9AFF : ACCENT) : (hover ? 0xFF343B48 : 0xFF252A34));
        graphics.centeredText(font, label, rect.x() + rect.width() / 2, rect.y() + 7, TEXT);
    }

    private void drawTools(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var panel = currentLayout.tools();
        title(graphics, panel, EditorI18n.text("arcmenu_editor.panel.tools"));
        ToolDefinition[] tools = state.activeTab() == EditorProtocol.TAB_BACKEND
                ? new ToolDefinition[]{new ToolDefinition(EditorIcons.OUTLINE, EditorI18n.text("arcmenu_editor.tool.region"), EditorProtocol.KIND_REGION)}
                : new ToolDefinition[]{new ToolDefinition(EditorIcons.CURSOR, EditorI18n.text("arcmenu_editor.tool.select"), (byte) -1),
                new ToolDefinition(EditorIcons.FOLDER, EditorI18n.text("arcmenu_editor.tool.group"), EditorProtocol.KIND_GROUP),
                new ToolDefinition(EditorIcons.SQUARE, EditorI18n.text("arcmenu_editor.tool.rectangle"), EditorProtocol.KIND_RECTANGLE),
                new ToolDefinition(EditorIcons.OUTLINE, EditorI18n.text("arcmenu_editor.tool.frame"), EditorProtocol.KIND_FRAME),
                new ToolDefinition(EditorIcons.LINE, EditorI18n.text("arcmenu_editor.tool.line"), EditorProtocol.KIND_LINE),
                new ToolDefinition(EditorIcons.FONT, EditorI18n.text("arcmenu_editor.tool.text"), EditorProtocol.KIND_TEXT),
                new ToolDefinition(EditorIcons.IMAGE, EditorI18n.text("arcmenu_editor.tool.image"), EditorProtocol.KIND_IMAGE),
                new ToolDefinition(EditorIcons.MATERIAL, EditorI18n.text("arcmenu_editor.tool.item"), EditorProtocol.KIND_ITEM),
                new ToolDefinition(EditorIcons.BLOCK, EditorI18n.text("arcmenu_editor.tool.block"), EditorProtocol.KIND_BLOCK)};
        toolButtons.clear();
        int contentTop = panel.y() + 26;
        int columns = panel.width() >= 96 ? 2 : 1;
        int gap = 4;
        int cellWidth = Math.max(36, (panel.width() - 12 - gap * (columns - 1)) / columns);
        int cellHeight = 38;
        graphics.enableScissor(panel.x(), panel.y() + 21, panel.right(), panel.bottom());
        for (int index = 0; index < tools.length; index++) {
            ToolDefinition tool = tools[index];
            int column = index % columns;
            int row = index / columns;
            int x = panel.x() + 6 + column * (cellWidth + gap);
            int y = contentTop + row * (cellHeight + gap) - toolsScroll;
            boolean hover = mouseX >= x && mouseX < x + cellWidth && mouseY >= y && mouseY < y + cellHeight;
            boolean active = tool.kind == EditorProtocol.KIND_IMAGE && assetLibrary.imageMode()
                    || tool.kind == -1 && !assetLibrary.imageMode();
            graphics.fill(x, y, x + cellWidth, y + cellHeight, active ? SELECTED : hover ? 0xFF343B48 : 0xFF242933);
            if (hover) graphics.outline(x, y, cellWidth, cellHeight, 0xFF52647C);
            EditorIcons.draw(graphics, tool.icon, x + (cellWidth - 16) / 2, y + 3,
                    hover ? 0xFF82B7FF : 0xFFD9DFEA);
            graphics.centeredText(font, tool.label, x + cellWidth / 2, y + 23, hover ? TEXT : MUTED);
            if (active) graphics.fill(x, y + cellHeight - 2, x + cellWidth, y + cellHeight, ACCENT);
            toolButtons.add(new ToolButton(tool, new EditorLayout.Rect(x, y, cellWidth, cellHeight)));
        }
        int rows = (tools.length + columns - 1) / columns;
        int y = contentTop + rows * (cellHeight + gap) + 2 - toolsScroll;
        graphics.disableScissor();
        toolContentHeight = Math.max(0, y + toolsScroll - contentTop);
    }

    private void drawViewport(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var view = currentLayout.viewport();
        var virtual = virtualScreen();
        EditorProtocol.SnapshotPacket snapshot = state.snapshot();
        if (snapshot == null) {
            graphics.centeredText(font, EditorI18n.text("arcmenu_editor.viewport.open_instruction"), view.x() + view.width() / 2,
                    view.y() + view.height() / 2, TEXT);
            return;
        }
        graphics.enableScissor(view.x(), view.y(), view.right(), view.bottom());
        List<EditorProtocol.NodeSnapshot> nodes = new ArrayList<>(state.nodes());
        for (EditorProtocol.NodeSnapshot node : nodes) {
            if (state.activeTab() == EditorProtocol.TAB_FRONTEND && !state.selected(node.id())) continue;
            ElementSelectionCalibration.Calibration calibration = selectionCalibration.get(node.kind());
            double drawX = calibration.proxyX(node.x(), node.width());
            double drawY = calibration.proxyY(node.y(), node.height());
            double drawWidth = calibration.proxyWidth(node.width());
            double drawHeight = calibration.proxyHeight(node.height());
            int x = screenX(drawX - drawWidth / 2);
            int y = screenY(drawY + drawHeight / 2);
            int w = Math.max(2, screenX(drawX + drawWidth / 2) - x);
            int h = Math.max(2, screenY(drawY - drawHeight / 2) - y);
            int color = state.selected(node.id()) ? (node.id().equals(state.selectedId()) ? 0xFFFFC857 : 0xFF8FB7ED)
                    : state.activeTab() == EditorProtocol.TAB_BACKEND ? 0xB04096FF : 0x885D88C7;
            graphics.outline(x, y, w, h, color);
            if (node.id().equals(state.selectedId())) {
                graphics.fill(x + w - 3, y + h - 3, x + w + 3, y + h + 3, ACCENT);
            }
        }
        int virtualX = (int) Math.round(virtual.x());
        int virtualY = (int) Math.round(virtual.y());
        int virtualRight = (int) Math.round(virtual.right());
        int virtualBottom = (int) Math.round(virtual.bottom());
        graphics.outline(virtualX, virtualY, Math.max(1, virtualRight - virtualX), Math.max(1, virtualBottom - virtualY), 0xFFB38CFF);
        graphics.text(font, EditorI18n.text("arcmenu_editor.viewport.game", view.width(), view.height()),
                view.x() + 5, view.y() + 5, 0xCCFFFFFF, true);
        String calibration = EditorI18n.text("arcmenu_editor.viewport.calibration",
                format(layout.virtualScreenScale()), format(layout.virtualScreenOffsetX()), format(layout.virtualScreenOffsetY()));
        graphics.text(font, calibration, view.x() + 5, view.y() + 17, 0xDDBF9CFF, true);
        if (virtual.contains(mouseX, mouseY)) {
            double menuX = menuX(mouseX);
            double menuY = menuY(mouseY);
            String coordinates = EditorI18n.text("arcmenu_editor.viewport.coordinates", format(menuX), format(menuY));
            graphics.text(font, coordinates, virtualX + 5, virtualBottom - 13, 0xCCFFFFFF, true);
        }
        graphics.disableScissor();
    }

    private void drawElementManager(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var panel = currentLayout.manager();
        title(graphics, panel, EditorI18n.text("arcmenu_editor.panel.manager"));
        managerButtons.clear();
        int buttonX = panel.x() + 5;
        buttonX = managerButton(graphics, buttonX, panel.y() + 23, EditorIcons.ADD,
                EditorI18n.text(state.activeTab() == EditorProtocol.TAB_FRONTEND
                        ? "arcmenu_editor.manager.new_group" : "arcmenu_editor.manager.new_region"), ManagerAction.CREATE, mouseX, mouseY);
        if (state.activeTab() == EditorProtocol.TAB_FRONTEND) {
            buttonX = managerButton(graphics, buttonX, panel.y() + 23, EditorIcons.FOLDER,
                    EditorI18n.text("arcmenu_editor.manager.group"), ManagerAction.GROUP, mouseX, mouseY);
        }
        buttonX = managerButton(graphics, buttonX, panel.y() + 23, EditorIcons.DUPLICATE,
                EditorI18n.text("arcmenu_editor.manager.duplicate"), ManagerAction.DUPLICATE, mouseX, mouseY);
        buttonX = managerButton(graphics, buttonX, panel.y() + 23, EditorIcons.PASTE,
                EditorI18n.text("arcmenu_editor.manager.paste"), ManagerAction.PASTE, mouseX, mouseY);
        String selectedCount = state.selectedIds().isEmpty() ? Integer.toString(state.nodes().size())
                : state.selectedIds().size() + " / " + state.nodes().size();
        graphics.text(font, selectedCount, panel.right() - font.width(selectedCount) - 7, panel.y() + 28, MUTED, false);
        nodeRows.clear();
        Map<String, List<EditorProtocol.NodeSnapshot>> children = new HashMap<>();
        for (EditorProtocol.NodeSnapshot node : state.nodes()) {
            children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
        }
        int[] row = {0};
        appendRows(children, "", 0, row);
        int yStart = panel.y() + 48 - managerScroll;
        boolean managerDrag = !dragCandidateNode.isBlank()
                && Math.hypot(mouseX - dragStartX, mouseY - dragStartY) >= 4.0;
        List<String> dragSelection = managerDrag ? selectedOrder() : List.of();
        boolean hoveringRow = false;
        graphics.enableScissor(panel.x(), panel.y() + 46, panel.right(), panel.bottom());
        for (NodeRow nodeRow : nodeRows) {
            int y = yStart + nodeRow.index * 19;
            if (y + 19 < panel.y() + 46 || y >= panel.bottom()) continue;
            boolean selected = state.selected(nodeRow.node.id());
            boolean hover = panel.contains(mouseX, mouseY) && mouseY >= y && mouseY < y + 19;
            if (hover) hoveringRow = true;
            boolean groupDrop = managerDrag && hover && canMoveIntoGroup(dragSelection, nodeRow.node);
            boolean levelDrop = managerDrag && hover && !selected
                    && nodeRow.node.kind() != EditorProtocol.KIND_GROUP
                    && canMoveToParent(dragSelection, nodeRow.node.parentId());
            if (selected || hover) graphics.fill(panel.x() + 3, y, panel.right() - 3, y + 19,
                    groupDrop ? 0xFF24513E : selected ? SELECTED : 0xFF2B303A);
            if (groupDrop) graphics.outline(panel.x() + 3, y, panel.width() - 6, 19, 0xFF65D68A);
            if (levelDrop) graphics.fill(panel.x() + 3, y, panel.right() - 3, y + 2, 0xFF65D68A);
            if (nodeRow.node.id().equals(state.selectedId())) graphics.fill(panel.x() + 3, y, panel.x() + 5, y + 19, ACCENT);
            int x = panel.x() + 8 + nodeRow.depth * 13;
            if (state.hasChildren(nodeRow.node.id())) {
                graphics.text(font, state.expanded().contains(nodeRow.node.id()) ? "-" : "+", x + 2, y + 5, MUTED, false);
                x += 11;
            } else x += 11;
            EditorIcons.draw(graphics, kindIcon(nodeRow.node.kind()), x - 2, y + 2, kindColor(nodeRow.node.kind()));
            graphics.text(font, ellipsize(nodeRow.node.id(), Math.max(10, (panel.width() - x - 55) / 6)), x + 16, y + 5, TEXT, false);
            EditorIcons.draw(graphics, nodeRow.node.visible() ? EditorIcons.VISIBLE : EditorIcons.INVISIBLE,
                    panel.right() - 38, y + 2, nodeRow.node.visible() ? 0xFF9BE2A0 : 0xFF697382);
            EditorIcons.draw(graphics, state.locked(nodeRow.node.id()) ? EditorIcons.LOCKED : EditorIcons.UNLOCKED,
                    panel.right() - 20, y + 2, 0xFFB4BDCA);
            nodeRow.screenY = y;
        }
        graphics.disableScissor();
        if (managerDrag && state.activeTab() == EditorProtocol.TAB_FRONTEND
                && panel.contains(mouseX, mouseY) && mouseY >= panel.y() + 46 && !hoveringRow
                && canMoveToParent(dragSelection, "")) {
            graphics.outline(panel.x() + 3, panel.y() + 46, panel.width() - 6,
                    Math.max(1, panel.height() - 49), 0xFF65D68A);
        }
    }

    private int managerButton(GuiGraphicsExtractor graphics, int x, int y, EditorIcons.Icon icon,
                              String tooltip, ManagerAction action, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 21;
        graphics.fill(x, y, x + 22, y + 21, hover ? 0xFF343B48 : 0xFF242933);
        EditorIcons.draw(graphics, icon, x + 3, y + 2, hover ? 0xFF82B7FF : 0xFFD4DBE6);
        managerButtons.add(new ManagerButton(action, new EditorLayout.Rect(x, y, 22, 21)));
        if (hover) hoverTooltip = new HoverTooltip(tooltip, x, y + 23);
        return x + 25;
    }

    private void drawHoverTooltip(GuiGraphicsExtractor graphics) {
        if (hoverTooltip == null || contextMenu != null) return;
        int tooltipWidth = Math.min(width - 4, font.width(hoverTooltip.text) + 10);
        int tooltipHeight = 19;
        int x = Math.max(2, Math.min(width - tooltipWidth - 2, hoverTooltip.x));
        int y = Math.max(2, Math.min(height - tooltipHeight - 2, hoverTooltip.y));
        graphics.fill(x + 3, y + 3, x + tooltipWidth + 3, y + tooltipHeight + 3, 0x77000000);
        graphics.fill(x, y, x + tooltipWidth, y + tooltipHeight, 0xFF161920);
        graphics.outline(x, y, tooltipWidth, tooltipHeight, BORDER);
        graphics.text(font, hoverTooltip.text, x + 5, y + 6, TEXT, false);
    }

    private void appendRows(Map<String, List<EditorProtocol.NodeSnapshot>> children, String parent, int depth, int[] row) {
        for (EditorProtocol.NodeSnapshot node : children.getOrDefault(parent, List.of())) {
            NodeRow value = new NodeRow(node, depth, row[0]++);
            nodeRows.add(value);
            if (state.expanded().contains(node.id())) appendRows(children, node.id(), depth + 1, row);
        }
    }

    private void drawProperties(GuiGraphicsExtractor graphics) {
        var panel = currentLayout.properties();
        title(graphics, panel, EditorI18n.text("arcmenu_editor.panel.properties"));
        EditorProtocol.NodeSnapshot node = state.find(state.selectedId());
        if (node == null) {
            graphics.centeredText(font, EditorI18n.text("arcmenu_editor.properties.select"), panel.x() + panel.width() / 2, panel.y() + 43, MUTED);
            return;
        }
        graphics.enableScissor(panel.x(), panel.y() + 21, panel.right(), panel.bottom() - 21);
        for (PropertyField field : propertyFields) {
            graphics.text(font, propertyLabel(field.property.key()), panel.x() + 8, field.y + 7, MUTED, false);
            graphics.fill(field.rect.x(), field.rect.y(), field.rect.right(), field.rect.bottom(),
                    field.box.isFocused() ? 0xFF223A5B : 0xFF171A20);
            int stripe = propertyStripe(field.property.key());
            graphics.fill(field.rect.x(), field.rect.y(), field.rect.x() + 3, field.rect.bottom(), stripe);
            if (isNumeric(field.property)) {
                EditorIcons.draw(graphics, EditorIcons.CHEVRON_LEFT, field.rect.x() + 6, field.rect.y() + 2, 0x887F8997);
                EditorIcons.draw(graphics, EditorIcons.CHEVRON_RIGHT, field.rect.right() - 12, field.rect.y() + 2, 0x887F8997);
            } else if (field.property.type() == EditorProtocol.PROPERTY_BOOLEAN || field.property.type() == EditorProtocol.PROPERTY_CHOICE) {
                EditorIcons.draw(graphics, EditorIcons.CHEVRON_RIGHT, field.rect.right() - 12, field.rect.y() + 2, 0xAA8EBBFA);
            }
        }
        graphics.disableScissor();
        graphics.text(font, EditorI18n.text("arcmenu_editor.properties.help"), panel.x() + 8, panel.bottom() - 15, 0xFF788394, false);
    }

    private void drawTemplates(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (assetLibrary.imageMode()) {
            templateCards.clear();
            drawImages(graphics, mouseX, mouseY);
            return;
        }
        var panel = currentLayout.templates();
        title(graphics, panel, EditorI18n.text("arcmenu_editor.panel.templates"));
        graphics.text(font, EditorI18n.text("arcmenu_editor.templates.help"),
                panel.x() + 10, panel.y() + 25, MUTED, false);
        int cardY = panel.y() + 45 - templateScroll;
        int cardW = 126;
        templateCards.clear();
        List<EditorProtocol.TemplateSnapshot> templates = state.snapshot() == null ? List.of() : state.snapshot().templates();
        int columns = Math.max(1, (panel.width() - 18) / (cardW + 9));
        int cardCount = Math.max(1, templates.size());
        templateContentHeight = ((cardCount + columns - 1) / columns) * 86;
        graphics.enableScissor(panel.x(), panel.y() + 43, panel.right(), panel.bottom());
        for (int i = 0; i < cardCount; i++) {
            int x = panel.x() + 10 + (i % columns) * (cardW + 9);
            int y = cardY + (i / columns) * 86;
            EditorProtocol.TemplateSnapshot template = templates.isEmpty() ? null : templates.get(i);
            boolean visible = y + 78 >= panel.y() + 43 && y < panel.bottom();
            if (!visible) continue;
            boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= Math.max(y, panel.y() + 43) && mouseY < Math.min(y + 78, panel.bottom());
            graphics.fill(x, y, x + cardW, y + 78, hover ? 0xFF303846 : 0xFF242933);
            graphics.outline(x, y, cardW, 78, hover ? 0xFF6EA8F5 : BORDER);
            graphics.fill(x + 6, y + 6, x + cardW - 6, y + 49, 0xFF11141A);
            if (template == null) {
                graphics.centeredText(font, EditorI18n.text("arcmenu_editor.templates.drop"), x + cardW / 2, y + 23, 0xFF8FB7ED);
            } else {
                EditorIcons.draw(graphics, EditorIcons.FOLDER, x + cardW / 2 - 28, y + 16, 0xFF6CC7EA);
                graphics.text(font, EditorI18n.text("arcmenu_editor.common.elements", template.nodeCount()), x + cardW / 2 - 8, y + 23, 0xFF6CC7EA, false);
            }
            graphics.text(font, template == null ? EditorI18n.text("arcmenu_editor.templates.none") : ellipsize(template.id(), 18), x + 7, y + 61, TEXT, false);
            if (template != null) templateCards.add(new TemplateCard(template, new EditorLayout.Rect(x, y, cardW, 78)));
        }
        graphics.disableScissor();
        if (!dragCandidateNode.isBlank() && currentLayout.templates().contains(mouseX, mouseY)) {
            graphics.outline(panel.x() + 4, panel.y() + 4, panel.width() - 8, panel.height() - 8, ACCENT);
        }
        if (!pendingTemplateNode.isBlank()) drawTemplateDialog(graphics);
    }


    private int folderPaneWidth() {
        return Math.max(86, Math.min(140, currentLayout.templates().width() / 4));
    }

    private void drawImages(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var panel = currentLayout.templates();
        title(graphics, panel, EditorI18n.text("arcmenu_editor.images.library"));
        String help = EditorI18n.text("arcmenu_editor.images.help");
        if (panel.width() > font.width(help) + 110)
            graphics.text(font, help, panel.right() - font.width(help) - 8, panel.y() + 7, MUTED, false);
        int divider = panel.x() + folderPaneWidth();
        int top = panel.y() + 43;
        graphics.fill(panel.x(), panel.y() + 21, panel.right(), top, 0xFF141820);
        graphics.fill(panel.x(), top, divider, panel.bottom(), 0xFF151920);
        graphics.fill(divider, panel.y() + 21, divider + 1, panel.bottom(), BORDER);
        EditorIcons.draw(graphics, EditorIcons.CHEVRON_LEFT, panel.x() + 8, panel.y() + 24,
                assetLibrary.folder().isBlank() ? 0xFF515B6B : TEXT);
        String breadcrumb = assetLibrary.folder().isBlank() ? EditorI18n.text("arcmenu_editor.images.root")
                : assetLibrary.folder().replace("/", " / ");
        graphics.enableScissor(panel.x() + 22, panel.y() + 21, panel.right() - 70, top);
        graphics.text(font, breadcrumb, panel.x() + 24, panel.y() + 28, TEXT, false);
        graphics.disableScissor();
        boolean canInsert = assetLibrary.selected() != null && state.activeTab() == EditorProtocol.TAB_FRONTEND;
        graphics.fill(panel.right() - 65, panel.y() + 24, panel.right() - 6, top - 3, canInsert ? SELECTED : PANEL_ALT);
        graphics.centeredText(font, EditorI18n.text("arcmenu_editor.images.insert"),
                panel.right() - 35, panel.y() + 28, canInsert ? TEXT : MUTED);

        folderRows.clear();
        int visibleHeight = panel.bottom() - top;
        folderScroll = Math.max(0, Math.min(Math.max(0, assetLibrary.folders().size() * 20 - visibleHeight), folderScroll));
        graphics.enableScissor(panel.x(), top, divider, panel.bottom());
        int index = 0;
        for (String path : assetLibrary.folders()) {
            int y = top + index++ * 20 - folderScroll;
            if (y + 20 <= top || y >= panel.bottom()) continue;
            boolean chosen = path.equals(assetLibrary.folder());
            if (chosen) graphics.fill(panel.x() + 3, y, divider - 3, y + 20, SELECTED);
            int depth = path.isEmpty() ? 0 : path.split("/").length;
            int x = panel.x() + 6 + Math.min(depth, 4) * 9;
            EditorIcons.draw(graphics, EditorIcons.FOLDER, x, y + 2, chosen ? 0xFF83B8FF : 0xFFA5ADBD);
            String label = path.isEmpty() ? EditorI18n.text("arcmenu_editor.images.root") : EditorAssetLibrary.name(path);
            graphics.text(font, font.plainSubstrByWidth(label, Math.max(12, divider - x - 24)), x + 19, y + 6, TEXT, false);
            folderRows.add(new FolderRow(path, new EditorLayout.Rect(panel.x(), y, folderPaneWidth(), 20)));
            if (mouseX < divider && mouseX >= panel.x() && mouseY >= Math.max(y, top) && mouseY < Math.min(y + 20, panel.bottom()))
                hoverTooltip = new HoverTooltip(path.isEmpty() ? label : path, mouseX + 8, mouseY - 20);
        }
        graphics.disableScissor();

        List<EditorAssetLibrary.Entry> entries = assetLibrary.entries();
        int gridX = divider + 8;
        int gridWidth = panel.right() - gridX - 8;
        int columns = Math.max(1, (gridWidth + 7) / 115);
        int cardW = Math.min(150, Math.max(50, (gridWidth - (columns - 1) * 7) / columns));
        int rowHeight = 76;
        imageContentHeight = ((entries.size() + columns - 1) / columns) * rowHeight + 4;
        imageScroll = Math.max(0, Math.min(Math.max(0, imageContentHeight - visibleHeight), imageScroll));
        assetCards.clear();
        graphics.enableScissor(divider + 1, top, panel.right(), panel.bottom());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            int x = gridX + (i % columns) * (cardW + 7);
            int y = top + 4 + (i / columns) * rowHeight - imageScroll;
            if (y + 70 <= top || y >= panel.bottom()) continue;
            boolean selected = !entry.directory() && entry.image().path().equals(assetLibrary.selectedPath());
            boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= Math.max(y, top) && mouseY < Math.min(y + 70, panel.bottom());
            graphics.fill(x, y, x + cardW, y + 70, selected ? SELECTED : hover ? 0xFF303846 : 0xFF242933);
            if (selected || hover) graphics.outline(x, y, cardW, 70, selected ? ACCENT : 0xFF52647C);
            var preview = new EditorLayout.Rect(x + 4, y + 4, cardW - 8, 43);
            if (entry.directory()) {
                EditorIcons.draw(graphics, EditorIcons.FOLDER, x + (cardW - 16) / 2, y + 17, 0xFF8FB7ED);
            } else {
                // Checkerboard makes transparent artwork readable without altering its texture.
                for (int cy = preview.y(); cy < preview.bottom(); cy += 6) {
                    for (int cx = preview.x(); cx < preview.right(); cx += 6) {
                        int color = (((cx - preview.x()) / 6 + (cy - preview.y()) / 6) & 1) == 0 ? 0xFF343A45 : 0xFF282D37;
                        graphics.fill(cx, cy, Math.min(cx + 6, preview.right()), Math.min(cy + 6, preview.bottom()), color);
                    }
                }
                if (!EditorImagePreview.draw(graphics, entry.image(), preview)) {
                    graphics.fill(preview.x(), preview.y(), preview.right(), preview.bottom(), 0xFF171B22);
                    graphics.centeredText(font, font.plainSubstrByWidth(EditorI18n.text("arcmenu_editor.images.unavailable"), cardW - 8),
                            x + cardW / 2, y + 21, MUTED);
                }
            }
            graphics.text(font, font.plainSubstrByWidth(entry.name(), cardW - 10), x + 5, y + 50, TEXT, false);
            String detail = entry.directory() ? EditorI18n.text("arcmenu_editor.images.folder")
                    : entry.image().width() + " × " + entry.image().height();
            graphics.text(font, detail, x + 5, y + 61, MUTED, false);
            assetCards.add(new AssetCard(entry, new EditorLayout.Rect(x, y, cardW, 70)));
            if (hover) hoverTooltip = new HoverTooltip(entry.path(), mouseX + 8, mouseY - 20);
        }
        if (entries.isEmpty()) graphics.centeredText(font, EditorI18n.text("arcmenu_editor.images.none"),
                divider + (panel.right() - divider) / 2, top + 25, MUTED);
        graphics.disableScissor();
    }

    private boolean clickImages(double x, double y, boolean doubleClick) {
        var panel = currentLayout.templates();
        if (y >= panel.y() + 21 && y < panel.y() + 43) {
            if (x < panel.x() + 22) { assetLibrary.up(); imageScroll = 0; }
            else if (x >= panel.right() - 65) insertSelectedImage();
            return true;
        }
        if (y < panel.y() + 43) return true;
        for (FolderRow row : folderRows) {
            if (row.rect.contains(x, y)) { assetLibrary.openFolder(row.path); imageScroll = 0; return true; }
        }
        for (AssetCard card : assetCards) {
            if (!card.rect.contains(x, y)) continue;
            if (card.entry.directory()) {
                assetLibrary.openFolder(card.entry.path());
                imageScroll = 0;
            } else {
                assetLibrary.select(card.entry.image().path());
                if (doubleClick) insertSelectedImage();
            }
            return true;
        }
        return true;
    }

    private void insertSelectedImage() {
        var image = assetLibrary.selected();
        if (image != null && state.activeTab() == EditorProtocol.TAB_FRONTEND)
            create(EditorProtocol.KIND_IMAGE, image.path());
    }
    private void drawTemplateDialog(GuiGraphicsExtractor graphics) {
        int dialogWidth = 260;
        int x = (width - dialogWidth) / 2;
        int y = height / 2 - 48;
        graphics.fill(x, y, x + dialogWidth, y + 96, 0xFC171A20);
        graphics.outline(x, y, dialogWidth, 96, ACCENT);
        graphics.text(font, EditorI18n.text("arcmenu_editor.templates.save_title"), x + 12, y + 12, TEXT, false);
        graphics.text(font, EditorI18n.text("arcmenu_editor.templates.name"), x + 12, y + 34, MUTED, false);
        graphics.text(font, EditorI18n.text("arcmenu_editor.templates.submit_help"), x + 12, y + 75, MUTED, false);
    }

    private void title(GuiGraphicsExtractor graphics, EditorLayout.Rect panel, String value) {
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.y() + 20, HEADER);
        graphics.text(font, value, panel.x() + 7, panel.y() + 6, TEXT, false);
        graphics.fill(panel.x(), panel.y() + 19, panel.right(), panel.y() + 20, BORDER);
    }

    private void property(GuiGraphicsExtractor graphics, EditorLayout.Rect panel, int y, String label, String value) {
        graphics.text(font, label, panel.x() + 8, y + 5, MUTED, false);
        int x = panel.x() + 72;
        graphics.fill(x, y, panel.right() - 8, y + 20, 0xFF171A20);
        graphics.text(font, ellipsize(value, 35), x + 6, y + 6, TEXT, false);
    }

    private void property2(GuiGraphicsExtractor graphics, EditorLayout.Rect panel, int y, String label, double a, double b) {
        graphics.text(font, label, panel.x() + 8, y, MUTED, false);
        int x = panel.x() + 8;
        int w = (panel.width() - 25) / 2;
        smallField(graphics, x, y + 12, w, "X  " + format(a), 0xFFE95C66);
        smallField(graphics, x + w + 7, y + 12, w, "Y  " + format(b), 0xFF56D364);
    }

    private void property3(GuiGraphicsExtractor graphics, EditorLayout.Rect panel, int y, String label, double a, double b, double c) {
        graphics.text(font, label, panel.x() + 8, y, MUTED, false);
        int x = panel.x() + 8;
        int w = (panel.width() - 30) / 3;
        smallField(graphics, x, y + 12, w, "X " + format(a), 0xFFE95C66);
        smallField(graphics, x + w + 5, y + 12, w, "Y " + format(b), 0xFF56D364);
        smallField(graphics, x + (w + 5) * 2, y + 12, w, "Z " + format(c), 0xFF55A6F1);
    }

    private void smallField(GuiGraphicsExtractor graphics, int x, int y, int width, String value, int stripe) {
        graphics.fill(x, y, x + width, y + 20, 0xFF171A20);
        graphics.fill(x, y, x + 2, y + 20, stripe);
        graphics.centeredText(font, ellipsize(value, 12), x + width / 2, y + 6, TEXT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (currentLayout == null) return super.mouseClicked(event, doubleClick);
        if (calibrationSettingsOpen) return clickCalibrationSettings(event, doubleClick);
        double x = event.x();
        double y = event.y();
        if (contextMenu != null) {
            ContextEntry entry = contextMenu.entryAt(x, y);
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && entry != null) {
                contextMenu = null;
                if (entry.enabled) entry.action.run();
                return true;
            }
            contextMenu = null;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        }
        if (propertyDrag != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            pendingPropertyValue = propertyDrag.initialValue;
            propertyDrag.dragging = true;
            propertyReleased = true;
            flushPropertyDrag(true);
            return true;
        }
        if (!pendingTemplateNode.isBlank()) return super.mouseClicked(event, doubleClick);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && currentLayout.templates().contains(x, y)) {
            if (assetLibrary.imageMode()) return true;
            TemplateCard card = templateCards.stream().filter(value -> value.rect.contains(x, y)).findFirst().orElse(null);
            if (card != null) openTemplateContext(x, y, card);
            return true;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && currentLayout.manager().contains(x, y)) {
            NodeRow row = nodeRowAt(y);
            if (row != null && !state.selected(row.node.id())) {
                state.selectOnly(row.node.id());
                rebuildWidgets();
            }
            openManagerContext(x, y, row);
            return true;
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        if (y < EditorLayout.HEADER_HEIGHT) return clickHeader(x, y);
        if (Math.abs(x - layout.toolsWidth()) <= 3) { resizeTarget = ResizeTarget.TOOLS; return true; }
        if (Math.abs(x - (width - layout.inspectorWidth())) <= 3) { resizeTarget = ResizeTarget.INSPECTOR; return true; }
        if (x >= layout.toolsWidth() && x < width - layout.inspectorWidth()
                && Math.abs(y - (height - layout.templatesHeight())) <= 3) {
            resizeTarget = ResizeTarget.TEMPLATES; return true;
        }
        for (PropertyField field : propertyFields) {
            if (!field.rect.contains(x, y)) continue;
            if (propertyRequestInFlight || propertyReleased) return true;
            if (isNumeric(field.property)) {
                double value = parseNumber(field.box.getValue(), field.property.value());
                if (event.hasControlDown()) {
                    setProperty(field, formatPropertyValue(field.property, Math.rint(value)));
                    return true;
                }
                propertyDrag = new PropertyDrag(field.nodeId, field.property, value, x, y, nextGestureId++);
                pendingPropertyValue = value;
                sentPropertyValue = Double.NaN;
                propertyRequestInFlight = false;
                propertyFinalSent = false;
                propertyReleased = false;
                lastPropertySendNanos = 0;
                unfocusPropertyFields();
                return true;
            }
            if (field.property.type() == EditorProtocol.PROPERTY_BOOLEAN) {
                setProperty(field, Boolean.toString(!Boolean.parseBoolean(field.property.value())));
                return true;
            }
            if (field.property.type() == EditorProtocol.PROPERTY_CHOICE && !field.property.choices().isEmpty()) {
                int current = field.property.choices().indexOf(field.property.value());
                int direction = event.hasShiftDown() ? -1 : 1;
                int next = Math.floorMod(current + direction, field.property.choices().size());
                setProperty(field, field.property.choices().get(next));
                return true;
            }
            focusProperty(field);
            return true;
        }
        if (currentLayout.tools().contains(x, y) && y >= currentLayout.tools().y() + 21) {
            for (ToolButton button : toolButtons) {
                if (button.rect.contains(x, y)) {
                    assetLibrary.selectTool(button.tool.kind);
                    if (button.tool.kind == EditorProtocol.KIND_IMAGE) { contextMenu = null; }
                    else if (button.tool.kind >= 0) create(button.tool.kind, "");
                    else state.status(EditorI18n.text("arcmenu_editor.status.select_tool"));
                    return true;
                }
            }
        }
        if (currentLayout.templates().contains(x, y)) {
            if (assetLibrary.imageMode()) return clickImages(x, y, doubleClick);
            for (TemplateCard card : templateCards) {
                if (card.rect.contains(x, y)) {
                    draggingTemplate = card.template.id();
                    dragStartX = x; dragStartY = y;
                    return true;
                }
            }
        }
        if (currentLayout.manager().contains(x, y)) {
            for (ManagerButton button : managerButtons) {
                if (button.rect.contains(x, y)) {
                    performManagerAction(button.action);
                    return true;
                }
            }
            for (NodeRow row : nodeRows) {
                if (y >= row.screenY && y < row.screenY + 19) {
                    int toggleX = currentLayout.manager().x() + 8 + row.depth * 13;
                    if (x >= currentLayout.manager().right() - 36 && x < currentLayout.manager().right() - 22) {
                        EditorProtocol.PropertySnapshot visible = row.node.properties().stream()
                                .filter(property -> property.key().equals("visible")).findFirst().orElse(null);
                        if (visible != null) ArcMenuEditorClient.send(new EditorProtocol.SetPropertyPacket(
                                state.snapshot().revision(), state.activeTab(), row.node.id(), "visible",
                                Boolean.toString(!Boolean.parseBoolean(visible.value()))));
                    } else if (x >= currentLayout.manager().right() - 22) {
                        state.toggleLocked(row.node.id());
                    } else if (state.hasChildren(row.node.id()) && x >= toggleX && x < toggleX + 12) {
                        if (!state.expanded().add(row.node.id())) state.expanded().remove(row.node.id());
                    } else {
                        if (event.hasShiftDown()) {
                            state.selectRange(nodeRows.stream().map(value -> value.node.id()).toList(), row.node.id());
                        } else if (event.hasControlDown()) {
                            state.toggleSelection(row.node.id());
                        } else if (!state.selected(row.node.id())) {
                            state.selectOnly(row.node.id());
                        }
                        dragCandidateNode = state.selected(row.node.id()) ? row.node.id() : "";
                        dragStartX = x; dragStartY = y;
                        rebuildWidgets();
                    }
                    return true;
                }
            }
        }
        if (virtualScreen().contains(x, y) && state.snapshot() != null) {
            EditorProtocol.NodeSnapshot selected = state.find(state.selectedId());
            if (selected != null && !state.locked(selected.id()) && resizeHandleHit(selected, x, y)) {
                resizingNode = true;
                draggingNode = selected.id();
                beginLiveGesture();
                return true;
            }
            EditorProtocol.NodeSnapshot hit = hitNode(x, y);
            if (hit != null) {
                if (event.hasControlDown()) {
                    state.toggleSelection(hit.id());
                    rebuildWidgets();
                    return true;
                }
                if (!state.selected(hit.id())) {
                    state.selectOnly(hit.id());
                    rebuildWidgets();
                }
                boolean locked = state.locked(hit.id());
                resizingNode = !locked && resizeHandleHit(hit, x, y);
                if (!locked) draggingNode = hit.id();
                grabOffsetX = menuX(x) - hit.x();
                grabOffsetY = menuY(y) - hit.y();
                if (resizingNode || !draggingNode.isBlank()) beginLiveGesture();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickHeader(double x, double y) {
        int at = 68;
        int next = at + font.width(EditorI18n.text("arcmenu_editor.header.frontend")) + 14;
        if (x >= at && x < next) { switchTab(EditorProtocol.TAB_FRONTEND); return true; }
        at = next + 3; next = at + font.width(EditorI18n.text("arcmenu_editor.header.backend")) + 14;
        if (x >= at && x < next) { switchTab(EditorProtocol.TAB_BACKEND); return true; }
        at = next + 11;
        HeaderAction[] actions = HeaderAction.values();
        for (HeaderAction action : actions) {
            String label = EditorI18n.text(action.translationKey);
            next = at + font.width(label) + 14;
            if (x >= at && x < next) {
                performHeaderAction(action);
                return true;
            }
            at = next + 3;
        }
        if (x >= width - 24) { onClose(); return true; }
        return true;
    }

    private boolean clickCalibrationSettings(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        double x = event.x();
        double y = event.y();
        EditorLayout.Rect panel = calibrationPanel();
        if (x >= panel.right() - 26 && x < panel.right() && y >= panel.y() && y < panel.y() + 30) {
            closeCalibrationSettings();
            return true;
        }
        for (int index = 0; index < ElementSelectionCalibration.KINDS.length; index++) {
            if (!calibrationKindRect(index).contains(x, y)) continue;
            byte nextKind = ElementSelectionCalibration.KINDS[index];
            if (nextKind != calibrationKind && applyCalibrationFields(true)) {
                calibrationKind = nextKind;
                rebuildWidgets();
            }
            return true;
        }
        if (calibrationResetRect().contains(x, y)) {
            selectionCalibration.reset(calibrationKind);
            selectionCalibration.save(ArcMenuEditorClient.selectionCalibrationFile());
            state.status(EditorI18n.text("arcmenu_editor.status.calibration_reset", calibrationKindLabel(calibrationKind)));
            rebuildWidgets();
            return true;
        }
        if (calibrationSaveRect().contains(x, y)) {
            closeCalibrationSettings();
            return true;
        }
        if (panel.contains(x, y)) return super.mouseClicked(event, doubleClick);
        return true;
    }

    private boolean keyPressedCalibrationSettings(KeyEvent event) {
        if (event.isEscape()) {
            closeCalibrationSettings();
            return true;
        }
        if (event.isConfirmation() && getFocused() instanceof EditBox) {
            if (applyCalibrationFields(true)) {
                setFocused(null);
                rebuildWidgets();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private void openCalibrationSettings() {
        EditorProtocol.NodeSnapshot selected = state.find(state.selectedId());
        if (selected != null && supportsCalibration(selected.kind())) calibrationKind = selected.kind();
        calibrationSettingsOpen = true;
        contextMenu = null;
        state.status(EditorI18n.text("arcmenu_editor.status.calibration_editing"));
        rebuildWidgets();
    }

    private void closeCalibrationSettings() {
        if (!applyCalibrationFields(true)) return;
        calibrationSettingsOpen = false;
        state.status(EditorI18n.text("arcmenu_editor.status.calibration_saved"));
        rebuildWidgets();
    }

    private boolean applyCalibrationFields(boolean save) {
        if (calibrationFields.size() != 4) return true;
        double[] values = new double[4];
        try {
            for (CalibrationField field : calibrationFields) {
                values[field.index] = Double.parseDouble(field.box.getValue().trim());
                if (!Double.isFinite(values[field.index])) throw new NumberFormatException();
            }
        } catch (NumberFormatException ignored) {
            state.error(EditorI18n.text("arcmenu_editor.status.calibration_invalid_number"));
            return false;
        }
        if (values[2] <= 0.0 || values[3] <= 0.0) {
            state.error(EditorI18n.text("arcmenu_editor.status.calibration_scale_positive"));
            return false;
        }
        selectionCalibration.set(calibrationKind, values[0], values[1], values[2], values[3]);
        if (save) selectionCalibration.save(ArcMenuEditorClient.selectionCalibrationFile());
        return true;
    }

    private static boolean supportsCalibration(byte kind) {
        for (byte supported : ElementSelectionCalibration.KINDS) if (supported == kind) return true;
        return false;
    }

    private void switchTab(byte tab) {
        if (state.snapshot() == null || state.activeTab() == tab) return;
        state.activeTab(tab);
        propertyScroll = 0;
        rebuildWidgets();
        ArcMenuEditorClient.send(new EditorProtocol.SwitchTabPacket(tab));
    }

    private void create(byte kind, String imageSource) {
        EditorProtocol.SnapshotPacket snapshot = state.snapshot();
        if (snapshot == null) return;
        String parent = "";
        EditorProtocol.NodeSnapshot selected = state.find(state.selectedId());
        if (state.activeTab() == EditorProtocol.TAB_FRONTEND && selected != null && selected.kind() == EditorProtocol.KIND_GROUP) {
            parent = selected.id();
            state.expanded().add(parent);
        }
        ArcMenuEditorClient.send(new EditorProtocol.CreatePacket(snapshot.revision(), state.activeTab(), kind, parent, imageSource));
    }

    private void performHeaderAction(HeaderAction action) {
        if (action == HeaderAction.SETTINGS) {
            openCalibrationSettings();
            return;
        }
        EditorProtocol.SnapshotPacket snapshot = state.snapshot();
        if (snapshot == null) return;
        switch (action) {
            case UNDO -> ArcMenuEditorClient.send(new EditorProtocol.UndoPacket(snapshot.revision()));
            case REDO -> ArcMenuEditorClient.send(new EditorProtocol.RedoPacket(snapshot.revision()));
            case SAVE -> ArcMenuEditorClient.send(new EditorProtocol.SavePacket(snapshot.revision()));
            case APPLY -> ArcMenuEditorClient.send(new EditorProtocol.ApplyPacket(snapshot.revision()));
            case SETTINGS -> { }
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (propertyDrag != null) {
            double dx = event.x() - propertyDrag.startX;
            if (!propertyDrag.dragging && Math.abs(dx) >= 3.0) propertyDrag.dragging = true;
            if (propertyDrag.dragging) {
                pendingPropertyValue = normalizePropertyValue(propertyDrag.property,
                        propertyDrag.initialValue + dx * propertyStep(propertyDrag.property, event));
                propertyField(propertyDrag.property.key()).ifPresent(field -> field.box.setValue(formatPropertyValue(
                        propertyDrag.property, pendingPropertyValue)));
                flushPropertyDrag(false);
            }
            return true;
        }
        if (resizeTarget != ResizeTarget.NONE) {
            switch (resizeTarget) {
                case TOOLS -> layout.toolsWidth((int) Math.round(event.x()));
                case INSPECTOR -> layout.inspectorWidth((int) Math.round(width - event.x()));
                case TEMPLATES -> layout.templatesHeight((int) Math.round(height - event.y()));
                default -> { }
            }
            return true;
        }
        if (!dragCandidateNode.isBlank() || !draggingTemplate.isBlank()) return true;
        if (resizingNode && state.snapshot() != null) {
            pendingDragPointer = boundedPointer(event.x(), event.y(), true);
            flushLiveDrag(false);
            return true;
        }
        if (!draggingNode.isBlank() && state.snapshot() != null) {
            pendingDragPointer = boundedPointer(event.x(), event.y(), false);
            flushLiveDrag(false);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (propertyDrag != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!propertyDrag.dragging) {
                PropertyDrag clicked = propertyDrag;
                finishPropertyGesture();
                propertyField(clicked.property.key()).ifPresent(this::focusProperty);
            } else {
                propertyReleased = true;
                flushPropertyDrag(true);
            }
            return true;
        }
        if (activeGestureId != 0) {
            if (pendingDragPointer != null && currentLayout != null) {
                var virtual = virtualScreen();
                double x = Math.max(virtual.x(), Math.min(virtual.right() - 0.001, event.x()));
                double y = Math.max(virtual.y(), Math.min(virtual.bottom() - 0.001, event.y()));
                pendingDragPointer = resizingNode ? resizePointer(x, y) : pointer(x, y);
            }
            dragReleased = true;
            if (pendingDragPointer == null) finishLiveGesture();
            else flushLiveDrag(true);
            resizeTarget = ResizeTarget.NONE;
            return true;
        }
        boolean managerDragged = !dragCandidateNode.isBlank() && Math.hypot(event.x() - dragStartX, event.y() - dragStartY) >= 4.0;
        if (managerDragged && !assetLibrary.imageMode() && currentLayout != null && currentLayout.templates().contains(event.x(), event.y())) {
            List<String> selected = selectedOrder();
            if (selected.size() == 1) {
                EditorProtocol.NodeSnapshot node = state.find(selected.getFirst());
                if (node != null && node.kind() == EditorProtocol.KIND_GROUP) {
                    pendingTemplateNode = node.id();
                    dragCandidateNode = "";
                    rebuildWidgets();
                    return true;
                }
            }
            state.status(EditorI18n.text("arcmenu_editor.status.only_single_group"));
        } else if (managerDragged && currentLayout != null && currentLayout.manager().contains(event.x(), event.y())
                && state.snapshot() != null) {
            NodeRow target = nodeRowAt(event.y());
            List<String> selected = selectedOrder();
            if (target != null && target.node.kind() == EditorProtocol.KIND_GROUP) {
                if (canMoveIntoGroup(selected, target.node)) {
                    state.expanded().add(target.node.id());
                    if (sameParent(selected, target.node.id())) {
                        ArcMenuEditorClient.send(new EditorProtocol.ReorderPacket(state.snapshot().revision(),
                                state.activeTab(), selected, ""));
                    } else {
                        ArcMenuEditorClient.send(new EditorProtocol.ReparentPacket(state.snapshot().revision(),
                                selected, target.node.id(), ""));
                    }
                } else {
                    state.status(EditorI18n.text("arcmenu_editor.status.invalid_group_drop"));
                }
            } else if (target != null && !selected.contains(target.node.id())) {
                if (sameParent(selected, target.node.parentId())) {
                    ArcMenuEditorClient.send(new EditorProtocol.ReorderPacket(state.snapshot().revision(), state.activeTab(),
                            selected, target.node.id()));
                } else if (canMoveToParent(selected, target.node.parentId())) {
                    ArcMenuEditorClient.send(new EditorProtocol.ReparentPacket(state.snapshot().revision(), selected,
                            target.node.parentId(), target.node.id()));
                } else {
                    state.status(EditorI18n.text("arcmenu_editor.status.invalid_group_drop"));
                }
            } else if (target == null && event.y() >= currentLayout.manager().y() + 46) {
                if (sameParent(selected, "")) {
                    ArcMenuEditorClient.send(new EditorProtocol.ReorderPacket(state.snapshot().revision(),
                            state.activeTab(), selected, ""));
                } else if (canMoveToParent(selected, "")) {
                    ArcMenuEditorClient.send(new EditorProtocol.ReparentPacket(state.snapshot().revision(),
                            selected, "", ""));
                }
            }
        }
        if (!draggingTemplate.isBlank() && currentLayout != null && currentLayout.manager().contains(event.x(), event.y())
                && state.snapshot() != null) {
            ArcMenuEditorClient.send(new EditorProtocol.InstantiateTemplatePacket(state.snapshot().revision(), draggingTemplate));
        }
        dragCandidateNode = "";
        draggingTemplate = "";
        resizeTarget = ResizeTarget.NONE;
        return super.mouseReleased(event);
    }

    private void beginLiveGesture() {
        activeGestureId = nextGestureId++;
        if (nextGestureId <= 0) nextGestureId = 1;
        pendingDragPointer = null;
        lastSentDragPointer = null;
        lastDragSendNanos = 0;
        dragRequestInFlight = false;
        dragReleased = false;
        finalDragSent = false;
    }

    private void flushLiveDrag(boolean finalUpdate) {
        if (activeGestureId == 0 || pendingDragPointer == null || dragRequestInFlight || state.snapshot() == null) return;
        long now = System.nanoTime();
        if (!finalUpdate && now - lastDragSendNanos < LIVE_DRAG_INTERVAL_NANOS) return;
        if (resizingNode) {
            ArcMenuEditorClient.send(new EditorProtocol.ResizePacket(
                    state.snapshot().revision(), state.activeTab(), draggingNode, pendingDragPointer,
                    activeGestureId, finalUpdate));
        } else if (!draggingNode.isBlank()) {
            ArcMenuEditorClient.send(new EditorProtocol.MovePacket(
                    state.snapshot().revision(), state.activeTab(), draggingNode, pendingDragPointer,
                    grabOffsetX, grabOffsetY, activeGestureId, finalUpdate));
        } else return;
        lastSentDragPointer = pendingDragPointer;
        lastDragSendNanos = now;
        dragRequestInFlight = true;
        finalDragSent = finalUpdate;
    }

    private void finishLiveGesture() {
        activeGestureId = 0;
        draggingNode = "";
        resizingNode = false;
        pendingDragPointer = null;
        lastSentDragPointer = null;
        dragRequestInFlight = false;
        dragReleased = false;
        finalDragSent = false;
    }

    private void flushPropertyDrag(boolean finalUpdate) {
        if (propertyDrag == null || !propertyDrag.dragging || propertyRequestInFlight || state.snapshot() == null) return;
        long now = System.nanoTime();
        if (!finalUpdate && now - lastPropertySendNanos < LIVE_DRAG_INTERVAL_NANOS) return;
        if (Double.compare(pendingPropertyValue, propertyDrag.initialValue) == 0 && Double.isNaN(sentPropertyValue)) {
            if (finalUpdate) finishPropertyGesture();
            return;
        }
        ArcMenuEditorClient.send(new EditorProtocol.SetPropertyPacket(
                state.snapshot().revision(), state.activeTab(), propertyDrag.nodeId, propertyDrag.property.key(),
                formatPropertyValue(propertyDrag.property, pendingPropertyValue), propertyDrag.gestureId, finalUpdate));
        sentPropertyValue = pendingPropertyValue;
        lastPropertySendNanos = now;
        propertyRequestInFlight = true;
        propertyFinalSent = finalUpdate;
    }

    private void finishPropertyGesture() {
        propertyDrag = null;
        propertyRequestInFlight = false;
        propertyFinalSent = false;
        propertyReleased = false;
        pendingPropertyValue = 0;
        sentPropertyValue = Double.NaN;
    }

    private static double propertyStep(EditorProtocol.PropertySnapshot property, MouseButtonEvent event) {
        String key = property.key();
        double normal = key.startsWith("rotation.") ? 1.0
                : key.startsWith("scale.") ? 0.01
                : property.type() == EditorProtocol.PROPERTY_INTEGER ? 1.0 : 0.25;
        if (event.hasAltDown()) return normal / 5.0;
        if (event.hasShiftDown()) return normal * 5.0;
        if (event.hasControlDown()) return key.startsWith("rotation.") ? 15.0
                : key.startsWith("scale.") ? 0.1 : 1.0;
        return normal;
    }

    private static double normalizePropertyValue(EditorProtocol.PropertySnapshot property, double value) {
        if (!Double.isFinite(value)) return 0.0;
        if (property.type() == EditorProtocol.PROPERTY_INTEGER) value = Math.rint(value);
        if (property.key().equals("opacity")) value = Math.max(0, Math.min(255, value));
        if (property.key().equals("width") || property.key().equals("height") || property.key().equals("thickness")) {
            value = Math.max(0.01, value);
        }
        return Math.abs(value) < 0.0000005 ? 0.0 : value;
    }

    private static String formatPropertyValue(EditorProtocol.PropertySnapshot property, double value) {
        if (property.type() == EditorProtocol.PROPERTY_INTEGER) return Long.toString(Math.round(value));
        return format(value);
    }

    private static double parseNumber(String value, String fallback) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) {
            try { return Double.parseDouble(fallback); }
            catch (NumberFormatException ignoredAgain) { return 0.0; }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (currentLayout != null && currentLayout.tools().contains(mouseX, mouseY)) {
            int visible = currentLayout.tools().height() - 28;
            toolsScroll = Math.max(0, Math.min(Math.max(0, toolContentHeight - visible), toolsScroll - (int) Math.round(vertical * 25)));
            return true;
        }
        if (currentLayout != null && currentLayout.manager().contains(mouseX, mouseY)) {
            int content = nodeRows.size() * 19;
            int visible = currentLayout.manager().height() - 50;
            managerScroll = Math.max(0, Math.min(Math.max(0, content - visible), managerScroll - (int) Math.round(vertical * 19)));
            return true;
        }
        if (currentLayout != null && currentLayout.properties().contains(mouseX, mouseY)) {
            EditorProtocol.NodeSnapshot node = state.find(state.selectedId());
            if (node != null) {
                int content = node.properties().size() * 24;
                int visible = currentLayout.properties().height() - 50;
                propertyScroll = Math.max(0, Math.min(Math.max(0, content - visible), propertyScroll - (int) Math.round(vertical * 24)));
                rebuildWidgets();
            }
            return true;
        }
        if (currentLayout != null && currentLayout.templates().contains(mouseX, mouseY)) {
            if (assetLibrary.imageMode()) {
                int visible = currentLayout.templates().height() - 43;
                if (mouseX < currentLayout.templates().x() + folderPaneWidth())
                    folderScroll = Math.max(0, Math.min(Math.max(0, assetLibrary.folders().size() * 20 - visible), folderScroll - (int) Math.round(vertical * 20)));
                else imageScroll = Math.max(0, Math.min(Math.max(0, imageContentHeight - visible), imageScroll - (int) Math.round(vertical * 38)));
                return true;
            }
            int visible = currentLayout.templates().height() - 45;
            templateScroll = Math.max(0, Math.min(Math.max(0, templateContentHeight - visible), templateScroll - (int) Math.round(vertical * 43)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (calibrationSettingsOpen) return keyPressedCalibrationSettings(event);
        if (contextMenu != null && event.isEscape()) {
            contextMenu = null;
            return true;
        }
        if (!pendingTemplateNode.isBlank()) {
            if (event.isEscape()) {
                pendingTemplateNode = "";
                rebuildWidgets();
                return true;
            }
            if (event.isConfirmation() && templateNameBox != null && state.snapshot() != null) {
                String templateId = templateNameBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
                ArcMenuEditorClient.send(new EditorProtocol.SaveTemplatePacket(state.snapshot().revision(), pendingTemplateNode, templateId));
                pendingTemplateNode = "";
                rebuildWidgets();
                return true;
            }
            return super.keyPressed(event);
        }
        for (PropertyField field : propertyFields) {
            if (!field.box.isFocused()) continue;
            if (event.isEscape()) {
                field.box.setValue(displayPropertyValue(field.property));
                setFocused(null);
                return true;
            }
            if (event.isConfirmation() && state.snapshot() != null) {
                String value = submittedPropertyValue(field.property, field.box.getValue());
                setProperty(field, value);
                setFocused(null);
                return true;
            }
            return super.keyPressed(event);
        }
        if (state.snapshot() != null && event.hasControlDown()) {
            if (event.key() == GLFW.GLFW_KEY_Z) {
                ArcMenuEditorClient.send(event.hasShiftDown()
                        ? new EditorProtocol.RedoPacket(state.snapshot().revision())
                        : new EditorProtocol.UndoPacket(state.snapshot().revision()));
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_Y) {
                ArcMenuEditorClient.send(new EditorProtocol.RedoPacket(state.snapshot().revision())); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_S) {
                ArcMenuEditorClient.send(new EditorProtocol.SavePacket(state.snapshot().revision())); return true;
            }
            if (event.key() == GLFW.GLFW_KEY_A) {
                state.selectAll(state.nodes().stream().map(EditorProtocol.NodeSnapshot::id).toList());
                rebuildWidgets();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_C) { copySelection(); return true; }
            if (event.key() == GLFW.GLFW_KEY_V) {
                EditorProtocol.NodeSnapshot selected = state.find(state.selectedId());
                if (event.hasShiftDown() && selected != null && selected.kind() == EditorProtocol.KIND_GROUP) {
                    pasteSelection(false, selected.id());
                } else pasteSelection(true, "");
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_D) { duplicateSelection(); return true; }
            if (event.key() == GLFW.GLFW_KEY_G && !event.hasShiftDown()) { groupSelection(); return true; }
        }
        if (event.key() == GLFW.GLFW_KEY_F2 && state.selectedIds().size() == 1) {
            focusIdProperty();
            return true;
        }
        if (state.snapshot() != null && event.key() == GLFW.GLFW_KEY_DELETE && !state.selectedIds().isEmpty()) {
            deleteSelection();
            return true;
        }
        return super.keyPressed(event);
    }

    private void performManagerAction(ManagerAction action) {
        switch (action) {
            case CREATE -> create(state.activeTab() == EditorProtocol.TAB_FRONTEND
                    ? EditorProtocol.KIND_GROUP : EditorProtocol.KIND_REGION, "");
            case GROUP -> groupSelection();
            case DUPLICATE -> duplicateSelection();
            case PASTE -> pasteSelection(true, "");
        }
    }

    private void copySelection() {
        if (state.snapshot() == null || state.selectedIds().isEmpty()) {
            state.status(EditorI18n.text("arcmenu_editor.status.no_copy"));
            return;
        }
        clipboardIds = selectedOrder();
        clipboardTab = state.activeTab();
        clipboardMenu = state.snapshot().menuId();
        state.status(EditorI18n.text("arcmenu_editor.status.copied", clipboardIds.size()));
    }

    private boolean clipboardAvailable() {
        return state.snapshot() != null && !clipboardIds.isEmpty() && clipboardTab == state.activeTab()
                && clipboardMenu.equals(state.snapshot().menuId());
    }

    private void pasteSelection(boolean preserveParents, String targetParent) {
        if (!clipboardAvailable()) {
            state.status(EditorI18n.text("arcmenu_editor.status.clipboard_unavailable"));
            return;
        }
        ArcMenuEditorClient.send(new EditorProtocol.DuplicatePacket(state.snapshot().revision(), state.activeTab(),
                clipboardIds, targetParent, preserveParents));
    }

    private void duplicateSelection() {
        if (state.snapshot() == null || state.selectedIds().isEmpty()) return;
        ArcMenuEditorClient.send(new EditorProtocol.DuplicatePacket(state.snapshot().revision(), state.activeTab(),
                selectedOrder(), "", true));
    }

    private void groupSelection() {
        if (state.snapshot() == null || state.activeTab() != EditorProtocol.TAB_FRONTEND || state.selectedIds().isEmpty()) return;
        ArcMenuEditorClient.send(new EditorProtocol.GroupPacket(state.snapshot().revision(), selectedOrder()));
    }

    private void deleteSelection() {
        if (state.snapshot() == null || state.selectedIds().isEmpty()) return;
        List<String> ids = selectedOrder();
        if (ids.size() == 1) {
            ArcMenuEditorClient.send(new EditorProtocol.DeletePacket(state.snapshot().revision(), state.activeTab(), ids.getFirst()));
        } else {
            ArcMenuEditorClient.send(new EditorProtocol.DeleteManyPacket(state.snapshot().revision(), state.activeTab(), ids));
        }
    }

    private void focusIdProperty() {
        propertyScroll = 0;
        rebuildWidgets();
        propertyField("id").ifPresent(this::focusProperty);
    }

    private void focusProperty(PropertyField field) {
        unfocusPropertyFields();
        setFocused(field.box);
        field.box.moveCursorToEnd(false);
        field.box.setHighlightPos(0);
    }

    private void unfocusPropertyFields() {
        propertyFields.forEach(field -> field.box.setFocused(false));
        if (getFocused() instanceof EditBox && getFocused() != templateNameBox) setFocused(null);
    }

    private void setProperty(PropertyField field, String value) {
        if (state.snapshot() == null || field.tab != state.activeTab() || state.find(field.nodeId) == null) return;
        ArcMenuEditorClient.send(new EditorProtocol.SetPropertyPacket(state.snapshot().revision(), field.tab,
                field.nodeId, field.property.key(), value));
    }

    private java.util.Optional<PropertyField> propertyField(String key) {
        return propertyFields.stream().filter(field -> field.property.key().equals(key)).findFirst();
    }

    private List<String> selectedOrder() {
        return state.nodes().stream().map(EditorProtocol.NodeSnapshot::id).filter(state::selected).toList();
    }

    private boolean sameParent(List<String> ids, String parentId) {
        return !ids.isEmpty() && ids.stream().map(state::find).allMatch(node -> node != null && node.parentId().equals(parentId));
    }

    private boolean canMoveIntoGroup(List<String> ids, EditorProtocol.NodeSnapshot target) {
        return target.kind() == EditorProtocol.KIND_GROUP && canMoveToParent(ids, target.id());
    }

    private boolean canMoveToParent(List<String> ids, String targetParentId) {
        if (state.activeTab() != EditorProtocol.TAB_FRONTEND || ids.isEmpty()) return false;
        if (targetParentId.isBlank()) return true;
        EditorProtocol.NodeSnapshot target = state.find(targetParentId);
        if (target == null || target.kind() != EditorProtocol.KIND_GROUP) return false;
        String cursor = targetParentId;
        Set<String> visited = new LinkedHashSet<>();
        while (!cursor.isBlank() && visited.add(cursor)) {
            if (ids.contains(cursor)) return false;
            EditorProtocol.NodeSnapshot node = state.find(cursor);
            cursor = node == null ? "" : node.parentId();
        }
        return true;
    }

    private NodeRow nodeRowAt(double y) {
        return nodeRows.stream().filter(row -> y >= row.screenY && y < row.screenY + 19).findFirst().orElse(null);
    }

    private void openManagerContext(double mouseX, double mouseY, NodeRow row) {
        List<ContextEntry> entries = new ArrayList<>();
        boolean hasSelection = !state.selectedIds().isEmpty();
        if (row != null && row.node.kind() == EditorProtocol.KIND_GROUP && clipboardAvailable()) {
            entries.add(new ContextEntry(EditorIcons.PASTE, EditorI18n.text("arcmenu_editor.context.paste_into_group"), "Ctrl+Shift+V", true,
                    () -> pasteSelection(false, row.node.id())));
        }
        entries.add(new ContextEntry(EditorIcons.COPY, EditorI18n.text("arcmenu_editor.context.copy"), "Ctrl+C", hasSelection, this::copySelection));
        entries.add(new ContextEntry(EditorIcons.PASTE, EditorI18n.text("arcmenu_editor.context.paste_original"), "Ctrl+V", clipboardAvailable(),
                () -> pasteSelection(true, "")));
        entries.add(new ContextEntry(EditorIcons.DUPLICATE, EditorI18n.text("arcmenu_editor.context.duplicate"), "Ctrl+D", hasSelection, this::duplicateSelection));
        if (state.activeTab() == EditorProtocol.TAB_FRONTEND) {
            entries.add(new ContextEntry(EditorIcons.FOLDER, EditorI18n.text("arcmenu_editor.context.group"), "Ctrl+G", hasSelection, this::groupSelection));
        }
        if (row != null && state.hasChildren(row.node.id())) {
            boolean expanded = state.expanded().contains(row.node.id());
            entries.add(new ContextEntry(expanded ? EditorIcons.CHEVRON_LEFT : EditorIcons.CHEVRON_RIGHT,
                    EditorI18n.text(expanded ? "arcmenu_editor.context.collapse" : "arcmenu_editor.context.expand"), "", true, () -> {
                if (!state.expanded().add(row.node.id())) state.expanded().remove(row.node.id());
            }));
            entries.add(new ContextEntry(EditorIcons.LIST, EditorI18n.text("arcmenu_editor.context.select_children"), "", true, () -> selectChildren(row.node.id())));
        }
        entries.add(new ContextEntry(EditorIcons.EDIT, EditorI18n.text("arcmenu_editor.context.rename"), "F2", state.selectedIds().size() == 1, this::focusIdProperty));
        entries.add(new ContextEntry(EditorIcons.TRASH, EditorI18n.text("arcmenu_editor.context.delete"), "Delete", hasSelection, this::deleteSelection));
        int menuWidth = 174;
        int menuHeight = entries.size() * 21 + 4;
        int x = Math.max(2, Math.min(width - menuWidth - 2, (int) Math.round(mouseX)));
        int y = Math.max(2, Math.min(height - menuHeight - 2, (int) Math.round(mouseY)));
        contextMenu = new ContextMenu(x, y, menuWidth, entries);
    }

    private void openTemplateContext(double mouseX, double mouseY, TemplateCard card) {
        List<ContextEntry> entries = List.of(new ContextEntry(
                EditorIcons.TRASH, EditorI18n.text("arcmenu_editor.context.delete_template"), "", true, () -> deleteTemplate(card.template.id())));
        int menuWidth = 126;
        int menuHeight = entries.size() * 21 + 4;
        int x = Math.max(2, Math.min(width - menuWidth - 2, (int) Math.round(mouseX)));
        int y = Math.max(2, Math.min(height - menuHeight - 2, (int) Math.round(mouseY)));
        contextMenu = new ContextMenu(x, y, menuWidth, entries);
    }

    private void deleteTemplate(String templateId) {
        if (state.snapshot() == null) return;
        ArcMenuEditorClient.send(new EditorProtocol.DeleteTemplatePacket(state.snapshot().revision(), templateId));
        state.status(EditorI18n.text("arcmenu_editor.status.deleting_template", templateId));
    }

    private void selectChildren(String parentId) {
        List<String> children = state.nodes().stream().filter(node -> node.parentId().equals(parentId))
                .map(EditorProtocol.NodeSnapshot::id).toList();
        state.selectAll(children);
        state.expanded().add(parentId);
        rebuildWidgets();
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (contextMenu == null) return;
        int height = contextMenu.entries.size() * 21 + 4;
        graphics.fill(contextMenu.x + 4, contextMenu.y + 4, contextMenu.x + contextMenu.width + 5,
                contextMenu.y + height + 5, 0x77000000);
        graphics.fill(contextMenu.x, contextMenu.y, contextMenu.x + contextMenu.width, contextMenu.y + height, 0xFF161920);
        graphics.outline(contextMenu.x, contextMenu.y, contextMenu.width, height, BORDER);
        for (int index = 0; index < contextMenu.entries.size(); index++) {
            ContextEntry entry = contextMenu.entries.get(index);
            int y = contextMenu.y + 2 + index * 21;
            boolean hover = entry.enabled && mouseX >= contextMenu.x && mouseX < contextMenu.x + contextMenu.width
                    && mouseY >= y && mouseY < y + 21;
            if (hover) graphics.fill(contextMenu.x + 2, y, contextMenu.x + contextMenu.width - 2, y + 21, SELECTED);
            int color = entry.enabled ? TEXT : 0xFF626A77;
            EditorIcons.draw(graphics, entry.icon, contextMenu.x + 5, y + 2, color);
            graphics.text(font, entry.label, contextMenu.x + 26, y + 7, color, false);
            if (!entry.shortcut.isBlank()) {
                graphics.text(font, entry.shortcut, contextMenu.x + contextMenu.width - font.width(entry.shortcut) - 7,
                        y + 7, entry.enabled ? MUTED : 0xFF555C68, false);
            }
        }
    }

    private EditorProtocol.NodeSnapshot hitNode(double x, double y) {
        return state.nodes().stream().filter(node -> {
            ElementSelectionCalibration.Calibration calibration = selectionCalibration.get(node.kind());
            double width = calibration.proxyWidth(node.width());
            double height = calibration.proxyHeight(node.height());
            double left = calibration.proxyX(node.x(), node.width()) - width / 2;
            double right = calibration.proxyX(node.x(), node.width()) + width / 2;
            double bottom = calibration.proxyY(node.y(), node.height()) - height / 2;
            double top = calibration.proxyY(node.y(), node.height()) + height / 2;
            double mx = menuX(x); double my = menuY(y);
            return mx >= left && mx <= right && my >= bottom && my <= top;
        }).max(Comparator.comparingInt(node -> state.nodes().indexOf(node))).orElse(null);
    }

    private boolean resizeHandleHit(EditorProtocol.NodeSnapshot node, double screenX, double screenY) {
        ElementSelectionCalibration.Calibration calibration = selectionCalibration.get(node.kind());
        int handleX = screenX(calibration.proxyX(node.x(), node.width()) + calibration.proxyWidth(node.width()) / 2);
        int handleY = screenY(calibration.proxyY(node.y(), node.height()) - calibration.proxyHeight(node.height()) / 2);
        return Math.abs(screenX - handleX) <= 8 && Math.abs(screenY - handleY) <= 8;
    }

    private EditorProtocol.Pointer pointer(double mouseX, double mouseY) {
        var view = virtualScreen();
        return new EditorProtocol.Pointer(mouseX, mouseY, view.x(), view.y(), view.width(), view.height(), menuX(mouseX), menuY(mouseY));
    }

    private EditorProtocol.Pointer boundedPointer(double mouseX, double mouseY, boolean resize) {
        var view = virtualScreen();
        double x = Math.max(view.x(), Math.min(view.right() - 0.001, mouseX));
        double y = Math.max(view.y(), Math.min(view.bottom() - 0.001, mouseY));
        return resize ? resizePointer(x, y) : pointer(x, y);
    }

    /** Invert the type calibration before the server derives backend width/height from the pointer. */
    private EditorProtocol.Pointer resizePointer(double mouseX, double mouseY) {
        var view = virtualScreen();
        EditorProtocol.NodeSnapshot node = state.find(draggingNode);
        ElementSelectionCalibration.Calibration calibration = node == null
                ? ElementSelectionCalibration.IDENTITY : selectionCalibration.get(node.kind());
        double nodeX = node == null ? 0.0 : node.x();
        double nodeY = node == null ? 0.0 : node.y();
        double correctedMenuX = calibration.serverRightX(nodeX, menuX(mouseX));
        double correctedMenuY = calibration.serverBottomY(nodeY, menuY(mouseY));
        return new EditorProtocol.Pointer(screenXExact(correctedMenuX), screenYExact(correctedMenuY),
                view.x(), view.y(), view.width(), view.height(), correctedMenuX, correctedMenuY);
    }

    private int screenX(double menuX) {
        return (int) Math.round(screenXExact(menuX));
    }

    private int screenY(double menuY) {
        return (int) Math.round(screenYExact(menuY));
    }

    private double screenXExact(double menuX) {
        var snapshot = state.snapshot(); var view = virtualScreen();
        return view.x() + (menuX + snapshot.canvasWidth() / 2.0) / snapshot.canvasWidth() * view.width();
    }

    private double screenYExact(double menuY) {
        var snapshot = state.snapshot(); var view = virtualScreen();
        return view.y() + (snapshot.canvasHeight() / 2.0 - menuY) / snapshot.canvasHeight() * view.height();
    }

    private double menuX(double screenX) {
        var snapshot = state.snapshot(); var view = virtualScreen();
        return (screenX - view.x()) / view.width() * snapshot.canvasWidth() - snapshot.canvasWidth() / 2.0;
    }

    private double menuY(double screenY) {
        var snapshot = state.snapshot(); var view = virtualScreen();
        return snapshot.canvasHeight() / 2.0 - (screenY - view.y()) / view.height() * snapshot.canvasHeight();
    }

    private EditorLayout.ScreenRect virtualScreen() {
        return layout.virtualScreen(currentLayout);
    }

    private EditorLayout.Rect calibrationPanel() {
        int panelWidth = Math.max(320, Math.min(560, width - 40));
        int panelHeight = Math.max(300, Math.min(350, height - EditorLayout.HEADER_HEIGHT - 30));
        return new EditorLayout.Rect((width - panelWidth) / 2,
                EditorLayout.HEADER_HEIGHT + Math.max(10, (height - EditorLayout.HEADER_HEIGHT - panelHeight) / 2),
                panelWidth, panelHeight);
    }

    private EditorLayout.Rect calibrationKindRect(int index) {
        EditorLayout.Rect panel = calibrationPanel();
        int listWidth = Math.min(145, Math.max(118, panel.width() / 3));
        return new EditorLayout.Rect(panel.x() + 8, panel.y() + 39 + index * 23, listWidth, 21);
    }

    private EditorLayout.Rect calibrationResetRect() {
        EditorLayout.Rect panel = calibrationPanel();
        return new EditorLayout.Rect(panel.right() - 236, panel.bottom() - 31, 108, 22);
    }

    private EditorLayout.Rect calibrationSaveRect() {
        EditorLayout.Rect panel = calibrationPanel();
        return new EditorLayout.Rect(panel.right() - 120, panel.bottom() - 31, 108, 22);
    }

    private static void fill(GuiGraphicsExtractor graphics, EditorLayout.Rect rect, int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color);
    }

    private String ellipsize(String value, int maxCharacters) {
        if (value == null) return "";
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, Math.max(0, maxCharacters - 3)) + "...";
    }

    private static String displayPropertyValue(EditorProtocol.PropertySnapshot property) {
        return property.type() == EditorProtocol.PROPERTY_MULTILINE
                ? property.value().replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")
                : property.value();
    }

    private static String submittedPropertyValue(EditorProtocol.PropertySnapshot property, String value) {
        if (property.type() != EditorProtocol.PROPERTY_MULTILINE) return value.trim();
        StringBuilder output = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (escape) {
                output.append(character == 'n' ? '\n' : character);
                escape = false;
            } else if (character == '\\') escape = true;
            else output.append(character);
        }
        if (escape) output.append('\\');
        return output.toString();
    }

    private String suggestTemplateId(String groupId) {
        String base = groupId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (base.isBlank() || !Character.isLetterOrDigit(base.charAt(0))) base = "template-" + base;
        if (state.snapshot() == null) return base;
        java.util.Set<String> ids = new java.util.HashSet<>();
        state.snapshot().templates().forEach(template -> ids.add(template.id()));
        if (!ids.contains(base)) return base;
        int suffix = 2;
        while (ids.contains(base + suffix)) suffix++;
        return base + suffix;
    }

    private static String propertyLabel(String key) {
        return switch (key) {
            case "id" -> EditorI18n.text("arcmenu_editor.property.id");
            case "offset.x", "x" -> EditorI18n.text("arcmenu_editor.property.position_x");
            case "offset.y", "y" -> EditorI18n.text("arcmenu_editor.property.position_y");
            case "offset.z" -> EditorI18n.text("arcmenu_editor.property.position_z");
            case "rotation.x" -> EditorI18n.text("arcmenu_editor.property.rotation_x");
            case "rotation.y" -> EditorI18n.text("arcmenu_editor.property.rotation_y");
            case "rotation.z" -> EditorI18n.text("arcmenu_editor.property.rotation_z");
            case "scale.x" -> EditorI18n.text("arcmenu_editor.property.scale_x");
            case "scale.y" -> EditorI18n.text("arcmenu_editor.property.scale_y");
            case "scale.z" -> EditorI18n.text("arcmenu_editor.property.scale_z");
            case "visible" -> EditorI18n.text("arcmenu_editor.property.visible");
            case "width" -> EditorI18n.text("arcmenu_editor.property.width");
            case "height" -> EditorI18n.text("arcmenu_editor.property.height");
            case "thickness" -> EditorI18n.text("arcmenu_editor.property.thickness");
            case "color" -> EditorI18n.text("arcmenu_editor.property.color");
            case "opacity" -> EditorI18n.text("arcmenu_editor.property.opacity");
            case "content" -> EditorI18n.text("arcmenu_editor.property.content");
            case "size" -> EditorI18n.text("arcmenu_editor.property.size");
            case "font" -> EditorI18n.text("arcmenu_editor.property.font");
            case "line-width" -> EditorI18n.text("arcmenu_editor.property.line_width");
            case "alignment" -> EditorI18n.text("arcmenu_editor.property.alignment");
            case "update" -> EditorI18n.text("arcmenu_editor.property.update");
            case "source" -> EditorI18n.text("arcmenu_editor.property.source");
            case "material" -> EditorI18n.text("arcmenu_editor.property.material");
            case "context" -> EditorI18n.text("arcmenu_editor.property.context");
            case "block-data" -> EditorI18n.text("arcmenu_editor.property.block_data");
            case "priority" -> EditorI18n.text("arcmenu_editor.property.priority");
            case "tooltip" -> EditorI18n.text("arcmenu_editor.property.tooltip");
            case "condition" -> EditorI18n.text("arcmenu_editor.property.condition");
            case "actions" -> EditorI18n.text("arcmenu_editor.property.actions");
            case "deny" -> EditorI18n.text("arcmenu_editor.property.deny");
            default -> key;
        };
    }

    private static String calibrationFieldLabel(int index) {
        return switch (index) {
            case 0 -> EditorI18n.text("arcmenu_editor.calibration.field.offset_x");
            case 1 -> EditorI18n.text("arcmenu_editor.calibration.field.offset_y");
            case 2 -> EditorI18n.text("arcmenu_editor.calibration.field.scale_x");
            case 3 -> EditorI18n.text("arcmenu_editor.calibration.field.scale_y");
            default -> EditorI18n.text("arcmenu_editor.common.unknown_parameter");
        };
    }

    private static String calibrationKindLabel(byte kind) {
        return switch (kind) {
            case EditorProtocol.KIND_GROUP -> EditorI18n.text("arcmenu_editor.tool.group");
            case EditorProtocol.KIND_RECTANGLE -> EditorI18n.text("arcmenu_editor.tool.rectangle");
            case EditorProtocol.KIND_FRAME -> EditorI18n.text("arcmenu_editor.tool.frame");
            case EditorProtocol.KIND_LINE -> EditorI18n.text("arcmenu_editor.tool.line");
            case EditorProtocol.KIND_TEXT -> EditorI18n.text("arcmenu_editor.tool.text");
            case EditorProtocol.KIND_IMAGE -> EditorI18n.text("arcmenu_editor.tool.image");
            case EditorProtocol.KIND_ITEM -> EditorI18n.text("arcmenu_editor.tool.item");
            case EditorProtocol.KIND_BLOCK -> EditorI18n.text("arcmenu_editor.tool.block");
            case EditorProtocol.KIND_REGION -> EditorI18n.text("arcmenu_editor.tool.region");
            default -> EditorI18n.text("arcmenu_editor.common.unknown_type");
        };
    }

    private static int propertyStripe(String key) {
        if (key.endsWith(".x") || key.equals("x") || key.equals("width")) return 0xFFE95C66;
        if (key.endsWith(".y") || key.equals("y") || key.equals("height")) return 0xFF56D364;
        if (key.endsWith(".z")) return 0xFF55A6F1;
        return 0xFF6B7482;
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001) return Long.toString(Math.round(value));
        return String.format(java.util.Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static boolean isNumeric(EditorProtocol.PropertySnapshot property) {
        return property.type() == EditorProtocol.PROPERTY_NUMBER || property.type() == EditorProtocol.PROPERTY_INTEGER;
    }

    private static EditorIcons.Icon kindIcon(byte kind) {
        return switch (kind) {
            case EditorProtocol.KIND_GROUP -> EditorIcons.FOLDER;
            case EditorProtocol.KIND_RECTANGLE -> EditorIcons.SQUARE;
            case EditorProtocol.KIND_FRAME, EditorProtocol.KIND_REGION -> EditorIcons.OUTLINE;
            case EditorProtocol.KIND_LINE -> EditorIcons.LINE;
            case EditorProtocol.KIND_TEXT -> EditorIcons.FONT;
            case EditorProtocol.KIND_IMAGE -> EditorIcons.IMAGE;
            case EditorProtocol.KIND_ITEM -> EditorIcons.MATERIAL;
            case EditorProtocol.KIND_BLOCK -> EditorIcons.BLOCK;
            default -> EditorIcons.PROPERTIES;
        };
    }

    private static int kindColor(byte kind) {
        return switch (kind) {
            case EditorProtocol.KIND_GROUP -> 0xFF6CC7EA;
            case EditorProtocol.KIND_REGION -> 0xFF5DA8FF;
            case EditorProtocol.KIND_TEXT -> 0xFFF0CA65;
            case EditorProtocol.KIND_IMAGE -> 0xFF9B8AFB;
            case EditorProtocol.KIND_ITEM, EditorProtocol.KIND_BLOCK -> 0xFF65D68A;
            default -> 0xFFAFB8C6;
        };
    }

    private static String kindName(byte kind) {
        return switch (kind) {
            case EditorProtocol.KIND_GROUP -> "group";
            case EditorProtocol.KIND_RECTANGLE -> "rectangle / line";
            case EditorProtocol.KIND_FRAME -> "frame";
            case EditorProtocol.KIND_LINE -> "line";
            case EditorProtocol.KIND_TEXT -> "text";
            case EditorProtocol.KIND_IMAGE -> "image";
            case EditorProtocol.KIND_ITEM -> "item";
            case EditorProtocol.KIND_BLOCK -> "block";
            case EditorProtocol.KIND_REGION -> "interaction region";
            default -> "unknown";
        };
    }

    private static final class NodeRow {
        private final EditorProtocol.NodeSnapshot node;
        private final int depth;
        private final int index;
        private int screenY = Integer.MIN_VALUE;

        private NodeRow(EditorProtocol.NodeSnapshot node, int depth, int index) {
            this.node = node; this.depth = depth; this.index = index;
        }
    }

    private record PropertyField(String nodeId, byte tab, EditorProtocol.PropertySnapshot property, EditBox box, int y, EditorLayout.Rect rect) {}
    private record ToolDefinition(EditorIcons.Icon icon, String label, byte kind) {}
    private record ToolButton(ToolDefinition tool, EditorLayout.Rect rect) {}
    private record AssetCard(EditorAssetLibrary.Entry entry, EditorLayout.Rect rect) {}
    private record FolderRow(String path, EditorLayout.Rect rect) {}
    private record TemplateCard(EditorProtocol.TemplateSnapshot template, EditorLayout.Rect rect) {}
    private record ManagerButton(ManagerAction action, EditorLayout.Rect rect) {}
    private record HoverTooltip(String text, int x, int y) {}
    private record CalibrationField(int index, EditBox box) {}

    private static final class PropertyDrag {
        private final String nodeId;
        private final EditorProtocol.PropertySnapshot property;
        private final double initialValue;
        private final double startX;
        @SuppressWarnings("unused") private final double startY;
        private final long gestureId;
        private boolean dragging;

        private PropertyDrag(String nodeId, EditorProtocol.PropertySnapshot property, double initialValue,
                             double startX, double startY, long gestureId) {
            this.nodeId = nodeId;
            this.property = property;
            this.initialValue = initialValue;
            this.startX = startX;
            this.startY = startY;
            this.gestureId = gestureId;
        }
    }

    private static final class ContextEntry {
        private final EditorIcons.Icon icon;
        private final String label;
        private final String shortcut;
        private final boolean enabled;
        private final Runnable action;

        private ContextEntry(EditorIcons.Icon icon, String label, String shortcut, boolean enabled, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.shortcut = shortcut;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private static final class ContextMenu {
        private final int x;
        private final int y;
        private final int width;
        private final List<ContextEntry> entries;

        private ContextMenu(int x, int y, int width, List<ContextEntry> entries) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.entries = List.copyOf(entries);
        }

        private ContextEntry entryAt(double mouseX, double mouseY) {
            if (mouseX < x || mouseX >= x + width || mouseY < y + 2) return null;
            int index = (int) ((mouseY - y - 2) / 21);
            return index >= 0 && index < entries.size() ? entries.get(index) : null;
        }
    }

    private enum ManagerAction { CREATE, GROUP, DUPLICATE, PASTE }

    private enum HeaderAction {
        UNDO("arcmenu_editor.header.undo"), REDO("arcmenu_editor.header.redo"),
        SAVE("arcmenu_editor.header.save"), APPLY("arcmenu_editor.header.apply"),
        SETTINGS("arcmenu_editor.header.settings");

        private final String translationKey;
        HeaderAction(String translationKey) { this.translationKey = translationKey; }
    }

    private enum ResizeTarget { NONE, TOOLS, INSPECTOR, TEMPLATES }
}

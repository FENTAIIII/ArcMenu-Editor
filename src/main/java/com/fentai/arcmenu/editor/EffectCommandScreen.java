package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable list of executable workspace commands and their keyboard bindings. */
public final class EffectCommandScreen extends Screen implements EffectWorkspaceChild
{
    private static final int MAX_PANEL_WIDTH = 520;
    private static final int SCREEN_MARGIN = 24;
    private static final int PANEL_TOP = 24;
    private static final int PADDING = 10;
    private static final int SEARCH_TOP = 26;
    private static final int SEARCH_HEIGHT = 20;
    private static final int SEARCH_MAX_LENGTH = 100;
    private static final int LIST_TOP = 52;
    private static final int ROW_HEIGHT = 22;
    /** Vertical space the panel needs besides its rows: header, search box and hint. */
    private static final int RESERVED_HEIGHT = 96;
    /** Panel height besides its rows: header and search box above, a little padding below. */
    private static final int PANEL_CHROME_HEIGHT = 65;
    private static final int HINT_OFFSET = 74;
    private static final int HINT_BOTTOM_MARGIN = 14;
    /** Horizontal room kept free for the shortcut column when a label is truncated. */
    private static final int SHORTCUT_COLUMN_WIDTH = 160;
    private static final int ROW_TEXT_INSET = 12;
    private static final int ROW_TEXT_TOP = 6;
    private static final int HIGHLIGHT_INSET = 3;
    private static final int BACKDROP_COLOR = 0xE5101010;
    private static final int SELECTED_ROW_COLOR = 0xFF384A60;

    private final EffectEditorScreen parent;
    private EditBox search;
    private List<EffectCommands.Command> shown = List.of();
    private int left;
    private int top;
    private int panelWidth;
    private int rows;
    private int scroll;
    private int selected;

    EffectCommandScreen(EffectEditorScreen parent)
    {
        super(EditorI18n.component("arcmenu_editor.workspace.command_search"));
        this.parent = parent;
    }

    @Override
    public EffectEditorScreen workspace()
    {
        return parent;
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    protected void init()
    {
        panelWidth = Math.min(MAX_PANEL_WIDTH, width - SCREEN_MARGIN);
        left = (width - panelWidth) / 2;
        top = PANEL_TOP;
        rows = Math.max(1, (height - RESERVED_HEIGHT) / ROW_HEIGHT);
        search = new EditBox(font, left + PADDING, top + SEARCH_TOP, panelWidth - 2 * PADDING, SEARCH_HEIGHT, title);
        search.setMaxLength(SEARCH_MAX_LENGTH);
        search.setResponder(value ->
        {
            scroll = 0;
            selected = 0;
            filter(value);
        });
        addRenderableWidget(search);
        filter("");
        setFocused(search);
    }

    private void filter(String value)
    {
        String query = value.toLowerCase(Locale.ROOT);
        var commands = new ArrayList<>(EffectCommands.list());
        EffectTimeline.shortcuts().forEach((id, binding) ->
        {
            if (commands.stream().noneMatch(command -> command.id().equals(id)))
            {
                commands.add(new EffectCommands.Command(id, id.replace('-', '_'), binding));
            }
        });
        shown = commands.stream().filter(command -> searchText(command).contains(query)).toList();
    }

    private static String searchText(EffectCommands.Command command)
    {
        String text = EffectUi.label(command.label()) + " " + command.id() + " " + command.shortcut();
        return text.toLowerCase(Locale.ROOT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {
        int panelHeight = rows * ROW_HEIGHT + PANEL_CHROME_HEIGHT;
        graphics.fill(0, 0, width, height, BACKDROP_COLOR);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, EffectUi.PANEL);
        graphics.outline(left, top, panelWidth, panelHeight, EffectUi.BORDER);
        EffectUi.text(graphics, font, title.getString(), left + PADDING, top + 8, EffectUi.TEXT);
        for (int row = 0; row < rows && row + scroll < shown.size(); row++)
        {
            int index = row + scroll;
            int y = top + LIST_TOP + row * ROW_HEIGHT;
            var command = shown.get(index);
            boolean enabled = parent.commandEnabled(command.id());
            if (index == selected)
            {
                graphics.fill(left + HIGHLIGHT_INSET, y, left + panelWidth - HIGHLIGHT_INSET, y + ROW_HEIGHT - 1,
                        SELECTED_ROW_COLOR);
            }
            String label = EffectUi.label(command.label());
            if (label.startsWith("arcmenu_editor."))
            {
                label = command.id();
            }
            String fittedLabel = EffectUi.fit(font, label, panelWidth - SHORTCUT_COLUMN_WIDTH);
            int labelColor = enabled ? EffectUi.TEXT : EffectUi.MUTED;
            EffectUi.text(graphics, font, fittedLabel, left + ROW_TEXT_INSET, y + ROW_TEXT_TOP, labelColor);
            String shortcut = command.shortcut();
            int shortcutX = left + panelWidth - EffectUi.width(font, shortcut) - ROW_TEXT_INSET;
            EffectUi.mono(graphics, font, shortcut, shortcutX, y + ROW_TEXT_TOP, EffectUi.MUTED);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int hintY = Math.min(height - HINT_BOTTOM_MARGIN, top + rows * ROW_HEIGHT + HINT_OFFSET);
        EffectUi.text(graphics, font, EffectUi.label("command_search_hint"), left + 8, hintY, EffectUi.MUTED);
    }

    private void run()
    {
        if (selected >= 0 && selected < shown.size() && parent.commandEnabled(shown.get(selected).id()))
        {
            String id = shown.get(selected).id();
            minecraft.setScreen(parent);
            parent.workspaceCommand(id);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event)
    {
        if (event.isEscape())
        {
            onClose();
            return true;
        }
        if (event.isConfirmation())
        {
            run();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN)
        {
            int step = event.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1;
            selected = Math.max(0, Math.min(shown.size() - 1, selected + step));
            scroll = Math.max(0, Math.min(selected, Math.max(scroll, selected - rows + 1)));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
    {
        int listTop = top + LIST_TOP;
        boolean insideColumns = event.x() >= left && event.x() < left + panelWidth;
        boolean insideRows = event.y() >= listTop && event.y() < listTop + rows * ROW_HEIGHT;
        if (insideColumns && insideRows)
        {
            selected = scroll + (int) ((event.y() - listTop) / ROW_HEIGHT);
            run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        int maxScroll = Math.max(0, shown.size() - rows);
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) verticalAmount));
        return true;
    }

    @Override
    public void onClose()
    {
        minecraft.setScreen(parent);
    }
}

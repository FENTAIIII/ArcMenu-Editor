package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Searchable list of executable workspace commands and their keyboard bindings. */
public final class EffectCommandScreen extends Screen implements EffectWorkspaceChild
{
    private final EffectEditorScreen parent;
    private EditBox search;
    private List<EffectCommands.Command> shown = List.of();
    private int left;
    private int top;
    private int w;
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
        w = Math.min(520, width - 24);
        left = (width - w) / 2;
        top = 24;
        rows = Math.max(1, (height - 96) / 22);
        search = new EditBox(font, left + 10, top + 26, w - 20, 20, title);
        search.setMaxLength(100);
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
        String q = value.toLowerCase(Locale.ROOT);
        var all = new ArrayList<>(EffectCommands.list());
        EffectTimeline.shortcuts().forEach((id, binding) ->
        {
            if (all.stream().noneMatch(c -> c.id().equals(id)))
            {
                all.add(new EffectCommands.Command(id, id.replace('-', '_'), binding));
            }
        });
        shown = all.stream().filter(c -> (EffectUi.label(c.label()) + " " + c.id() + " " + c.shortcut())
                .toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {
        graphics.fill(0, 0, width, height, 0xE5101010);
        graphics.fill(left, top, left + w, top + rows * 22 + 65, EffectUi.PANEL);
        graphics.outline(left, top, w, rows * 22 + 65, EffectUi.BORDER);
        EffectUi.text(graphics, font, title.getString(), left + 10, top + 8, EffectUi.TEXT);
        for (int j = 0; j < rows && j + scroll < shown.size(); j++)
        {
            int i = j + scroll;
            int y = top + 52 + j * 22;
            var c = shown.get(i);
            boolean enabled = parent.commandEnabled(c.id());
            if (i == selected)
            {
                graphics.fill(left + 3, y, left + w - 3, y + 21, 0xFF384A60);
            }
            String label = EffectUi.label(c.label());
            if (label.startsWith("arcmenu_editor."))
            {
                label = c.id();
            }
            EffectUi.text(graphics, font, EffectUi.fit(font, label, w - 160), left + 12, y + 6,
                    enabled ? EffectUi.TEXT : EffectUi.MUTED);
            EffectUi.mono(graphics, font, c.shortcut(), left + w - EffectUi.width(font, c.shortcut()) - 12, y + 6,
                    EffectUi.MUTED);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        EffectUi.text(graphics, font, EffectUi.label("command_search_hint"), left + 8,
                Math.min(height - 14, top + rows * 22 + 74), EffectUi.MUTED);
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
            selected = Math.max(0, Math.min(shown.size() - 1, selected + (event.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            scroll = Math.max(0, Math.min(selected, Math.max(scroll, selected - rows + 1)));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
    {
        if (event.x() >= left && event.x() < left + w && event.y() >= top + 52 && event.y() < top + 52 + rows * 22)
        {
            selected = scroll + (int) ((event.y() - top - 52) / 22);
            run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v)
    {
        scroll = Math.max(0, Math.min(Math.max(0, shown.size() - rows), scroll - (int) v));
        return true;
    }

    @Override
    public void onClose()
    {
        minecraft.setScreen(parent);
    }
}

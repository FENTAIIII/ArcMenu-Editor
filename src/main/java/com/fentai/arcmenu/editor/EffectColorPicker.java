package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Consumer;

/** RGBA and hexadecimal color editing; values are committed once on confirmation. */
final class EffectColorPicker extends Screen implements EffectWorkspaceChild
{
    private final EffectEditorScreen parent;
    private final Consumer<String> commit;
    private final int original;
    private EditBox hex;
    private int color;
    private int left;
    private int top;
    private int panelWidth;
    private int slider;

    EffectColorPicker(EffectEditorScreen parent, String value, Consumer<String> commit)
    {
        super(EditorI18n.component("arcmenu_editor.workspace.color_picker"));
        this.parent = parent;
        this.commit = commit;
        color = parse(value);
        original = color;
    }

    static int parse(String value)
    {
        String hex = value.trim().replace("#", "");
        if (hex.startsWith("0x"))
        {
            hex = hex.substring(2);
        }
        if (hex.length() == 6)
        {
            hex = "FF" + hex;
        }
        if (hex.length() != 8)
        {
            throw new IllegalArgumentException("Color must use RRGGBB or AARRGGBB hexadecimal notation");
        }
        return (int) Long.parseLong(hex, 16);
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
        panelWidth = Math.min(340, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(16, (height - 210) / 2);
        hex = new EditBox(font, left + 12, top + 32, panelWidth - 24, 20, title);
        hex.setMaxLength(9);
        updateHex();
        addRenderableWidget(hex);
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.confirm"), button ->
        {
            try
            {
                color = parse(hex.getValue());
                commit.accept(String.format(Locale.ROOT, "#%08X", color));
                onClose();
            }
            catch (IllegalArgumentException error)
            {
                hex.setTextColor(0xFFFF8888);
            }
        }).bounds(left + panelWidth - 170, top + 176, 76, 20).build());
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.cancel"), button -> onClose())
                .bounds(left + panelWidth - 88, top + 176, 76, 20).build());
    }

    private void updateHex()
    {
        hex.setValue(String.format(Locale.ROOT, "#%08X", color));
        hex.setTextColor(EffectUi.TEXT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta)
    {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta)
    {
        graphics.fill(0, 0, width, height, 0xE0101010);
        graphics.fill(left, top, left + panelWidth, top + 204, EffectUi.PANEL);
        graphics.outline(left, top, panelWidth, 204, EffectUi.BORDER);
        EffectUi.text(graphics, font, title.getString(), left + 12, top + 10, EffectUi.TEXT);
        for (int channel = 0; channel < 4; channel++)
        {
            int y = top + 66 + channel * 23;
            int bit = channel == 3 ? 24 : 16 - channel * 8;
            EffectUi.mono(graphics, font, new String[] { "R", "G", "B", "A" }[channel], left + 12, y + 3,
                    EffectUi.TEXT);
            for (int i = 0; i < panelWidth - 74; i++)
            {
                int channelValue = i * 255 / Math.max(1, panelWidth - 75);
                int sampleColor = (color & ~(255 << bit)) | (channelValue << bit);
                graphics.fill(left + 36 + i, y, left + 37 + i, y + 12, 0xFF000000 | sampleColor);
            }
            int currentValue = (color >>> bit) & 255;
            int x = left + 36 + currentValue * (panelWidth - 75) / 255;
            graphics.outline(x - 1, y - 2, 3, 16, 0xFFFFFFFF);
            EffectUi.mono(graphics, font, Integer.toString(currentValue), left + panelWidth - 31, y + 3,
                    EffectUi.MUTED);
        }
        graphics.fill(left + 12, top + 165, left + 47, top + 175, original);
        graphics.fill(left + 47, top + 165, left + 82, top + 175, color);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void slide(double x)
    {
        int bit = slider == 4 ? 24 : 16 - (slider - 1) * 8;
        int channelValue = (int) Math.round(Math.max(0, Math.min(1, (x - left - 36) / (panelWidth - 75))) * 255);
        color = (color & ~(255 << bit)) | (channelValue << bit);
        updateHex();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice)
    {
        int row = (int) ((event.y() - top - 66) / 23);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && row >= 0 && row < 4 && event.y() >= top + 66
                && event.y() < top + 66 + row * 23 + 13 && event.x() >= left + 36 && event.x() < left + panelWidth - 38)
        {
            slider = row + 1;
            slide(event.x());
            return true;
        }
        return super.mouseClicked(event, twice);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy)
    {
        if (slider != 0)
        {
            slide(event.x());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event)
    {
        slider = 0;
        return super.mouseReleased(event);
    }

    @Override
    public void onClose()
    {
        minecraft.setScreen(parent);
    }
}

package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Effect workspace typography and dark-panel design tokens. */
final class EffectUi
{
    static final int BACKGROUND = 0xFF121212;
    static final int HEADER = 0xFF1F1F1F;
    static final int PANEL = 0xFF232323;
    static final int BORDER = 0xFF343434;
    static final int TEXT = 0xFFC8C8C8;
    static final int MUTED = 0xFF9A9A9A;
    static final int ACCENT = 0xFF2D8CEB;
    private static final FontDescription.Resource UI = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("arcmenu_editor", "effect_ui"));
    private static final FontDescription.Resource MONO = new FontDescription.Resource(
            Identifier.fromNamespaceAndPath("arcmenu_editor", "effect_mono"));

    static Component component(String value)
    {
        return Component.literal(value).withStyle(style -> style.withFont(UI));
    }

    static Component mono(String value)
    {
        return Component.literal(value).withStyle(style -> style.withFont(MONO));
    }

    static String label(String key)
    {
        return EditorI18n.text("arcmenu_editor.workspace." + key);
    }

    static void text(GuiGraphicsExtractor graphics, Font font, String value, int x, int y, int color)
    {
        graphics.text(font, component(value), x, y, color, false);
    }

    static void mono(GuiGraphicsExtractor graphics, Font font, String value, int x, int y, int color)
    {
        graphics.text(font, mono(value), x, y, color, false);
    }

    static void line(GuiGraphicsExtractor graphics, int x, int y, int toX, int toY, int color)
    {
        int steps = Math.min(8192, Math.max(Math.abs(toX - x), Math.abs(toY - y)));
        for (int i = 0; i <= steps; i++)
        {
            double t = steps == 0 ? 0 : i / (double) steps;
            int pointX = (int) Math.round(x + (toX - x) * t);
            int pointY = (int) Math.round(y + (toY - y) * t);
            graphics.fill(pointX, pointY, pointX + 1, pointY + 1, color);
        }
    }

    static String fit(Font font, String value, int max)
    {
        String text = value;
        while (!text.isEmpty() && font.width(component(text)) > max)
        {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    static int width(Font font, String value)
    {
        return font.width(component(value));
    }

    static void panel(GuiGraphicsExtractor graphics, EditorLayout.Rect rect)
    {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), PANEL);
        graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(), BORDER);
    }

    static void tab(GuiGraphicsExtractor graphics, Font font, String label, int x, int y, boolean active)
    {
        text(graphics, font, label, x, y, active ? TEXT : MUTED);
        if (active)
        {
            graphics.fill(x, y + 13, x + width(font, label), y + 14, ACCENT);
        }
    }

    private EffectUi()
    {}
}

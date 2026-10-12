package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/** Effect workspace typography and dark-panel design tokens. */
final class EffectUi {
    static final int BACKGROUND = 0xFF121212, HEADER = 0xFF1F1F1F, PANEL = 0xFF232323;
    static final int BORDER = 0xFF343434, TEXT = 0xFFC8C8C8, MUTED = 0xFF9A9A9A, ACCENT = 0xFF2D8CEB;
    private static final FontDescription.Resource UI = new FontDescription.Resource(Identifier.fromNamespaceAndPath("arcmenu_editor", "effect_ui"));
    private static final FontDescription.Resource MONO = new FontDescription.Resource(Identifier.fromNamespaceAndPath("arcmenu_editor", "effect_mono"));
    static Component component(String value) { return Component.literal(value).withStyle(style -> style.withFont(UI)); }
    static Component mono(String value) { return Component.literal(value).withStyle(style -> style.withFont(MONO)); }
    static String label(String key) { return EditorI18n.text("arcmenu_editor.workspace." + key); }
    static void text(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color) { g.text(font, component(value), x, y, color, false); }
    static void mono(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color) { g.text(font, mono(value), x, y, color, false); }
    static void line(GuiGraphicsExtractor g, int x, int y, int tx, int ty, int color) {
        int steps = Math.min(8192, Math.max(Math.abs(tx - x), Math.abs(ty - y)));
        for (int i = 0; i <= steps; i++) { double t = steps == 0 ? 0 : i / (double) steps; int px = (int) Math.round(x + (tx - x) * t), py = (int) Math.round(y + (ty - y) * t); g.fill(px, py, px + 1, py + 1, color); }
    }
    static String fit(Font font, String value, int max) { String s = value; while (!s.isEmpty() && font.width(component(s)) > max) s = s.substring(0, s.length() - 1); return s; }
    static int width(Font font, String value) { return font.width(component(value)); }
    static void panel(GuiGraphicsExtractor g, EditorLayout.Rect rect) { g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), PANEL); g.outline(rect.x(), rect.y(), rect.width(), rect.height(), BORDER); }
    static void tab(GuiGraphicsExtractor g, Font font, String label, int x, int y, boolean active) { text(g,font,label,x,y,active?TEXT:MUTED); if (active) g.fill(x,y+13,x+width(font,label),y+14,ACCENT); }
    private EffectUi() {}
}

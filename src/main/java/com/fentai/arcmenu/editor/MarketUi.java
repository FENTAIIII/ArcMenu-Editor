package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.Locale;

/** A restrained storefront palette using the editor's bundled Inter font. */
final class MarketUi {
    static final int BACKGROUND = 0xFFF3F5F9, SURFACE = 0xFFFFFFFF, INK = 0xFF18243B;
    static final int MUTED = 0xFF65738B, LINE = 0xFFE1E7EF, ACCENT = 0xFF5265DD;
    static final int ACCENT_SOFT = 0xFFE9EDFF, NAVY = 0xFF152039, GREEN = 0xFF168263, GREEN_SOFT = 0xFFE1F4EC;
    static String text(String key, Object... arguments) { return EditorI18n.text("arcmenu_editor.market." + key, arguments); }
    static void rounded(GuiGraphicsExtractor g, EditorLayout.Rect rect, int color) {
        if (rect.width() < 6 || rect.height() < 6) { g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color); return; }
        g.fill(rect.x() + 3, rect.y(), rect.right() - 3, rect.bottom(), color);
        g.fill(rect.x() + 1, rect.y() + 1, rect.right() - 1, rect.bottom() - 1, color);
        g.fill(rect.x(), rect.y() + 3, rect.right(), rect.bottom() - 3, color);
    }
    static void card(GuiGraphicsExtractor g, EditorLayout.Rect rect, boolean hovered) {
        rounded(g, new EditorLayout.Rect(rect.x(), rect.y() + 2, rect.width(), rect.height()), 0x100E2445);
        rounded(g, rect, hovered ? 0xFFD9E1FF : LINE);
        rounded(g, new EditorLayout.Rect(rect.x() + 1, rect.y() + 1, rect.width() - 2, rect.height() - 2), SURFACE);
    }
    static void label(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color) { EffectUi.text(g, font, value, x, y, color); }
    static void title(GuiGraphicsExtractor g, Font font, String value, int x, int y, int color) { g.text(font, EffectUi.component(value).copy().withStyle(style -> style.withBold(true)), x, y, color, false); }
    static String fit(Font font, String value, int width) {
        if (EffectUi.width(font, value) <= width) return value;
        String trimmed = EffectUi.fit(font, value, Math.max(0, width - EffectUi.width(font, "…")));
        return trimmed.isEmpty() ? "" : trimmed + "…";
    }
    static void button(GuiGraphicsExtractor g, Font font, EditorLayout.Rect rect, String label, String style, boolean enabled, boolean hovered, boolean focused) {
        int fill = style.equals("primary") ? (hovered ? 0xFF4355C8 : ACCENT) : style.equals("nav") ? (hovered ? ACCENT_SOFT : BACKGROUND) : hovered ? 0xFFF0F3FB : SURFACE;
        if (!enabled) fill = 0xFFE9EDF3;
        rounded(g, rect, style.equals("ghost") || style.equals("nav") ? fill : LINE);
        rounded(g, new EditorLayout.Rect(rect.x() + 1, rect.y() + 1, rect.width() - 2, rect.height() - 2), fill);
        if (focused) g.outline(rect.x(), rect.y(), rect.width(), rect.height(), ACCENT);
        int color = !enabled ? 0xFF9CA7B8 : style.equals("primary") ? SURFACE : INK;
        label(g, font, fit(font, label, rect.width() - 14), rect.x() + 7, rect.y() + (rect.height() - 9) / 2, color);
    }
    static void pill(GuiGraphicsExtractor g, Font font, String label, int x, int y, int color, int background) {
        int width = EffectUi.width(font, label) + 12; rounded(g, new EditorLayout.Rect(x, y, width, 16), background); label(g, font, label, x + 6, y + 4, color);
    }
    static void icon(GuiGraphicsExtractor g, String name, int x, int y, int color) {
        String[] shape = switch (name) {
            case "heart" -> new String[]{"0110110", "1111111", "1111111", "0111110", "0011100", "0001000"};
            case "download" -> new String[]{"0001000", "0001000", "0101010", "0011100", "0001000", "1000001", "1111111"};
            case "star" -> new String[]{"0001000", "0011100", "1111111", "0111110", "0011100", "0100010"};
            case "grid" -> new String[]{"1110111", "1110111", "1110111", "0000000", "1110111", "1110111", "1110111"};
            default -> new String[]{"1111110", "1000011", "1000001", "1000001", "1000001", "1111111"};
        };
        for (int row = 0; row < shape.length; row++) for (int column = 0; column < shape[row].length(); column++) if (shape[row].charAt(column) == '1') g.fill(x + column, y + row, x + column + 1, y + row + 1, color);
    }
    static String compact(double value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000).replace(".0M", "M");
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000).replace(".0k", "k");
        return Long.toString((long) value);
    }
    private MarketUi() {}
}

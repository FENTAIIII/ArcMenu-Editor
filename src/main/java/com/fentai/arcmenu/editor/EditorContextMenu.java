package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.EditorIcons.Icon;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

import static com.fentai.arcmenu.editor.EditorColors.BORDER;
import static com.fentai.arcmenu.editor.EditorColors.DISABLED_SHORTCUT;
import static com.fentai.arcmenu.editor.EditorColors.DISABLED_TEXT;
import static com.fentai.arcmenu.editor.EditorColors.DROP_SHADOW;
import static com.fentai.arcmenu.editor.EditorColors.POPUP_BACKGROUND;
import static com.fentai.arcmenu.editor.EditorColors.MUTED;
import static com.fentai.arcmenu.editor.EditorColors.SELECTED;
import static com.fentai.arcmenu.editor.EditorColors.TEXT;

/** Right-click menu of the menu editor: a column of icon entries with optional shortcut hints. */
final class EditorContextMenu
{
    private static final int ROW_HEIGHT = 21;
    private static final int PADDING = 2;
    private static final int SHADOW_OFFSET = 4;
    private static final int ICON_X = 5;
    private static final int LABEL_X = 26;
    private static final int TEXT_Y = 7;
    private static final int SHORTCUT_RIGHT_MARGIN = 7;
    /** Distance kept between the menu and the screen edges. */
    private static final int SCREEN_MARGIN = 2;

    record Entry(Icon icon, String label, String shortcut, boolean enabled, Runnable action)
    {}

    private final int x;
    private final int y;
    private final int width;
    private final List<Entry> entries;

    private EditorContextMenu(int x, int y, int width, List<Entry> entries)
    {
        this.x = x;
        this.y = y;
        this.width = width;
        this.entries = List.copyOf(entries);
    }

    /** Opens a menu at the pointer, moved inwards where it would cross the screen edge. */
    static EditorContextMenu at(double mouseX, double mouseY, int width, List<Entry> entries, int screenWidth,
            int screenHeight)
    {
        int height = height(entries.size());
        int x = Math.max(SCREEN_MARGIN, Math.min(screenWidth - width - SCREEN_MARGIN, (int) Math.round(mouseX)));
        int y = Math.max(SCREEN_MARGIN, Math.min(screenHeight - height - SCREEN_MARGIN, (int) Math.round(mouseY)));
        return new EditorContextMenu(x, y, width, entries);
    }

    private static int height(int entryCount)
    {
        return entryCount * ROW_HEIGHT + PADDING * 2;
    }

    /** The entry under the pointer, or {@code null} outside the entries. */
    Entry entryAt(double mouseX, double mouseY)
    {
        if (mouseX < x || mouseX >= x + width || mouseY < y + PADDING)
        {
            return null;
        }
        int index = (int) ((mouseY - y - PADDING) / ROW_HEIGHT);
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY)
    {
        int height = height(entries.size());
        graphics.fill(x + SHADOW_OFFSET, y + SHADOW_OFFSET, x + width + SHADOW_OFFSET + 1,
                y + height + SHADOW_OFFSET + 1, DROP_SHADOW);
        graphics.fill(x, y, x + width, y + height, POPUP_BACKGROUND);
        graphics.outline(x, y, width, height, BORDER);
        for (int index = 0; index < entries.size(); index++)
        {
            Entry entry = entries.get(index);
            int rowY = y + PADDING + index * ROW_HEIGHT;
            boolean hover = entry.enabled() && mouseX >= x && mouseX < x + width && mouseY >= rowY
                    && mouseY < rowY + ROW_HEIGHT;
            if (hover)
            {
                graphics.fill(x + PADDING, rowY, x + width - PADDING, rowY + ROW_HEIGHT, SELECTED);
            }
            int color = entry.enabled() ? TEXT : DISABLED_TEXT;
            EditorIcons.draw(graphics, entry.icon(), x + ICON_X, rowY + PADDING, color);
            graphics.text(font, entry.label(), x + LABEL_X, rowY + TEXT_Y, color, false);
            if (!entry.shortcut().isBlank())
            {
                int shortcutX = x + width - font.width(entry.shortcut()) - SHORTCUT_RIGHT_MARGIN;
                graphics.text(font, entry.shortcut(), shortcutX, rowY + TEXT_Y,
                        entry.enabled() ? MUTED : DISABLED_SHORTCUT, false);
            }
        }
    }
}

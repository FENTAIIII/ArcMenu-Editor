package com.fentai.arcmenu.editor;

/** Responsive marketplace geometry shared by rendering and pointer hit testing. */
final class MarketLayout {
    static final int HEADER = 42, FOOTER = 22, GAP = 12;
    record Grid(int left, int top, int width, int height, int columns, int cardWidth, int cardHeight) {
        EditorLayout.Rect card(int index, int scroll) {
            return new EditorLayout.Rect(left + index % columns * (cardWidth + GAP),
                    top + index / columns * (cardHeight + GAP) - scroll, cardWidth, cardHeight);
        }
        int contentHeight(int count) { return count == 0 ? 0 : ((count + columns - 1) / columns) * (cardHeight + GAP) - GAP; }
        int maximumScroll(int count) { return Math.max(0, contentHeight(count) - height); }
        int hit(double x, double y, int count, int scroll) {
            if (x < left || x >= left + width || y < top || y >= top + height) return -1;
            int column = (int) (x - left) / (cardWidth + GAP), row = (int) (y - top + scroll) / (cardHeight + GAP);
            int index = row * columns + column;
            if (column >= columns || index < 0 || index >= count) return -1;
            return contains(card(index, scroll), x, y) ? index : -1;
        }
    }
    static int header(int height) { return height < 320 ? 32 : HEADER; }
    static int searchTop(int height) { return height < 320 ? 50 : height < 360 ? 77 : 89; }
    static int gridTop(int height) { return height < 320 ? 82 : height < 360 ? 108 : 123; }
    static int sidebar(int width) { return sidebar(width, 400); }
    static int sidebar(int width, int height) { return height < 340 ? 0 : width >= 780 ? 154 : width >= 450 ? 112 : 0; }
    static int left(int width) { return left(width, 400); }
    static int left(int width, int height) { return sidebar(width, height) + (sidebar(width, height) == 0 ? 12 : 16); }
    static int contentWidth(int width) { return contentWidth(width, 400); }
    static int contentWidth(int width, int height) { return Math.max(120, width - left(width, height) - 16); }
    static Grid grid(int width, int height) {
        int available = contentWidth(width, height), columns = Math.max(1, Math.min(4, (available + GAP) / (height < 340 ? 148 : 205)));
        int cardWidth = (available - (columns - 1) * GAP) / columns;
        int viewport = Math.max(1, height - gridTop(height) - FOOTER - 10);
        int cardHeight = Math.min(Math.max(100, viewport), cardWidth * 9 / 16 + (height < 340 ? 51 : 68));
        return new Grid(left(width, height), gridTop(height), available, viewport, columns, cardWidth, cardHeight);
    }
    static boolean contains(EditorLayout.Rect rect, double x, double y) {
        return x >= rect.x() && x < rect.right() && y >= rect.y() && y < rect.bottom();
    }
    private MarketLayout() {}
}

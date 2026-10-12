package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Twelve-pixel vector tools remain legible independently of the active font. */
final class EffectToolIcons {
    static void draw(GuiGraphicsExtractor g, String tool, int x, int y, int color) {
        switch (tool) {
            case "select", "pointer" -> polygon(g, x, y, color, new int[][]{{1,0},{1,10},{4,7},{6,11},{8,10},{6,6},{10,6}});
            case "hand" -> outline(g, x, y, color, new int[][]{{2,6},{2,4},{4,4},{4,1},{5,1},{5,5},{6,5},{6,0},{7,0},{7,5},{8,5},{8,2},{9,2},{9,6},{10,6},{10,4},{11,4},{11,8},{8,11},{5,11},{1,7},{1,6},{2,6}});
            case "rectangle" -> rect(g, x + 1, y + 2, 10, 8, color);
            case "frame" -> { rect(g, x + 1, y + 1, 10, 10, color); rect(g, x + 3, y + 3, 6, 6, color); }
            case "text" -> { g.fill(x + 1, y + 1, x + 11, y + 3, color); g.fill(x + 5, y + 3, x + 7, y + 11, color); }
            case "image" -> {
                rect(g, x + 1, y + 1, 10, 10, color); g.fill(x + 7, y + 3, x + 9, y + 5, color);
                path(g, x, y, color, new int[][]{{2,9},{5,6},{7,8},{9,6},{10,7}});
            }
            case "gif" -> {
                rect(g,x,y,10,10,color); rect(g,x+2,y+2,10,10,color);
                polygon(g,x+3,y+2,color,new int[][]{{2,2},{2,7},{6,4}});
            }
            case "item" -> {
                outline(g, x, y, color, new int[][]{{6,0},{11,5},{6,11},{0,5}});
                path(g, x, y, color, new int[][]{{0,5},{11,5}});
                path(g, x, y, color, new int[][]{{6,0},{3,5},{6,11},{8,5},{6,0}});
            }
            case "block" -> {
                outline(g, x, y, color, new int[][]{{6,0},{11,3},{11,8},{6,11},{1,8},{1,3}});
                path(g, x, y, color, new int[][]{{1,3},{6,5},{11,3}}); EffectUi.line(g, x + 6, y + 5, x + 6, y + 11, color);
            }
            case "line" -> {
                EffectUi.line(g, x + 2, y + 9, x + 9, y + 2, color);
                g.fill(x, y + 8, x + 3, y + 11, color); g.fill(x + 8, y, x + 11, y + 3, color);
            }
            case "group" -> { rect(g, x, y, 8, 8, color); rect(g, x + 4, y + 4, 8, 8, color); }
            case "region" -> {
                for (int i = 0; i < 4; i++) {
                    int pos = i * 3; g.fill(x + pos, y, x + pos + 2, y + 1, color); g.fill(x + pos, y + 11, x + pos + 2, y + 12, color);
                    g.fill(x, y + pos, x + 1, y + pos + 2, color); g.fill(x + 11, y + pos, x + 12, y + pos + 2, color);
                }
            }
            case "undo" -> {
                path(g, x, y, color, new int[][]{{11,10},{11,6},{9,3},{6,2},{3,3}});
                polygon(g, x, y, color, new int[][]{{0,3},{4,0},{4,6}});
            }
            case "redo" -> {
                path(g, x, y, color, new int[][]{{0,10},{0,6},{2,3},{5,2},{8,3}});
                polygon(g, x, y, color, new int[][]{{11,3},{7,0},{7,6}});
            }
            default -> rect(g, x + 2, y + 2, 8, 8, color);
        }
    }
    private static void rect(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        g.fill(x, y, x + width, y + 1, color); g.fill(x, y + height - 1, x + width, y + height, color);
        g.fill(x, y, x + 1, y + height, color); g.fill(x + width - 1, y, x + width, y + height, color);
    }
    private static void path(GuiGraphicsExtractor g, int x, int y, int color, int[][] points) {
        for (int i = 1; i < points.length; i++) EffectUi.line(g, x + points[i - 1][0], y + points[i - 1][1], x + points[i][0], y + points[i][1], color);
    }
    private static void outline(GuiGraphicsExtractor g, int x, int y, int color, int[][] points) {
        path(g, x, y, color, points);
        EffectUi.line(g, x + points[points.length - 1][0], y + points[points.length - 1][1], x + points[0][0], y + points[0][1], color);
    }
    private static void polygon(GuiGraphicsExtractor g, int x, int y, int color, int[][] points) {
        for (int row = 0; row < 12; row++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, scan = row + .5;
            for (int i = 0; i < points.length; i++) {
                int[] a = points[i], b = points[(i + 1) % points.length];
                if (scan >= Math.min(a[1], b[1]) && scan < Math.max(a[1], b[1])) {
                    double hit = a[0] + (scan - a[1]) * (b[0] - a[0]) / (b[1] - a[1]); min = Math.min(min, hit); max = Math.max(max, hit);
                }
            }
            if (max >= min) g.fill(x + (int) Math.ceil(min), y + row, x + (int) Math.floor(max) + 1, y + row + 1, color);
        }
        outline(g, x, y, color, points);
    }
    private EffectToolIcons() {}
}

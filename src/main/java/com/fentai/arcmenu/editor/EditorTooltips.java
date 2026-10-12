package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Wrapped hints keep image playback examples visible at small GUI resolutions. */
final class EditorTooltips {
    static void draw(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int height, int background, int border, int color) {
        int maxWidth = Math.max(20, Math.min(280, width - 14));
        var lines = font.split(text,maxWidth);
        int boxWidth = Math.min(maxWidth,font.width(text)) + 10, boxHeight = lines.size() * 11 + 10;
        x = Math.max(2,Math.min(width-boxWidth-2,x)); y = Math.max(2,Math.min(height-boxHeight-2,y));
        g.fill(x,y,x+boxWidth,y+boxHeight,background); g.outline(x,y,boxWidth,boxHeight,border);
        for (int i=0;i<lines.size();i++) g.text(font,lines.get(i),x+5,y+5+i*11,color,false);
    }
    private EditorTooltips() {}
}

package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.*;

/** Shared keyboard-accessible command menus for every Effect panel. */
final class EffectPopup {
    record Item(String label, String shortcut, Runnable action, boolean enabled) {
        Item(String label, String shortcut, Runnable action) { this(label, shortcut, action, true); }
    }
    private List<Item> items = List.of();
    private int x, y, width, visible, selected, scroll;
    void open(int px, int py, List<Item> value) { items = List.copyOf(value); x = px; y = py; selected = -1; scroll = 0; }
    void close() { items = List.of(); }
    boolean active() { return !items.isEmpty(); }
    void draw(GuiGraphicsExtractor g, Font font, int screenWidth, int screenHeight, int mx, int my) {
        if (!active()) return;
        width = Math.min(Math.max(190, items.stream().mapToInt(i -> EffectUi.width(font,i.label)+EffectUi.width(font,i.shortcut)+32).max().orElse(190)), screenWidth-8);
        visible = Math.max(1, Math.min(items.size(), (screenHeight-12)/21));
        scroll = Math.max(0, Math.min(items.size()-visible, scroll));
        x = Math.max(4, Math.min(x, screenWidth-width-4)); y = Math.max(4,Math.min(y,screenHeight-visible*21-8));
        g.fill(x+3,y+3,x+width+3,y+visible*21+7,0x80000000);
        g.fill(x,y,x+width,y+visible*21+4,EffectUi.PANEL); g.outline(x,y,width,visible*21+4,EffectUi.BORDER);
        for (int j=0;j<visible;j++) {
            int i=j+scroll, ry=y+2+j*21; var item=items.get(i);
            boolean hovered=mx>=x&&mx<x+width&&my>=ry&&my<ry+20;
            if (hovered || selected==i) g.fill(x+2,ry,x+width-2,ry+20,item.enabled?0xFF384A60:0xFF292929);
            EffectUi.text(g,font,EffectUi.fit(font,item.label,width-EffectUi.width(font,item.shortcut)-24),x+8,ry+6,item.enabled?EffectUi.TEXT:0xFF666666);
            EffectUi.text(g,font,item.shortcut,x+width-EffectUi.width(font,item.shortcut)-8,ry+6,item.enabled?EffectUi.MUTED:0xFF555555);
        }
        if(items.size()>visible) { int h=Math.max(6,visible*21*visible/items.size()), top=y+2+scroll*(visible*21-h)/Math.max(1,items.size()-visible); g.fill(x+width-3,top,x+width-1,top+h,EffectUi.ACCENT); }
    }
    boolean click(double px, double py, boolean primary) {
        if (!active()) return false;
        int row=(int)((py-y-2)/21)+scroll;
        if(primary&&px>=x&&px<x+width&&py>=y+2&&py<y+2+visible*21&&row>=0&&row<items.size()) {
            var item=items.get(row); if(item.enabled) {close(); item.action.run();}
        } else close();
        return true;
    }
    boolean wheel(double amount) { if(!active())return false; scroll=Math.max(0,Math.min(items.size()-visible,scroll-(int)amount));return true; }
    boolean key(int direction, boolean accept, boolean escape) {
        if(!active())return false;
        if(escape)close();
        else if(accept) { if(selected>=0&&items.get(selected).enabled) { var action=items.get(selected).action;close();action.run(); } }
        else if(direction!=0) {
            if(selected<0)selected=direction>0?-1:0;
            for(int i=0;i<items.size();i++) {selected=Math.floorMod(selected+direction,items.size());if(items.get(selected).enabled)break;}
            scroll=Math.max(0,Math.min(selected,Math.max(scroll,selected-visible+1)));
        }
        return true;
    }
}

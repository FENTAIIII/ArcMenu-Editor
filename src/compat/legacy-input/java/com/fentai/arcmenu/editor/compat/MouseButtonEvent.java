package com.fentai.arcmenu.editor.compat;

import net.minecraft.client.gui.screens.Screen;

public record MouseButtonEvent(double x, double y, int button, int modifiers) {
    public MouseButtonEvent(double x,double y,int button) { this(x,y,button,-1); }
    public boolean hasControlDown() { return modifiers < 0 ? Screen.hasControlDown() : (modifiers&2)!=0; }
    public boolean hasShiftDown() { return modifiers < 0 ? Screen.hasShiftDown() : (modifiers&1)!=0; }
    public boolean hasAltDown() { return modifiers < 0 ? Screen.hasAltDown() : (modifiers&4)!=0; }
}

package com.fentai.arcmenu.editor.compat;

import net.minecraft.client.gui.screens.Screen;

public record MouseButtonEvent(double x, double y, int button) {
    public boolean hasControlDown() { return Screen.hasControlDown(); }
    public boolean hasShiftDown() { return Screen.hasShiftDown(); }
    public boolean hasAltDown() { return Screen.hasAltDown(); }
}

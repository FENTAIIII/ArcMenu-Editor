package com.fentai.arcmenu.editor.compat;

import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public record KeyEvent(int key, int scancode, int modifiers) {
    public boolean hasControlDown() { return (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0; }
    public boolean hasShiftDown() { return (modifiers & GLFW.GLFW_MOD_SHIFT) != 0; }
    public boolean hasAltDown() { return (modifiers & GLFW.GLFW_MOD_ALT) != 0; }
    public boolean isEscape() { return key == GLFW.GLFW_KEY_ESCAPE; }
    public boolean isConfirmation() { return key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER; }
}

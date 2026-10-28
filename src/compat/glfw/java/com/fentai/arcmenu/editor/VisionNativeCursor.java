package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/** Mouse focus also changes when the pointer leaves a focused game window. */
final class VisionNativeCursor {
    private VisionNativeCursor() {}
    static boolean insideWindow(Window window) {
        return GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;
    }
}

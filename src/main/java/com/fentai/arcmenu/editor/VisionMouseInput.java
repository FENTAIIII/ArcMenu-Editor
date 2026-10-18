package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/** Native button numbers and modifier masks differ between GLFW and SDL game versions. */
public final class VisionMouseInput {
    public static final byte NO_ACTION = -1;

    private VisionMouseInput() {}

    public static byte action(int nativeButton) {
        return switch (nativeButton) {
            case InputConstants.MOUSE_BUTTON_LEFT -> VisionProtocol.LEFT;
            case InputConstants.MOUSE_BUTTON_RIGHT -> VisionProtocol.RIGHT;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> VisionProtocol.MIDDLE;
            default -> NO_ACTION;
        };
    }

    public static boolean shift(int nativeModifiers) {
        return (nativeModifiers & GLFW.GLFW_MOD_SHIFT) != 0;
    }
}

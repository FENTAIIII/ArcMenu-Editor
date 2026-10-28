package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;

/** Mouse focus also changes when the pointer leaves a focused game window. */
final class VisionNativeCursor {
    private VisionNativeCursor() {}
    static boolean insideWindow(Window window) {
        return (SDLVideo.SDL_GetWindowFlags(window.handle()) & SDLVideo.SDL_WINDOW_MOUSE_FOCUS) != 0;
    }
}

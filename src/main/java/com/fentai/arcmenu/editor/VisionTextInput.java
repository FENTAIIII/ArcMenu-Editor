package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;

/** GLFW delivers committed Unicode text through KeyboardHandler's character callback. */
final class VisionTextInput {
    private VisionTextInput() {}
    static void start(EditBox input) {}
    static boolean composing() { return false; }
    static void render(GuiGraphicsExtractor graphics, int x, int y) {}
    static void clear() {}
}

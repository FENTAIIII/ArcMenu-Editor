package com.fentai.arcmenu.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.PreeditEvent;

/** SDL additionally exposes composition events and the native candidate-window position. */
public final class VisionTextInput {
    private static IMEPreeditOverlay preedit;
    private VisionTextInput() {}

    static void start(EditBox input) { Minecraft.getInstance().textInputManager().startTextInput(input); }
    static boolean composing() { return preedit != null; }
    public static void preedit(PreeditEvent event) {
        if (VisionTextClient.editing() && VisionCursorClient.controlling())
            preedit = event == null ? null : new IMEPreeditOverlay(event, Minecraft.getInstance().font, 10);
    }
    static void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (preedit != null) {
            preedit.updateInputPosition(x, y);
            graphics.setPreeditOverlay(preedit);
        } else Minecraft.getInstance().textInputManager().setTextInputArea(x, y, x + 1, y + 10);
    }
    static void clear() { preedit = null; }
}

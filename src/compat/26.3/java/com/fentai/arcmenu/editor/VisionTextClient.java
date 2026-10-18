package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;

/** A native text/IME host without an inventory, Screen, or visible widget. */
public final class VisionTextClient {
    private static VisionProtocol.TextState state;
    private static EditBox input;
    private static IMEPreeditOverlay preedit;
    private static VisionCursorMath.Point anchor;

    private VisionTextClient() {}

    public static boolean editing() { return input != null && state != null; }

    public static void receive(VisionProtocol.TextState next) {
        if (!next.active()) {
            if (state != null && state.editId() == next.editId()) clear();
            return;
        }
        if (state != null && state.editId() == next.editId()) return;
        clear();
        var client = Minecraft.getInstance();
        VisionCursorClient.prepareTextInput();
        state = next;
        anchor = VisionCursorClient.normalizedCursor();
        input = new EditBox(client.font, Math.max(1, client.getWindow().getGuiScaledWidth()), 12,
                Component.literal(next.title()));
        input.setMaxLength(next.maxLength());
        input.setValue(next.value());
        input.moveCursorToEnd(false);
        input.setResponder(value -> VisionCursorClient.sendText(state.editId(), VisionProtocol.TEXT_UPDATE, value));
        KeyMapping.releaseAll();
        input.setFocused(VisionCursorClient.controlling());
    }

    public static void tick() {
        if (!editing()) return;
        var client = Minecraft.getInstance();
        if (!VisionCursorClient.active() || client.level == null || client.gui.screen() != null) { finish(); return; }
        boolean focused = client.isWindowActive();
        if (input.isFocused() != focused) {
            input.setFocused(focused);
            preedit = null;
        }
        if (focused) client.textInputManager().startTextInput(input);
    }

    public static void key(KeyEvent event, int action) {
        if (!editing() || action == 0) return;
        if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER) {
            if (preedit == null) finish();
            return;
        }
        input.keyPressed(event);
    }

    public static boolean character(CharacterEvent event) {
        if (!editing() || !VisionCursorClient.controlling()) return false;
        input.charTyped(event);
        return true;
    }

    public static void preedit(PreeditEvent event) {
        if (!editing() || !VisionCursorClient.controlling()) return;
        preedit = event == null ? null : new IMEPreeditOverlay(event, Minecraft.getInstance().font, 10);
    }

    public static void render(GuiGraphicsExtractor graphics) {
        if (!editing() || !VisionCursorClient.controlling()) return;
        Minecraft.getInstance().textInputManager().startTextInput(input);
        int x = (int) Math.clamp(anchor.x() * graphics.guiWidth(), 0, graphics.guiWidth() - 1);
        int y = (int) Math.clamp(anchor.y() * graphics.guiHeight(), 0, graphics.guiHeight() - 1);
        if (preedit != null) {
            preedit.updateInputPosition(x, y);
            graphics.setPreeditOverlay(preedit);
        } else Minecraft.getInstance().textInputManager().setTextInputArea(x, y, x + 1, y + 10);
    }

    public static void finish() {
        if (!editing()) return;
        VisionCursorClient.sendText(state.editId(), VisionProtocol.TEXT_FINISH, input.getValue());
        clear();
    }

    public static void clear() {
        if (input != null) input.setFocused(false);
        input = null;
        state = null;
        preedit = null;
        anchor = null;
    }
}

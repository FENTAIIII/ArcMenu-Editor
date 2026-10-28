package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks native button and modifier semantics on every maintained game version. */
final class VisionMouseInputTest {
    @Test void nativeButtonsKeepTheirMeaningOnTheSelectedMinecraftVersion() {
        assertEquals(VisionProtocol.LEFT, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(VisionProtocol.RIGHT, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(VisionProtocol.MIDDLE, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(VisionMouseInput.NO_ACTION, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_4));
    }

    @Test void everyNativeShiftBitCountsAndUnrelatedModifiersDoNot() throws Exception {
        int bits = nativeModifier("MOD_SHIFT", 1);
        while (bits != 0) {
            int shiftBit = Integer.lowestOneBit(bits);
            assertTrue(VisionMouseInput.shift(shiftBit));
            bits &= ~shiftBit;
        }
        assertFalse(VisionMouseInput.shift(0));
        assertFalse(VisionMouseInput.shift(nativeModifier("MOD_CONTROL", 2) | nativeModifier("MOD_ALT", 4)));
    }
    private static int nativeModifier(String name, int glfwValue) throws Exception {
        try { return InputConstants.class.getField(name).getInt(null); }
        catch (NoSuchFieldException ignored) { return glfwValue; }
    }
}

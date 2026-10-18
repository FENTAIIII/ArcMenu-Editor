package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the SDL constants of the adapter that provides Vision input. */
final class VisionMouseInputTest {
    @Test void nativeButtonsKeepTheirMeaningOnTheSelectedMinecraftVersion() {
        assertEquals(VisionProtocol.LEFT, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(VisionProtocol.RIGHT, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(VisionProtocol.MIDDLE, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(VisionMouseInput.NO_ACTION, VisionMouseInput.action(InputConstants.MOUSE_BUTTON_4));
    }

    @Test void everyNativeShiftBitCountsAndUnrelatedModifiersDoNot() {
        int bits = InputConstants.MOD_SHIFT;
        while (bits != 0) {
            int shiftBit = Integer.lowestOneBit(bits);
            assertTrue(VisionMouseInput.shift(shiftBit));
            bits &= ~shiftBit;
        }
        assertFalse(VisionMouseInput.shift(0));
        assertFalse(VisionMouseInput.shift(InputConstants.MOD_CONTROL | InputConstants.MOD_ALT));
    }
}

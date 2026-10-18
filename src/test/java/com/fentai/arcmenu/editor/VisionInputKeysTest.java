package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;

import static com.fentai.arcmenu.editor.VisionInputKeys.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class VisionInputKeysTest {
    @Test void jumpPressIsRequestedOnceAndCannotLeakAfterTheMenuCloses() {
        var keys = new VisionInputKeys();
        assertEquals(JUMP, keys.accept(32, 1, true, false, false, true));
        assertEquals(CONSUME, keys.accept(32, -1, false, false, false, true));
        assertEquals(CONSUME, keys.accept(32, 0, false, false, false, true));
        assertEquals(PASS, keys.accept(32, 1, false, false, false, true));
    }

    @Test void disabledServerCloseDoesNotTurnHeldJumpIntoRepeatedRequests() {
        var keys = new VisionInputKeys();
        assertEquals(JUMP, keys.accept(32, 1, true, false, false, true));
        assertEquals(CONSUME, keys.accept(32, -1, true, false, false, true));
        assertEquals(CONSUME, keys.accept(32, 0, true, false, false, true));
        assertEquals(JUMP, keys.accept(32, 1, true, false, false, true));
    }

    @Test void textSpaceIsForwardedToTheEditorAndItsReleaseIsConsumed() {
        var keys = new VisionInputKeys();
        assertEquals(TEXT, keys.accept(32, 1, true, true, false, true));
        assertEquals(TEXT, keys.accept(32, -1, true, true, false, true));
        assertEquals(CONSUME, keys.accept(32, 0, true, true, false, true));
    }

    @Test void finishingTextWithEscapeDoesNotAlsoCloseTheMenuOnRepeat() {
        var keys = new VisionInputKeys();
        assertEquals(TEXT, keys.accept(27, 1, true, true, true, false));
        assertEquals(CONSUME, keys.accept(27, -1, true, false, true, false));
        assertEquals(CONSUME, keys.accept(27, 0, true, false, true, false));
        assertEquals(CLOSE, keys.accept(27, 1, true, false, true, false));
    }

    @Test void reboundJumpBindingControlsTheShortcutInsteadOfThePhysicalSpaceKey() {
        var keys = new VisionInputKeys();
        assertEquals(PASS, keys.accept(32, 1, true, false, false, false));
        assertEquals(JUMP, keys.accept(74, 1, true, false, false, true));
        assertEquals(CONSUME, keys.accept(74, 0, false, false, false, true));
    }

    @Test void textTypingCannotLeaveMovementKeysDownWhenEditingEndsByMouse() {
        var keys = new VisionInputKeys();
        assertEquals(TEXT, keys.accept(87, 1, true, true, false, false));
        assertEquals(CONSUME, keys.accept(87, -1, true, false, false, false));
        assertEquals(CONSUME, keys.accept(87, 0, true, false, false, false));
    }
}

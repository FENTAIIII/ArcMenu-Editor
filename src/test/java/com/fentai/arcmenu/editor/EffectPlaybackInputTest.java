package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectPlaybackInputTest {
    @Test void onlyAnActivePlaybackCapturesShiftSwap() {
        var input = new EffectPlaybackInput();
        assertNull(input.poll(true, true, true));
        input.update(new EffectProtocol.Playback(17));
        assertNull(input.poll(true, true, true));
        assertNull(input.poll(true, true, false));
        assertEquals(new EffectProtocol.Exit(17), input.poll(true, true, true));
        assertNull(input.poll(true, true, true));
        input.update(new EffectProtocol.Playback(0));
        input.poll(true, true, false);
        assertNull(input.poll(true, true, true));
    }

    @Test void typingAndUnfocusedKeysCannotExitPlayback() {
        var input = new EffectPlaybackInput();
        input.update(new EffectProtocol.Playback(9));
        input.poll(true, false, false);
        assertNull(input.poll(true, false, true));
        assertNull(input.poll(true, true, true));
        input.poll(false, true, false);
        assertNull(input.poll(false, true, true));
        assertNull(input.poll(true, true, true));
        input.poll(true, true, false);
        assertEquals(new EffectProtocol.Exit(9), input.poll(true, true, true));
    }

    @Test void replacementAndDisconnectDiscardOldPlaybackCapture() {
        var input = new EffectPlaybackInput();
        input.update(new EffectProtocol.Playback(2));
        input.poll(true, true, false);
        assertEquals(new EffectProtocol.Exit(2), input.poll(true, true, true));
        input.update(new EffectProtocol.Playback(3));
        assertNull(input.poll(true, true, true));
        input.poll(true, true, false);
        assertEquals(new EffectProtocol.Exit(3), input.poll(true, true, true));
        input.clear();
        assertNull(input.poll(true, true, true));
    }
}

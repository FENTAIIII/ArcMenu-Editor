package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;

/** Runtime shortcuts are separate from the editor timeline and bound to one playback. */
final class EffectPlaybackInput {
    private long sessionId;
    private boolean swapHeld;

    void update(EffectProtocol.Playback playback) {
        sessionId = playback.sessionId();
        swapHeld = true;
    }

    void clear() {
        sessionId = 0;
        swapHeld = false;
    }

    EffectProtocol.Exit poll(boolean available, boolean shift, boolean swap) {
        boolean pressed = swap && !swapHeld;
        swapHeld = swap;
        return available && sessionId != 0 && shift && pressed ? new EffectProtocol.Exit(sessionId) : null;
    }
}

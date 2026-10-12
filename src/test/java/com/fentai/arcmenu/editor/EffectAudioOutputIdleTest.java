package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EffectAudioOutputIdleTest {
    @Test void packReloadAndDisconnectDoNotStartAnAudioWorkerBeforeFirstPlayback() throws Exception {
        var constructor = EffectAudioOutput.class.getDeclaredConstructor(); constructor.setAccessible(true);
        long workersBefore = workers();
        var output = constructor.newInstance();
        try {
            output.configure("unused-preview-device", .25);
            output.stop("unused"); output.stopAll(); output.invalidate();
            assertEquals(0, output.activeVoices()); assertFalse(output.isPlaying("unused"));
            assertEquals(workersBefore, workers());
        } finally { output.close(); }
        assertEquals(workersBefore, workers());
    }
    private static long workers() {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals("ArcMenu Effect audio output") && thread.isAlive()).count();
    }
}

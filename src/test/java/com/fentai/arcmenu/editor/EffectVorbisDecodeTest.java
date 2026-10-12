package com.fentai.arcmenu.editor;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EffectVorbisDecodeTest {
    @Test void syntheticVorbisTrackDecodesToSeekablePcmWithAnEnvelope() throws Exception {
        // Locally generated 660 Hz tone with two beats per second; no external audio assets.
        try (var stream = getClass().getResourceAsStream("/audio/effect-synthetic-beat.ogg")) {
            assertNotNull(stream);
            var clip = EffectAudioAssets.decode("arcmenu:sounds/effect/test.ogg", stream.readAllBytes(), .75, 1.5, 8);
            assertEquals(1, clip.channels()); assertEquals(48_000, clip.sampleRate());
            assertEquals(480_000, clip.frameCount()); assertEquals(10, clip.durationSeconds(), .000001);
            assertEquals(240_000, clip.frameAt(5)); assertEquals(.75, clip.baseVolume()); assertEquals(1.5, clip.basePitch());
            assertTrue(clip.peak(2, 3) > .01); assertEquals(0, clip.peak(10, 12));
        }
    }
    @Test void invalidOggReportsADecodeErrorWithoutAnOutputDevice() {
        assertThrows(IOException.class, () -> EffectAudioAssets.decode("bad.ogg", new byte[] { 'O', 'g', 'g', 'S', 0, 0 }, 1, 1, 1));
        assertThrows(IOException.class, () -> EffectAudioAssets.decode("bad.ogg", new byte[] { 1, 2, 3 }, 1, 1, 1));
    }
}

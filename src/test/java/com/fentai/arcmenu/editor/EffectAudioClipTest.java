package com.fentai.arcmenu.editor;

import java.nio.ShortBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EffectAudioClipTest {
    @Test void stereoOffsetsCountFramesPerChannelAndPitchDoesNotChangeSourceTime() {
        var clip = new EffectAudioAssets.Clip("ce:sounds/music.ogg", 2, 4, new short[16], .5, 2, 7);
        assertEquals(8, clip.frameCount()); assertEquals(2, clip.durationSeconds());
        assertEquals(3, clip.frameAt(.99)); assertEquals(4, clip.frameAt(1));
        assertEquals(0, clip.frameAt(-1)); assertEquals(8, clip.frameAt(20));
        assertEquals(7, clip.generation()); assertEquals(.5, clip.baseVolume()); assertEquals(2, clip.basePitch());
        assertThrows(IllegalArgumentException.class, () -> clip.frameAt(Double.NaN));
    }
    @Test void waveformEnvelopePreservesNegativeFullScaleAndBothStereoChannels() {
        short[] samples = new short[1200]; samples[511] = Short.MIN_VALUE; samples[1198] = Short.MAX_VALUE;
        var clip = new EffectAudioAssets.Clip("ce:sounds/music.ogg", 2, 100, samples, 1, 1, 1);
        assertEquals(1, clip.peak(2.55, 2.56));
        assertEquals(0, clip.peak(2.56, 5.99));
        assertEquals(32767 / 32768f, clip.peak(5.99, 6), .000001);
        assertEquals(1, clip.peak(0, 6));
        assertEquals(0, clip.peak(6, 8)); assertEquals(0, clip.peak(2, 1));
    }
    @Test void partialPeakBucketsDoNotLeakSamplesOutsideTheRequestedWindow() {
        short[] samples = new short[1024]; samples[254] = 12000; samples[255] = 20000; samples[512] = 30000;
        var clip = new EffectAudioAssets.Clip("ce:sounds/music.ogg", 1, 1024, samples, 1, 1, 1);
        assertEquals(12000 / 32768f, clip.peak(254 / 1024.0, 255 / 1024.0), .000001);
        assertEquals(20000 / 32768f, clip.peak(254 / 1024.0, 512 / 1024.0), .000001);
        assertEquals(30000 / 32768f, clip.peak(512 / 1024.0, 513 / 1024.0), .000001);
        ShortBuffer copy = ShortBuffer.allocate(samples.length); clip.copyTo(copy);
        assertEquals(samples.length, copy.remaining()); assertEquals(30000, copy.get(512));
    }
    @Test void malformedDecodedMetadataIsRejectedBeforeAllocatingAnEnvelope() {
        assertThrows(IllegalArgumentException.class, () -> new EffectAudioAssets.Clip("x", 3, 44100, new short[3], 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EffectAudioAssets.Clip("x", 2, 44100, new short[3], 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EffectAudioAssets.Clip("x", 1, 0, new short[3], 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EffectAudioAssets.Clip("x", 1, 44100, new short[3], 1, 0, 1));
    }
}

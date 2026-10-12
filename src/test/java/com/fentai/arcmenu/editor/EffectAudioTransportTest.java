package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class EffectAudioTransportTest {
    private static final class Output implements EffectAudioTransport.Output {
        int starts, stops;
        final Map<String, EffectAudioTimeline.Voice> voices = new LinkedHashMap<>();
        double reportedOffset = Double.NaN;
        @Override public void start(String id, EffectAudioTimeline.Voice voice) { starts++; voices.put(id, voice); }
        @Override public void stop(String id) { stops++; voices.remove(id); }
        @Override public double offsetSeconds(String id) { return reportedOffset; }
    }
    private static EffectAudioTimeline.Voice voice(String source, double offset, double volume, double pitch) {
        return new EffectAudioTimeline.Voice("cue", source, offset, volume, pitch, 600);
    }
    @Test void periodicSnapshotsKeepTheExistingVoiceAndItsSamplePosition() {
        var output = new Output(); var transport = new EffectAudioTransport(output);
        for (int i = 0; i < 20; i++) transport.synchronize(Map.of("music", voice("music.ogg", 15 + i * .2, 1, 1)), i * 200_000_000L, false);
        assertEquals(1, output.starts); assertEquals(0, output.stops);
        assertEquals(15, output.voices.get("music").offsetSeconds());
    }
    @Test void pauseAndResumeContinueAtTheRequestedOffsetAndStopOnlyOwnedVoices() {
        var output = new Output(); var transport = new EffectAudioTransport(output);
        transport.synchronize(Map.of("music", voice("music.ogg", 20, .7, 1)), 0, false);
        transport.stop(); assertTrue(output.voices.isEmpty());
        transport.synchronize(Map.of("music", voice("music.ogg", 20.3, .7, 1)), 9_000_000_000L, false);
        assertEquals(20.3, output.voices.get("music").offsetSeconds()); assertEquals(2, output.starts);
        transport.synchronize(Map.of(), 9_100_000_000L, false);
        assertEquals(2, output.stops); assertEquals(0, transport.voiceCount());
    }
    @Test void seekingAndLoopingRestartEvenShortClipsAtTheCorrectOffset() {
        var output = new Output(); var transport = new EffectAudioTransport(output);
        transport.synchronize(Map.of("music", voice("music.ogg", 30, 1, 1)), 0, false);
        transport.synchronize(Map.of("music", voice("music.ogg", 2, 1, 1)), 200_000_000L, true);
        transport.synchronize(Map.of("music", voice("music.ogg", 2.02, 1, 1)), 220_000_000L, false);
        assertEquals(2, output.starts); assertEquals(2, output.voices.get("music").offsetSeconds());
        transport.synchronize(Map.of("music", voice("music.ogg", 2, 1, 1)), 230_000_000L, true);
        assertEquals(3, output.starts);
    }
    @Test void overlapMuteAndVolumePitchChangesAffectOnlyTheirOwnCue() {
        var output = new Output(); var transport = new EffectAudioTransport(output);
        transport.synchronize(Map.of("a", voice("same.ogg", 0, 1, 1), "b", voice("same.ogg", 5, .5, 2)), 0, false);
        transport.synchronize(Map.of("b", voice("same.ogg", 5.2, .5, 2)), 100_000_000L, false);
        assertEquals(2, output.starts); assertFalse(output.voices.containsKey("a"));
        transport.synchronize(Map.of("b", voice("same.ogg", 5.2, .2, 1)), 100_000_000L, false);
        assertEquals(3, output.starts); assertEquals(.2, output.voices.get("b").gain());
    }
    @Test void nativePositionDriftIsCorrectedWithoutFollowingNetworkJitter() {
        var output = new Output(); var transport = new EffectAudioTransport(output);
        transport.synchronize(Map.of("a", voice("beat.ogg", 10, 1, 1)), 0, false);
        output.reportedOffset = 10.24;
        transport.synchronize(Map.of("a", voice("beat.ogg", 10.2, 1, 1)), 200_000_000L, false);
        assertEquals(1, output.starts);
        output.reportedOffset = 10.25;
        transport.synchronize(Map.of("a", voice("beat.ogg", 11, 1, 1)), 1_000_000_000L, false);
        assertEquals(2, output.starts); assertEquals(11, output.voices.get("a").offsetSeconds());
    }
}

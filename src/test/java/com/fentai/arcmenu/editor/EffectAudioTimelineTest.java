package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class EffectAudioTimelineTest {
    private static EffectProtocol.Cue cue(String id, int frame, int end, double volume, double pitch) {
        return new EffectProtocol.Cue(id, frame, end, "sound", "music.ogg", volume, pitch, true);
    }

    private static EffectProtocol.Snapshot project(double fps, int duration, int start, int end,
                                                   boolean playing, boolean loop, EffectProtocol.Cue... cues) {
        return new EffectProtocol.Snapshot(1, "audio", fps, duration, start, end, loop, false,
                playing, false, start, List.of(), List.of(), List.of(cues), List.of(), List.of());
    }

    @Test void arbitrarySeekResumesInsideTheSourceInsteadOfRetriggeringItsBeginning() {
        var project = project(30, 600, 0, 600, true, false, cue("music", 60, -1, .75, 1));
        var voice = EffectAudioTimeline.desired(project, 210, source -> 20).get("music");
        assertEquals(5, voice.offsetSeconds(), 1e-9);
        assertEquals(.75, voice.gain());
        assertEquals("music.ogg", voice.source());
        assertEquals(600, voice.endFrame());
        assertEquals(0, EffectAudioTimeline.desired(project, 60, source -> 20).get("music").offsetSeconds());
        assertTrue(EffectAudioTimeline.desired(project, 59.999, source -> 20).isEmpty());
    }

    @Test void pitchAndFractionalFpsSetBothSourceOffsetAndNaturalDuration() {
        var cue = cue("fast", 12, -1, 1, 2);
        var project = project(23.976, 1000, 0, 1000, true, false, cue);
        double halfway = 12 + 23.976 * 2.5;
        assertEquals(5, EffectAudioTimeline.desired(project, halfway, source -> 10).get("fast").offsetSeconds(), 1e-9);
        double end = 12 + 23.976 * 5;
        assertEquals(end, EffectAudioTimeline.clipEnd(project, cue, 10), 1e-9);
        assertFalse(EffectAudioTimeline.desired(project, end - .001, source -> 10).isEmpty());
        assertTrue(EffectAudioTimeline.desired(project, end, source -> 10).isEmpty());
    }

    @Test void slowPitchExtendsTheClipAndKeepsItsSourcePositionAccurate() {
        var cue = cue("slow", 20, -1, 1, .5);
        var project = project(20, 500, 0, 500, true, false, cue);
        assertEquals(420, EffectAudioTimeline.clipEnd(project, cue, 10));
        assertEquals(7.5, EffectAudioTimeline.desired(project, 320, source -> 10).get("slow").offsetSeconds());
    }

    @Test void explicitEndAndNaturalEndAreBothExclusiveAndNeitherCanExtendTheOther() {
        var shortCue = cue("trimmed", 10, 40, 1, 1);
        var longCue = cue("natural", 10, 200, 1, 1);
        var project = project(10, 300, 0, 300, true, false, shortCue, longCue);
        assertEquals(40, EffectAudioTimeline.clipEnd(project, shortCue, 5));
        assertEquals(60, EffectAudioTimeline.clipEnd(project, longCue, 5));
        assertEquals(List.of("trimmed", "natural"), List.copyOf(EffectAudioTimeline.desired(project, 39.5, source -> 5).keySet()));
        assertEquals(List.of("natural"), List.copyOf(EffectAudioTimeline.desired(project, 40, source -> 5).keySet()));
        assertTrue(EffectAudioTimeline.desired(project, 60, source -> 5).isEmpty());
    }

    @Test void workAreaLimitsAuditionWithoutChangingAuthoredClipExtent() {
        var cue = cue("music", 0, -1, 1, 1);
        var project = project(25, 500, 100, 200, true, false, cue);
        assertEquals(500, EffectAudioTimeline.clipEnd(project, cue, 60));
        assertTrue(EffectAudioTimeline.desired(project, 99.999, source -> 60).isEmpty());
        var voice = EffectAudioTimeline.desired(project, 100, source -> 60).get("music");
        assertEquals(4, voice.offsetSeconds());
        assertEquals(200, voice.endFrame());
        assertFalse(EffectAudioTimeline.desired(project, 199.999, source -> 60).isEmpty());
        assertTrue(EffectAudioTimeline.desired(project, 200, source -> 60).isEmpty());
    }

    @Test void compositionEndClampsEvenAClipWithNoExplicitEnd() {
        var cue = cue("music", 90, -1, 1, 1);
        var project = project(30, 100, 0, 100, true, false, cue);
        assertEquals(100, EffectAudioTimeline.clipEnd(project, cue, 60));
        assertFalse(EffectAudioTimeline.desired(project, 99.9, source -> 60).isEmpty());
        assertTrue(EffectAudioTimeline.desired(project, 100, source -> 60).isEmpty());
    }

    @Test void overlappingInstancesOfTheSameSourceKeepIndependentPositionsAndGain() {
        var project = project(10, 200, 0, 200, true, false,
                cue("first", 0, -1, .25, 1), cue("second", 20, -1, .8, 2));
        var voices = EffectAudioTimeline.desired(project, 40, source -> 20);
        assertEquals(2, voices.size());
        assertEquals(4, voices.get("first").offsetSeconds());
        assertEquals(4, voices.get("second").offsetSeconds());
        assertEquals(.25, voices.get("first").gain());
        assertEquals(.8, voices.get("second").gain());
        assertThrows(UnsupportedOperationException.class, () -> voices.clear());
        assertNotEquals(EffectAudioTimeline.desired(project, 50, source -> 20).get("first").offsetSeconds(),
                EffectAudioTimeline.desired(project, 50, source -> 20).get("second").offsetSeconds());
    }

    @Test void disabledMutedAndNonSoundCuesNeverBecomeLocalVoices() {
        var project = project(20, 200, 0, 200, true, false,
                cue("muted", 0, -1, 0, 1),
                new EffectProtocol.Cue("disabled", 0, -1, "sound", "music.ogg", 1, 1, false),
                new EffectProtocol.Cue("command", 0, -1, "command", "say test", 1, 1, true),
                new EffectProtocol.Cue("action", 0, -1, "action", "close", 1, 1, true),
                new EffectProtocol.Cue("console", 0, -1, "console", "say test", 1, 1, true),
                new EffectProtocol.Cue("nbs", 0, -1, "nbs", "song.nbs", 1, 1, true));
        assertTrue(EffectAudioTimeline.desired(project, 5, source -> fail("Unsupported or silent cues must not request decoding")).isEmpty());
    }

    @Test void undecodedSourcesBecomeAudibleOnlyAfterTheirDurationIsAvailable() {
        var project = project(10, 100, 0, 100, true, false, cue("music", 0, -1, 1, 1));
        for (double unavailable : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0}) {
            assertTrue(EffectAudioTimeline.desired(project, 30, source -> unavailable).isEmpty());
        }
        assertEquals(3, EffectAudioTimeline.desired(project, 30, source -> 8).get("music").offsetSeconds());
    }

    @Test void loopResetAndBackwardSeekRecomputeOffsetsWithoutAccumulatingTime() {
        var project = project(10, 200, 40, 80, true, true, cue("music", 10, -1, 1, 1));
        assertEquals(6.9, EffectAudioTimeline.desired(project, 79, source -> 20).get("music").offsetSeconds());
        assertTrue(EffectAudioTimeline.desired(project, 80, source -> 20).isEmpty());
        assertEquals(3, EffectAudioTimeline.desired(project, 40, source -> 20).get("music").offsetSeconds());
        assertEquals(4, EffectAudioTimeline.desired(project, 50, source -> 20).get("music").offsetSeconds());
        assertEquals(3, EffectAudioTimeline.desired(project, 40, source -> 20).get("music").offsetSeconds());
    }

    @Test void pausedTransportStillProvidesPositionsForExplicitScrubAudition() {
        var project = project(20, 200, 0, 200, false, false, cue("music", 20, -1, 1, 1));
        assertEquals(2.025, EffectAudioTimeline.desired(project, 60.5, source -> 10).get("music").offsetSeconds(), 1e-9);
    }

    @Test void explicitScrubAuditionCanReachOutsideTheWorkAreaButNotOutsideTheComposition() {
        var project = project(20, 200, 80, 120, false, false, cue("music", 20, -1, 1, 1));
        assertTrue(EffectAudioTimeline.desired(project, 60.5, source -> 10).isEmpty());
        var voice = EffectAudioTimeline.desiredAtComposition(project, 60.5, source -> 10).get("music");
        assertEquals(2.025, voice.offsetSeconds(), 1e-9);
        assertEquals(200, voice.endFrame());
        assertNotNull(EffectAudioTimeline.desiredAtComposition(project, 150, source -> 10).get("music"));
        assertTrue(EffectAudioTimeline.desiredAtComposition(project, -1, source -> 10).isEmpty());
        assertTrue(EffectAudioTimeline.desiredAtComposition(project, 200, source -> 10).isEmpty());
    }

    @Test void invalidClockPitchAndSeekCannotProduceInvalidAudioParameters() {
        var cue = cue("music", 0, -1, 1, 1);
        var project = project(20, 200, 0, 200, true, false, cue);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(Map.of(), EffectAudioTimeline.desired(project, invalid, source -> 10));
        }
        assertTrue(EffectAudioTimeline.desired(project(0, 200, 0, 200, true, false, cue), 20, source -> 10).isEmpty());
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertTrue(EffectAudioTimeline.desired(project(20, 200, 0, 200, true, false, cue("bad", 0, -1, 1, invalid)), 20, source -> 10).isEmpty());
        }
    }
}

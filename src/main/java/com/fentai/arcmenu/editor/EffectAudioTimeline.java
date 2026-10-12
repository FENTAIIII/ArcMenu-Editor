package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Pure planning for decoded local sound clips, independent of the audio device. */
public final class EffectAudioTimeline {
    private EffectAudioTimeline() {}

    public record Voice(String cueId, String source, double offsetSeconds, double gain, double pitch,
                        double endFrame) {}

    /**
     * Finds sounds audible at an absolute timeline frame. A missing decoded duration must be NaN or
     * non-positive. Playback and scrubbing use the same offsets; the caller decides when to audition.
     * Work and clip ends are exclusive. A loop is represented by seeking back to the work start.
     */
    public static Map<String, Voice> desired(EffectProtocol.Snapshot project, double frame,
                                           ToDoubleFunction<String> duration) {
        if (project == null) return Map.of();
        return desired(project, frame, duration, Math.max(0, project.workStart()),
                Math.min(project.duration(), project.workEnd()));
    }

    /** Explicit scrub audition can reach any composition frame, including outside the work area. */
    public static Map<String, Voice> desiredAtComposition(EffectProtocol.Snapshot project, double frame,
                                                        ToDoubleFunction<String> duration) {
        if (project == null) return Map.of();
        return desired(project, frame, duration, 0, project.duration());
    }

    private static Map<String, Voice> desired(EffectProtocol.Snapshot project, double frame,
                                             ToDoubleFunction<String> duration, double start, double end) {
        if (!Double.isFinite(frame) || !validClock(project) || frame < start || frame >= end) return Map.of();

        var voices = new LinkedHashMap<String, Voice>();
        for (var cue : project.cues()) {
            if (!cue.enabled() || !cue.kind().equals("sound") || !Double.isFinite(cue.volume()) ||
                    cue.volume() <= 0 || !Double.isFinite(cue.pitch()) || cue.pitch() <= 0 || frame < cue.frame()) continue;
            double clipEnd = Math.min(end, clipEnd(project, cue, duration.applyAsDouble(cue.source())));
            if (!Double.isFinite(clipEnd) || frame >= clipEnd) continue;
            double offset = (frame - cue.frame()) / project.fps() * cue.pitch();
            if (!Double.isFinite(offset) || offset < 0) continue;
            voices.put(cue.id(), new Voice(cue.id(), cue.source(), offset, cue.volume(), cue.pitch(), clipEnd));
        }
        return Collections.unmodifiableMap(voices);
    }

    /**
     * Returns a decoded clip's exclusive end within the composition, or NaN when its duration or
     * timing is unavailable. The work area does not shorten the authored clip drawn by the editor.
     */
    public static double clipEnd(EffectProtocol.Snapshot project, EffectProtocol.Cue cue,
                                 double durationSeconds) {
        if (project == null || cue == null || !validClock(project) ||
                !Double.isFinite(durationSeconds) || durationSeconds <= 0 ||
                !Double.isFinite(cue.pitch()) || cue.pitch() <= 0) return Double.NaN;
        double end = Math.min(project.duration(), cue.frame() + durationSeconds / cue.pitch() * project.fps());
        if (cue.end() >= 0) end = Math.min(end, cue.end());
        return end;
    }

    private static boolean validClock(EffectProtocol.Snapshot project) {
        return Double.isFinite(project.fps()) && project.fps() > 0 && project.duration() > 0;
    }
}

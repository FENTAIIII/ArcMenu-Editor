package com.fentai.arcmenu.editor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps decoded clip voices running across ordinary snapshots and restarts only on discontinuities. */
public final class EffectAudioTransport
{
    public interface Output
    {
        void start(String id, EffectAudioTimeline.Voice voice);

        void stop(String id);

        default double offsetSeconds(String id)
        {
            return Double.NaN;
        }
    }

    private record Playing(EffectAudioTimeline.Voice voice, long startedAt)
    {}

    private final Output output;
    private final Map<String, Playing> playing = new LinkedHashMap<>();

    public EffectAudioTransport(Output output)
    {
        this.output = output;
    }

    public void synchronize(Map<String, EffectAudioTimeline.Voice> desired, long now, boolean discontinuity)
    {
        for (String id : List.copyOf(playing.keySet()))
        {
            if (!desired.containsKey(id))
            {
                output.stop(id);
                playing.remove(id);
            }
        }
        desired.forEach((id, voice) ->
        {
            Playing current = playing.get(id);
            double actual = current == null ? Double.NaN : output.offsetSeconds(id);
            double expected = current == null ? Double.NaN
                    : Double.isFinite(actual) ? actual
                            : current.voice.offsetSeconds()
                                    + Math.max(0, now - current.startedAt) / 1_000_000_000.0 * current.voice.pitch();
            if (current == null || discontinuity || !sameClip(current.voice, voice)
                    || Math.abs(expected - voice.offsetSeconds()) > .15 * voice.pitch())
            {
                if (current != null)
                {
                    output.stop(id);
                }
                output.start(id, voice);
                playing.put(id, new Playing(voice, now));
            }
        });
    }

    private static boolean sameClip(EffectAudioTimeline.Voice first, EffectAudioTimeline.Voice second)
    {
        return first.source().equals(second.source()) && first.gain() == second.gain()
                && first.pitch() == second.pitch();
    }

    public void stop()
    {
        for (String id : playing.keySet())
        {
            output.stop(id);
        }
        playing.clear();
    }

    public int voiceCount()
    {
        return playing.size();
    }
}

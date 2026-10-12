package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;

/** Client-only audio transport for the Effect workspace; never executes server-side cues. */
public final class EffectAudioPreview implements AutoCloseable {
    public static final EffectAudioPreview INSTANCE = new EffectAudioPreview();
    private final EffectAudioAssets assets = EffectAudioAssets.INSTANCE;
    private final EffectAudioOutput output = EffectAudioOutput.INSTANCE;
    private final Map<String, EffectAudioAssets.Clip> voiceClips = new LinkedHashMap<>();
    private final EffectAudioTransport transport = new EffectAudioTransport(new EffectAudioTransport.Output() {
        @Override public void start(String id, EffectAudioTimeline.Voice voice) {
            var clip = assets.ready("sound", voice.source()); if (clip == null) return;
            voiceClips.put(id, clip);
            output.play(id, clip, voice.offsetSeconds() * clip.basePitch(), voice.gain() * clip.baseVolume(), voice.pitch() * clip.basePitch());
        }
        @Override public void stop(String id) { output.stop(id); voiceClips.remove(id); }
        @Override public double offsetSeconds(String id) {
            var clip = voiceClips.get(id); double offset = output.offsetSeconds(id);
            return clip == null || offset < 0 ? Double.NaN : offset / clip.basePitch();
        }
    });
    private boolean enabled = true, waitingControl, audition, suspended;
    private long generation = -1, auditionUntil;
    private int auditionFrame;
    private double previousFrame = Double.NaN;
    private String project = "";
    private String configuredDevice;
    private EffectProtocol.Snapshot preloaded;
    private EffectAudioPreview() {}

    public boolean enabled() { return enabled; }
    public void enabled(boolean value) { enabled = value; if (!value) stop(); }
    public void control(String action, int frame) {
        transport.stop(); previousFrame = Double.NaN; waitingControl = true; suspended = false;
        audition = action.equals("seek") && enabled;
        auditionFrame = frame; auditionUntil = System.nanoTime() + 140_000_000L;
    }
    public void received(EffectProtocol.Snapshot snapshot) {
        if (!snapshot.id().equals(project)) { stop(); project = snapshot.id(); preloaded = null; }
        suspended = false;
        preload(snapshot);
    }
    private void preload(EffectProtocol.Snapshot snapshot) {
        if (preloaded != null && preloaded.assets().equals(snapshot.assets()) && preloaded.cues().equals(snapshot.cues()) && generation == assets.generation()) return;
        generation = assets.generation(); preloaded = snapshot;
        assets.preload(snapshot.cues());
        for (String asset : snapshot.assets()) if (asset.endsWith(".ogg")) assets.load("sound", asset);
    }
    public void update(boolean workspaceVisible) {
        var state = ArcMenuEditorClient.EFFECT; var snapshot = state.snapshot();
        if (!workspaceVisible || snapshot == null || !enabled || suspended) { transport.stop(); previousFrame = Double.NaN; return; }
        if (generation != assets.generation()) { transport.stop(); preloaded = null; previousFrame = Double.NaN; }
        preload(snapshot);
        var client = Minecraft.getInstance();
        String device = client.options.soundDevice().get();
        if (!Objects.equals(device, configuredDevice)) { transport.stop(); previousFrame = Double.NaN; configuredDevice = device; }
        output.configure(device, client.options.getSoundSourceVolume(SoundSource.MASTER));
        long now = System.nanoTime();
        if (waitingControl && !state.controlPending()) waitingControl = false;
        boolean scrubbing = audition && now < auditionUntil;
        if (audition && !scrubbing) audition = false;
        if (!scrubbing && (waitingControl || !snapshot.playing())) { transport.stop(); previousFrame = Double.NaN; return; }
        double frame = scrubbing ? auditionFrame : state.playhead();
        Map<String, EffectAudioTimeline.Voice> desired = scrubbing ?
                EffectAudioTimeline.desiredAtComposition(snapshot, frame, this::durationSeconds) :
                EffectAudioTimeline.desired(snapshot, frame, this::durationSeconds);
        boolean wrap = Double.isFinite(previousFrame) && frame < previousFrame - .5;
        transport.synchronize(desired, now, wrap); previousFrame = frame;
    }
    public double durationSeconds(String source) {
        var clip = assets.ready("sound", source); return clip == null ? Double.NaN : clip.durationSeconds() / clip.basePitch();
    }
    public double peak(String source, double startSeconds, double endSeconds) {
        var clip = assets.ready("sound", source); return clip == null ? 0 : clip.peak(startSeconds * clip.basePitch(), endSeconds * clip.basePitch());
    }
    public void stop() { transport.stop(); output.stopAll(); waitingControl = false; audition = false; previousFrame = Double.NaN; }
    public void fail() { stop(); suspended = true; }
    public void disconnected() { stop(); project = ""; preloaded = null; assets.invalidate(); }
    public String statusKey() {
        if (!output.error().isEmpty()) return "audio_missing";
        var snapshot = ArcMenuEditorClient.EFFECT.snapshot(); if (snapshot == null) return "";
        String result = "";
        for (var cue : snapshot.cues()) if (cue.kind().equals("sound") && cue.enabled()) {
            String phase = assets.status("sound", cue.source()).phase();
            if (phase.equals("error")) return "audio_missing";
            if (phase.equals("loading") || phase.equals("idle")) result = "audio_loading";
            else if (result.isEmpty()) result = "audio_ready";
        }
        return result;
    }
    public JsonObject metadata() {
        var result = new JsonObject(); result.addProperty("enabled", enabled); result.addProperty("voiceCount", transport.voiceCount());
        result.addProperty("generation", assets.generation()); result.addProperty("audition", audition);
        result.addProperty("status", statusKey()); result.addProperty("error", output.error());
        var cached = new JsonArray(); var voices = new JsonArray();
        var snapshot = ArcMenuEditorClient.EFFECT.snapshot();
        if (snapshot != null) {
            var sources = new LinkedHashSet<>(snapshot.assets().stream().filter(s -> s.endsWith(".ogg")).toList());
            snapshot.cues().stream().filter(c -> c.kind().equals("sound")).forEach(c -> sources.add(c.source()));
            for (String source : sources) {
                var status = assets.status("sound", source); var item = new JsonObject();
                item.addProperty("source", source); item.addProperty("phase", status.phase()); item.addProperty("error", status.error());
                double duration = durationSeconds(source); if (Double.isFinite(duration)) item.addProperty("durationSeconds", duration);
                cached.add(item);
            }
        }
        voiceClips.forEach((id, clip) -> {
            var item = new JsonObject(); item.addProperty("id", id); double offset = output.offsetSeconds(id);
            if (Double.isFinite(offset) && offset >= 0) item.addProperty("offsetSeconds", offset);
            item.addProperty("playing", output.isPlaying(id)); voices.add(item);
        });
        result.add("assets", cached); result.add("voices", voices); return result;
    }
    @Override public void close() { stop(); assets.close(); output.close(); }
}

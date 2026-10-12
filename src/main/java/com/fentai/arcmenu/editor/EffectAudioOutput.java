package com.fentai.arcmenu.editor;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;
import java.util.List;

/** Editor-only voices use a thread-local context; the game's sound engine keeps its own context. */
public final class EffectAudioOutput implements AutoCloseable {
    public static final EffectAudioOutput INSTANCE = new EffectAudioOutput();
    private static final long MAX_BUFFER_BYTES = 256_000_000;
    private static final int MAX_VOICES = 128;
    private record Buffer(int id, long bytes) {}
    private record Configuration(String device, double volume) {}
    private static final class Voice {
        final int source;
        final EffectAudioAssets.Clip clip;
        final double gain;
        final long request;
        Voice(int source, EffectAudioAssets.Clip clip, double gain, long request) { this.source = source; this.clip = clip; this.gain = gain; this.request = request; }
    }
    public record VoiceStatus(boolean playing, double offsetSeconds) {}
    private final ScheduledThreadPoolExecutor audio = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "ArcMenu Effect audio output"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean audioStarted = new AtomicBoolean();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Long> requests = new ConcurrentHashMap<>();
    private final Map<String, VoiceStatus> status = new ConcurrentHashMap<>();
    // The following maps and context fields are used only on the audio thread.
    private final Map<String, Voice> voices = new LinkedHashMap<>();
    private final Map<EffectAudioAssets.Clip, Buffer> buffers = new LinkedHashMap<>(16, .75f, true);
    private long device, context, bufferedBytes;
    private ScheduledFuture<?> pollTask;
    private volatile String lastError = "";
    private String selectedDevice = "";
    private double masterVolume = 1;
    private volatile Configuration requestedConfiguration = new Configuration("", 1);

    private EffectAudioOutput() {
        audio.setRemoveOnCancelPolicy(true);
        audio.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /** Mirror the game's selected output device and MASTER volume before preview playback. */
    public synchronized void configure(String deviceName, double volume) {
        String normalized = deviceName == null || deviceName.isBlank() || deviceName.equalsIgnoreCase("default") ? "" : deviceName;
        if (!Double.isFinite(volume)) throw new IllegalArgumentException("Master volume must be finite");
        double gain = Math.max(0, Math.min(1, volume));
        if (closed.get()) return;
        Configuration configuration = new Configuration(normalized, gain);
        if (configuration.equals(requestedConfiguration)) return;
        requestedConfiguration = configuration;
        if (audioStarted.get()) audio.execute(() -> applyConfiguration(configuration));
    }

    /** Offset is measured in decoded source seconds, independently of playback pitch. */
    public CompletableFuture<Boolean> play(String id, EffectAudioAssets.Clip clip, double sourceOffsetSeconds, double gain, double pitch) {
        if (id == null || id.isBlank() || clip == null || !Double.isFinite(sourceOffsetSeconds) || sourceOffsetSeconds < 0
                || !Double.isFinite(gain) || gain < 0 || !Double.isFinite(pitch) || pitch <= 0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Effect audio voice parameters"));
        if (closed.get()) return CompletableFuture.completedFuture(false);
        long request = sequence.incrementAndGet(), currentEpoch = epoch.get();
        requests.put(id, request);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        audioStarted.set(true);
        audio.execute(() -> {
            if (closed.get() || currentEpoch != epoch.get() || !Objects.equals(requests.get(id), request)) { result.complete(false); return; }
            int source = 0;
            try {
                removeVoice(id);
                if (clip.generation() != EffectAudioAssets.INSTANCE.generation() || clip.frameAt(sourceOffsetSeconds) >= clip.frameCount()) {
                    requests.remove(id, request); result.complete(false); return;
                }
                applyConfiguration(requestedConfiguration); ensureContext();
                if (voices.size() >= MAX_VOICES) throw new IllegalStateException("Too many overlapping Effect audio voices");
                int buffer = buffer(clip);
                source = AL10.alGenSources(); check("Create audio voice");
                AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
                AL10.alSourcef(source, AL10.AL_GAIN, (float) (Math.min(16, gain) * masterVolume));
                AL10.alSourcef(source, AL10.AL_PITCH, (float) Math.max(.001, Math.min(16, pitch)));
                AL10.alSourcei(source, AL11.AL_SAMPLE_OFFSET, clip.frameAt(sourceOffsetSeconds));
                AL10.alSourcePlay(source); check("Start audio voice");
                voices.put(id, new Voice(source, clip, Math.min(16, gain), request));
                status.put(id, new VoiceStatus(true, (double) clip.frameAt(sourceOffsetSeconds) / clip.sampleRate()));
                if (pollTask == null) pollTask = audio.scheduleWithFixedDelay(this::poll, 20, 20, TimeUnit.MILLISECONDS);
                lastError = ""; result.complete(true);
            } catch (Throwable error) {
                if (source != 0 && context != 0) AL10.alDeleteSources(source);
                requests.remove(id, request); status.remove(id);
                lastError = message(error); result.completeExceptionally(error);
            }
        });
        return result;
    }

    public VoiceStatus status(String id) { return status.getOrDefault(id, new VoiceStatus(false, -1)); }
    public double offsetSeconds(String id) { return status(id).offsetSeconds(); }
    public boolean isPlaying(String id) { return status(id).playing(); }
    public int activeVoices() { return status.size(); }
    public String error() { return lastError; }

    public void stop(String id) {
        if (id == null) return;
        requests.remove(id); status.remove(id);
        if (!closed.get() && audioStarted.get()) audio.execute(() -> removeVoice(id));
    }
    public void stopAll() {
        epoch.incrementAndGet(); requests.clear(); status.clear();
        if (!closed.get() && audioStarted.get()) audio.execute(this::removeAllVoices);
    }
    /** Called after resource reload to drop native buffers from the previous pack generation. */
    public void invalidate() {
        epoch.incrementAndGet(); requests.clear(); status.clear();
        if (!closed.get() && audioStarted.get()) audio.execute(() -> { removeAllVoices(); removeAllBuffers(); });
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        epoch.incrementAndGet(); requests.clear(); status.clear();
        if (audioStarted.get()) audio.execute(this::disposeContext);
        audio.shutdown();
    }

    private void applyConfiguration(Configuration configuration) {
        if (!Objects.equals(selectedDevice, configuration.device)) {
            disposeContext(); selectedDevice = configuration.device;
        }
        if (masterVolume != configuration.volume) {
            masterVolume = configuration.volume;
            if (context != 0) for (Voice voice : voices.values()) AL10.alSourcef(voice.source, AL10.AL_GAIN, (float) (voice.gain * masterVolume));
        }
    }

    private void ensureContext() {
        if (context != 0) return;
        device = selectedDevice.isEmpty() ? ALC10.alcOpenDevice((ByteBuffer) null) : ALC10.alcOpenDevice(selectedDevice);
        if (device == 0 && !selectedDevice.isEmpty()) device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0) throw new IllegalStateException("No audio output device is available for Effect preview");
        try {
            var capabilities = ALC.createCapabilities(device);
            if (!capabilities.ALC_EXT_thread_local_context)
                throw new IllegalStateException("Audio driver does not support an isolated Effect preview context");
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0 || !EXTThreadLocalContext.alcSetThreadContext(context))
                throw new IllegalStateException("Cannot create Effect audio preview context");
            AL.createCapabilities(capabilities);
            AL10.alDistanceModel(AL10.AL_NONE);
            AL10.alListenerf(AL10.AL_GAIN, 1);
            check("Initialize Effect audio output");
        } catch (Throwable error) { disposeContext(); throw error; }
    }

    private int buffer(EffectAudioAssets.Clip clip) {
        Buffer cached = buffers.get(clip);
        if (cached != null) return cached.id;
        var iterator = buffers.entrySet().iterator();
        while (bufferedBytes + clip.byteSize() > MAX_BUFFER_BYTES && iterator.hasNext()) {
            var entry = iterator.next();
            if (voices.values().stream().anyMatch(voice -> voice.clip == entry.getKey())) continue;
            AL10.alDeleteBuffers(entry.getValue().id); bufferedBytes -= entry.getValue().bytes; iterator.remove();
        }
        if (bufferedBytes + clip.byteSize() > MAX_BUFFER_BYTES)
            throw new IllegalStateException("Active Effect audio voices exceed the output buffer limit");
        int buffer = AL10.alGenBuffers(); check("Create audio buffer");
        ShortBuffer pcm = null;
        try {
            pcm = MemoryUtil.memAllocShort(Math.multiplyExact(clip.frameCount(), clip.channels()));
            clip.copyTo(pcm);
            AL10.alBufferData(buffer, clip.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16, pcm, clip.sampleRate());
            check("Upload audio samples");
            buffers.put(clip, new Buffer(buffer, clip.byteSize())); bufferedBytes += clip.byteSize();
            return buffer;
        } catch (Throwable error) { AL10.alDeleteBuffers(buffer); throw error; }
        finally { if (pcm != null) MemoryUtil.memFree(pcm); }
    }

    private void poll() {
        if (context == 0 || closed.get()) return;
        try {
            var iterator = voices.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next(); Voice voice = entry.getValue();
                if (AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                    AL10.alDeleteSources(voice.source); iterator.remove(); status.remove(entry.getKey()); requests.remove(entry.getKey(), voice.request);
                } else status.put(entry.getKey(), new VoiceStatus(true, (double) AL10.alGetSourcei(voice.source, AL11.AL_SAMPLE_OFFSET) / voice.clip.sampleRate()));
            }
            if (voices.isEmpty()) stopPolling();
        } catch (Throwable error) { lastError = message(error); removeAllVoices(); }
    }
    private void removeVoice(String id) {
        Voice voice = voices.remove(id); status.remove(id);
        if (voice != null && context != 0) { AL10.alSourceStop(voice.source); AL10.alDeleteSources(voice.source); }
        if (voices.isEmpty()) stopPolling();
    }
    private void removeAllVoices() {
        for (String id : List.copyOf(voices.keySet())) removeVoice(id);
        stopPolling();
    }
    private void stopPolling() {
        if (pollTask != null) { pollTask.cancel(false); pollTask = null; }
    }
    private void removeAllBuffers() {
        if (context != 0) for (Buffer buffer : buffers.values()) AL10.alDeleteBuffers(buffer.id);
        buffers.clear(); bufferedBytes = 0;
    }
    private void disposeContext() {
        removeAllVoices(); removeAllBuffers();
        if (context != 0) {
            EXTThreadLocalContext.alcSetThreadContext(0); AL.setCurrentThread(null);
            ALC10.alcDestroyContext(context); context = 0;
        }
        if (device != 0) { ALC10.alcCloseDevice(device); device = 0; }
    }
    private static void check(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw new IllegalStateException(operation + " failed (OpenAL error " + error + ")");
    }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}

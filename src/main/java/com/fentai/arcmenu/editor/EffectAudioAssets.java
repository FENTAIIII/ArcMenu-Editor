package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/** Decoded audio belongs to the active resource-pack generation, never the server filesystem. */
public final class EffectAudioAssets implements AutoCloseable {
    public static final EffectAudioAssets INSTANCE = new EffectAudioAssets();
    private static final int MAX_OGG_BYTES = 64_000_000;
    private static final long MAX_PCM_BYTES = 256_000_000;
    private static final int MAX_ENTRIES = 512;
    private record Key(String kind, String source) {}
    public record Status(String phase, String error, String resourceId, double durationSeconds, int channels, int sampleRate, long generation) {}
    private final AtomicLong generation = new AtomicLong(1);
    private final Map<Key, CompletableFuture<Clip>> cache = new LinkedHashMap<>(16, .75f, true);
    private Set<Key> pinned = Set.of();
    private EffectSoundResolver resolver;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ArcMenu Effect audio decode"); thread.setDaemon(true); return thread;
    });
    private long cachedBytes;
    private boolean closed;

    private EffectAudioAssets() {}
    public long generation() { return generation.get(); }

    public CompletableFuture<Clip> load(String kind, String source) {
        Key key = new Key(kind == null ? "" : kind, source == null ? "" : source.trim());
        synchronized (cache) {
            if (closed) return CompletableFuture.failedFuture(new IOException("Effect audio cache is closed"));
            CompletableFuture<Clip> existing = cache.get(key);
            if (existing != null) return existing;
            evict(MAX_ENTRIES - 1, MAX_PCM_BYTES, null);
            if (cache.size() >= MAX_ENTRIES) return CompletableFuture.failedFuture(new IOException("Too many Effect audio assets are loading"));
            long expected = generation();
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            if (resolver == null) resolver = resolver(resources);
            EffectSoundResolver soundResolver = resolver;
            CompletableFuture<Clip> result = new CompletableFuture<>();
            cache.put(key, result);
            workers.execute(() -> {
                try {
                    Clip clip = read(resources, soundResolver, key, expected);
                    synchronized (cache) {
                        if (closed || generation() != expected || cache.get(key) != result)
                            throw new CancellationException("Resource pack changed while audio was loading");
                        evict(MAX_ENTRIES, MAX_PCM_BYTES - clip.byteSize(), key);
                        if (cachedBytes + clip.byteSize() > MAX_PCM_BYTES)
                            throw new IOException("Referenced Effect audio assets exceed the decoded cache size limit");
                        cachedBytes += clip.byteSize();
                        result.complete(clip);
                    }
                } catch (Throwable error) { result.completeExceptionally(error); }
            });
            return result;
        }
    }

    public void preload(Collection<EffectProtocol.Cue> cues) {
        synchronized (cache) {
            pinned = cues.stream().filter(cue -> "sound".equals(cue.kind()))
                .map(cue -> new Key("sound", cue.source().trim())).collect(Collectors.toUnmodifiableSet());
        }
        for (var cue : cues) if ("sound".equals(cue.kind())) load("sound", cue.source());
    }

    /** Does not block or start I/O; callers preload before requesting a waveform or a voice. */
    public Clip ready(String kind, String source) {
        synchronized (cache) {
            CompletableFuture<Clip> future = cache.get(new Key(kind, source == null ? "" : source.trim()));
            return future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled() ? null : future.getNow(null);
        }
    }

    public Status status(String kind, String source) {
        synchronized (cache) {
            CompletableFuture<Clip> future = cache.get(new Key(kind, source == null ? "" : source.trim()));
            if (future == null) return new Status("idle", "", "", 0, 0, 0, generation());
            if (!future.isDone()) return new Status("loading", "", "", 0, 0, 0, generation());
            if (future.isCompletedExceptionally() || future.isCancelled()) {
                String message;
                try { future.join(); message = "Audio load failed"; }
                catch (RuntimeException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                }
                return new Status("error", message, "", 0, 0, 0, generation());
            }
            Clip clip = future.getNow(null);
            return new Status("ready", "", clip.resourceId(), clip.durationSeconds(), clip.channels(), clip.sampleRate(), clip.generation());
        }
    }

    public void invalidate() {
        synchronized (cache) {
            generation.incrementAndGet();
            cache.values().forEach(future -> future.cancel(false));
            cache.clear(); cachedBytes = 0; pinned = Set.of(); resolver = null;
        }
        EffectAudioOutput.INSTANCE.invalidate();
    }

    @Override public void close() {
        synchronized (cache) { if (closed) return; closed = true; }
        invalidate(); workers.shutdownNow();
    }

    private void evict(int maxEntries, long maxBytes, Key keep) {
        var iterator = cache.entrySet().iterator();
        while ((cache.size() > maxEntries || cachedBytes > maxBytes) && iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().equals(keep) || pinned.contains(entry.getKey()) || !entry.getValue().isDone()) continue;
            CompletableFuture<Clip> future = entry.getValue();
            if (!future.isCompletedExceptionally() && !future.isCancelled()) cachedBytes -= future.getNow(null).byteSize();
            iterator.remove();
        }
    }

    private static EffectSoundResolver resolver(ResourceManager resources) {
        return new EffectSoundResolver(new EffectSoundResolver.Resources() {
            @Override public List<String> soundDefinitions(String namespace) throws IOException {
                var json = Identifier.fromNamespaceAndPath(namespace, "sounds.json");
                var documents = new ArrayList<String>();
                for (var resource : resources.getResourceStack(json)) {
                    try (InputStream stream = resource.open()) {
                        byte[] bytes = stream.readNBytes(8_000_001);
                        if (bytes.length > 8_000_000) throw new IOException("Sound definitions are too large: " + json);
                        documents.add(new String(bytes, StandardCharsets.UTF_8));
                    }
                }
                return documents;
            }
            @Override public boolean exists(String resource) { return resources.getResource(Identifier.parse(resource)).isPresent(); }
        });
    }
    private static Clip read(ResourceManager resources, EffectSoundResolver resolver, Key key, long generation) throws IOException {
        EffectSoundResolver.Resolved resolved = resolver.resolve(key.kind, key.source);
        byte[] bytes;
        try (InputStream stream = resources.getResourceOrThrow(Identifier.parse(resolved.resourceId())).open()) {
            bytes = stream.readNBytes(MAX_OGG_BYTES + 1);
        }
        if (bytes.length > MAX_OGG_BYTES) throw new IOException("Effect audio file exceeds the compressed size limit");
        return decode(resolved.resourceId(), bytes, resolved.baseVolume(), resolved.basePitch(), generation);
    }

    static Clip decode(String resource, byte[] data, double baseVolume, double basePitch, long generation) throws IOException {
        if (data.length < 4 || data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S')
            throw new IOException("Sound file is not an OGG Vorbis stream: " + resource);
        ByteBuffer compressed = MemoryUtil.memAlloc(data.length);
        long decoder = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            compressed.put(data).flip();
            var error = stack.mallocInt(1);
            decoder = STBVorbis.stb_vorbis_open_memory(compressed, error, null);
            if (decoder == 0) throw new IOException("Cannot decode OGG Vorbis sound (error " + error.get(0) + "): " + resource);
            STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(decoder, STBVorbisInfo.malloc(stack));
            int channels = info.channels(), rate = info.sample_rate();
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(decoder);
            if (channels < 1 || channels > 2 || rate <= 0 || frames <= 0)
                throw new IOException("Effect preview requires a nonempty mono or stereo Vorbis stream: " + resource);
            long samples = (long) frames * channels;
            if (samples * Short.BYTES > MAX_PCM_BYTES) throw new IOException("Decoded Effect audio exceeds the PCM size limit: " + resource);
            ShortBuffer pcm = MemoryUtil.memAllocShort((int) samples);
            try {
                int decoded = 0;
                while (decoded < frames) {
                    pcm.position(decoded * channels);
                    int count = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcm);
                    if (count == 0) break;
                    decoded += count;
                }
                if (decoded == 0) throw new IOException("OGG stream contains no decoded samples: " + resource);
                short[] samplesArray = new short[decoded * channels];
                pcm.position(0); pcm.get(samplesArray);
                return new Clip(resource, channels, rate, samplesArray, baseVolume, basePitch, generation);
            } finally { MemoryUtil.memFree(pcm); }
        } finally {
            if (decoder != 0) STBVorbis.stb_vorbis_close(decoder);
            MemoryUtil.memFree(compressed);
        }
    }

    /** Immutable interleaved signed 16-bit PCM and a small envelope index for waveform rendering. */
    public static final class Clip {
        private static final int PEAK_FRAMES = 256;
        private final String resourceId;
        private final int channels, sampleRate;
        private final short[] samples;
        private final float[] peaks;
        private final double baseVolume, basePitch;
        private final long generation;

        Clip(String resourceId, int channels, int sampleRate, short[] samples, double baseVolume, double basePitch, long generation) {
            if (channels < 1 || channels > 2 || sampleRate <= 0 || samples.length == 0 || samples.length % channels != 0
                    || !Double.isFinite(baseVolume) || baseVolume < 0 || !Double.isFinite(basePitch) || basePitch <= 0)
                throw new IllegalArgumentException("Invalid decoded audio metadata");
            this.resourceId = resourceId; this.channels = channels; this.sampleRate = sampleRate;
            this.samples = samples; this.baseVolume = baseVolume; this.basePitch = basePitch; this.generation = generation;
            peaks = new float[(frameCount() + PEAK_FRAMES - 1) / PEAK_FRAMES];
            for (int frame = 0; frame < frameCount(); frame++)
                for (int channel = 0; channel < channels; channel++)
                    peaks[frame / PEAK_FRAMES] = Math.max(peaks[frame / PEAK_FRAMES], amplitude(samples[frame * channels + channel]));
        }
        public String resourceId() { return resourceId; }
        public int channels() { return channels; }
        public int sampleRate() { return sampleRate; }
        public int frameCount() { return samples.length / channels; }
        public double durationSeconds() { return (double) frameCount() / sampleRate; }
        public double baseVolume() { return baseVolume; }
        public double basePitch() { return basePitch; }
        public long generation() { return generation; }
        long byteSize() { return (long) samples.length * Short.BYTES; }
        void copyTo(ShortBuffer buffer) { buffer.put(samples).flip(); }
        public int frameAt(double sourceSeconds) {
            if (!Double.isFinite(sourceSeconds)) throw new IllegalArgumentException("Audio offset must be finite");
            return (int) Math.min(frameCount(), Math.max(0, Math.floor(sourceSeconds * sampleRate)));
        }
        public float peak(double startSeconds, double endSeconds) {
            int start = frameAt(startSeconds), end = frameAt(endSeconds);
            if (end <= start) return 0;
            float peak = 0;
            while (start < end && start % PEAK_FRAMES != 0) peak = Math.max(peak, framePeak(start++));
            while (start + PEAK_FRAMES <= end) { peak = Math.max(peak, peaks[start / PEAK_FRAMES]); start += PEAK_FRAMES; }
            while (start < end) peak = Math.max(peak, framePeak(start++));
            return peak;
        }
        private float framePeak(int frame) {
            float result = 0;
            for (int channel = 0; channel < channels; channel++) result = Math.max(result, amplitude(samples[frame * channels + channel]));
            return result;
        }
        private static float amplitude(short sample) { return Math.abs((int) sample) / 32768f; }
    }
}

package com.fentai.arcmenu.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Reuses downloaded cover bytes without sharing a caller's cancellation or retaining unlimited images. */
final class MarketImageCache {
    private record Loading(CompletableFuture<byte[]> result, CompletableFuture<byte[]> source) {}
    private final long maximumBytes;
    private final Map<String, byte[]> images = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Loading> downloads = new LinkedHashMap<>();
    private long retainedBytes;

    MarketImageCache(long maximumBytes) { this.maximumBytes = maximumBytes; }

    synchronized CompletableFuture<byte[]> get(String key, Supplier<CompletableFuture<byte[]>> download) {
        byte[] cached = images.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        var existing = downloads.get(key);
        if (existing != null) return existing.result().thenApply(bytes -> bytes);
        var result = new CompletableFuture<byte[]>();
        final CompletableFuture<byte[]> source;
        try { source = download.get(); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
        var loading = new Loading(result, source);
        downloads.put(key, loading);
        source.whenComplete((bytes, error) -> {
            synchronized (this) {
                if (downloads.get(key) != loading) return;
                downloads.remove(key);
                if (error == null && bytes.length <= maximumBytes) {
                    images.put(key, bytes);
                    retainedBytes += bytes.length;
                    var entries = images.entrySet().iterator();
                    while (retainedBytes > maximumBytes && entries.hasNext()) {
                        retainedBytes -= entries.next().getValue().length;
                        entries.remove();
                    }
                }
            }
            if (error == null) result.complete(bytes); else result.completeExceptionally(error);
        });
        return result.thenApply(bytes -> bytes);
    }

    void clear() {
        final ArrayList<Loading> cancelled;
        synchronized (this) {
            images.clear();
            retainedBytes = 0;
            cancelled = new ArrayList<>(downloads.values());
            downloads.clear();
        }
        for (var loading : cancelled) {
            loading.result().completeExceptionally(new IllegalStateException("Marketplace connection changed during preview download"));
            loading.source().cancel(true);
        }
    }
}

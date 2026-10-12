package com.fentai.arcmenu.editor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketImageCacheTest {
    @Test void simultaneousViewsShareOneDownloadAndReuseItsBytes() {
        var cache = new MarketImageCache(100);
        var calls = new AtomicInteger();
        var source = new CompletableFuture<byte[]>();
        var first = cache.get("cover", () -> { calls.incrementAndGet(); return source; });
        var second = cache.get("cover", () -> { calls.incrementAndGet(); return source; });
        byte[] bytes = {1, 2, 3};
        source.complete(bytes);
        assertSame(bytes, first.join());
        assertSame(bytes, second.join());
        assertSame(bytes, cache.get("cover", () -> { calls.incrementAndGet(); return source; }).join());
        assertEquals(1, calls.get());
    }

    @Test void cancellingOneViewDoesNotCancelOtherViewsOrTheDownload() {
        var cache = new MarketImageCache(100);
        var source = new CompletableFuture<byte[]>();
        var first = cache.get("cover", () -> source);
        var second = cache.get("cover", () -> fail("Duplicate download"));
        first.cancel(true);
        assertFalse(source.isCancelled());
        byte[] bytes = {7};
        source.complete(bytes);
        assertSame(bytes, second.join());
    }

    @Test void failuresCanRetryAndByteBudgetEvictsLeastRecentlyUsedImages() {
        var cache = new MarketImageCache(4);
        assertThrows(RuntimeException.class, () -> cache.get("failed", () -> CompletableFuture.failedFuture(new IllegalStateException("Offline"))).join());
        assertArrayEquals(new byte[]{1}, cache.get("failed", () -> CompletableFuture.completedFuture(new byte[]{1})).join());
        cache.get("a", () -> CompletableFuture.completedFuture(new byte[]{1, 2})).join();
        cache.get("b", () -> CompletableFuture.completedFuture(new byte[]{3, 4})).join();
        cache.get("a", () -> fail("Cached image was not reused")).join();
        cache.get("c", () -> CompletableFuture.completedFuture(new byte[]{5, 6})).join();
        var calls = new AtomicInteger();
        cache.get("b", () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new byte[]{9, 10}); }).join();
        assertEquals(1, calls.get());
    }

    @Test void connectionResetCancelsPendingRequestsAndCannotRetainAnOldCover() {
        var cache = new MarketImageCache(100);
        var source = new CompletableFuture<byte[]>();
        var view = cache.get("cover", () -> source);
        cache.clear();
        assertThrows(RuntimeException.class, view::join);
        assertTrue(source.isCancelled());
        assertArrayEquals(new byte[]{2}, cache.get("cover", () -> CompletableFuture.completedFuture(new byte[]{2})).join());
    }

    @Test void aPreviewLargerThanTheBudgetIsDisplayedButNeverRetained() {
        var cache = new MarketImageCache(1);
        var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(new byte[]{1, 2}, cache.get("large", () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(new byte[]{1, 2}); }).join());
        }
        assertEquals(2, calls.get());
    }
}

package com.fentai.arcmenu.editor;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarketAuthenticationTest {
    @Test void successfulSignInDoesNotRetry() {
        var queue = new Tasks();
        var response = new JsonObject();
        var result = MarketAuthentication.retry(() -> CompletableFuture.completedFuture(response),
                error -> fail("Successful authentication must not test failures"),
                () -> fail("Successful authentication must not announce a retry"), queue);
        assertSame(response, result.join());
        assertTrue(queue.isEmpty());
    }

    @Test void rejectedCredentialsPreserveTheirCauseWithoutRetrying() {
        var queue = new Tasks();
        var rejected = new IllegalStateException("Session rejected");
        var result = MarketAuthentication.retry(() -> CompletableFuture.failedFuture(new CompletionException(rejected)),
                error -> error instanceof IOException, () -> fail("Permanent failures must not retry"), queue);
        assertSame(rejected, assertThrows(CompletionException.class, result::join).getCause());
        assertTrue(queue.isEmpty());
    }

    @Test void transientFailuresHaveExactlyOneDelayedRetryAndKeepTheFinalCause() {
        var queue = new Tasks();
        var attempts = new AtomicInteger();
        var retryStages = new AtomicInteger();
        var firstFailure = new IOException("Temporary session outage");
        var secondFailure = new IOException("Still unavailable");
        var result = MarketAuthentication.retry(() -> CompletableFuture.failedFuture(
                        new CompletionException(attempts.incrementAndGet() == 1 ? firstFailure : secondFailure)),
                error -> error instanceof IOException, retryStages::incrementAndGet, queue);
        assertFalse(result.isDone());
        assertEquals(1, attempts.get());
        assertEquals(1, retryStages.get());
        queue.runNext();
        assertSame(secondFailure, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(2, attempts.get());
        assertEquals(1, retryStages.get());
        assertTrue(queue.isEmpty());
    }

    @Test void retryCanSucceedAfterASynchronouslyThrownTransientFailure() {
        var queue = new Tasks();
        var attempts = new AtomicInteger();
        var response = new JsonObject();
        var result = MarketAuthentication.retry(() -> {
            if (attempts.incrementAndGet() == 1) throw new CompletionException(new IOException("Temporary outage"));
            return CompletableFuture.completedFuture(response);
        }, error -> error instanceof IOException, () -> {}, queue);
        queue.runNext();
        assertSame(response, result.join());
        assertEquals(2, attempts.get());
    }

    @Test void cancellationStopsTheCurrentAuthenticationRequest() {
        var queue = new Tasks();
        var source = new CompletableFuture<JsonObject>();
        var result = MarketAuthentication.retry(() -> source, error -> true, () -> {}, queue);
        assertTrue(result.cancel(true));
        assertTrue(source.isCancelled());
        assertTrue(result.isCancelled());
        assertTrue(queue.isEmpty());
    }

    @Test void cancellationDuringTheRetryDelayPreventsAnotherRequest() {
        var queue = new Tasks();
        var attempts = new AtomicInteger();
        var result = MarketAuthentication.retry(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("Temporary outage"));
        }, error -> true, () -> {}, queue);
        result.cancel(true);
        queue.runNext();
        assertEquals(1, attempts.get());
        assertTrue(result.isCancelled());
        assertTrue(queue.isEmpty());
    }

    @Test void cancellationAlsoStopsAnAlreadyRunningSecondAttempt() {
        var queue = new Tasks();
        var attempts = new AtomicInteger();
        var second = new CompletableFuture<JsonObject>();
        var result = MarketAuthentication.retry(() -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IOException("Temporary outage")) : second,
                error -> true, () -> {}, queue);
        queue.runNext();
        result.cancel(true);
        assertTrue(second.isCancelled());
        assertTrue(result.isCancelled());
        assertEquals(2, attempts.get());
    }

    @Test void lateUncancellableResultsCannotChangeCancelledAuthentication() {
        var queue = new Tasks();
        var source = new CompletableFuture<JsonObject>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };
        var result = MarketAuthentication.retry(() -> source, error -> true, () -> {}, queue);
        result.cancel(true);
        source.complete(new JsonObject());
        assertTrue(result.isCancelled());
        assertTrue(queue.isEmpty());
    }

    @Test void retryDelayCannotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> MarketAuthentication.retry(
                () -> CompletableFuture.completedFuture(new JsonObject()), error -> true, () -> {}, Duration.ofMillis(-1)));
    }

    private static final class Tasks implements Executor {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable task) { pending.add(task); }
        boolean isEmpty() { return pending.isEmpty(); }
        void runNext() { pending.remove().run(); }
    }
}

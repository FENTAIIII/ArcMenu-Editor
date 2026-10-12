package com.fentai.arcmenu.editor;

import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Retries one transient sign-in failure without blocking the caller. */
public final class MarketAuthentication {
    private MarketAuthentication() {}

    public static CompletableFuture<JsonObject> retry(
            Supplier<CompletableFuture<JsonObject>> operation,
            Predicate<Throwable> transientFailure, Runnable retryStage) {
        return retry(operation, transientFailure, retryStage, Duration.ofSeconds(1));
    }

    static CompletableFuture<JsonObject> retry(
            Supplier<CompletableFuture<JsonObject>> operation,
            Predicate<Throwable> transientFailure, Runnable retryStage, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("Retry delay must not be negative");
        return retry(operation, transientFailure, retryStage,
                CompletableFuture.delayedExecutor(delay.toNanos(), TimeUnit.NANOSECONDS));
    }

    static CompletableFuture<JsonObject> retry(
            Supplier<CompletableFuture<JsonObject>> operation,
            Predicate<Throwable> transientFailure, Runnable retryStage, Executor retryExecutor) {
        var result = new Retry(Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(transientFailure, "transientFailure"),
                Objects.requireNonNull(retryStage, "retryStage"),
                Objects.requireNonNull(retryExecutor, "retryExecutor"));
        result.start(false);
        return result;
    }

    private static Throwable cause(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static final class Retry extends CompletableFuture<JsonObject> {
        private final Supplier<CompletableFuture<JsonObject>> operation;
        private final Predicate<Throwable> transientFailure;
        private final Runnable retryStage;
        private final Executor retryExecutor;
        private CompletableFuture<JsonObject> current;

        private Retry(Supplier<CompletableFuture<JsonObject>> operation,
                      Predicate<Throwable> transientFailure, Runnable retryStage, Executor retryExecutor) {
            this.operation = operation;
            this.transientFailure = transientFailure;
            this.retryStage = retryStage;
            this.retryExecutor = retryExecutor;
        }

        private synchronized void start(boolean secondAttempt) {
            if (isDone()) return;
            try {
                current = Objects.requireNonNull(operation.get(), "Authentication operation returned no future");
                current.whenComplete((value, error) -> finished(value, error, secondAttempt));
            } catch (Throwable error) {
                finished(null, error, secondAttempt);
            }
        }

        private synchronized void finished(JsonObject value, Throwable error, boolean secondAttempt) {
            if (isDone()) return;
            current = null;
            if (error == null) {
                complete(value);
                return;
            }
            Throwable failure = cause(error);
            try {
                if (secondAttempt || !transientFailure.test(failure)) {
                    completeExceptionally(failure);
                    return;
                }
                if (isDone()) return;
                retryStage.run();
                if (!isDone()) retryExecutor.execute(() -> start(true));
            } catch (Throwable callbackError) {
                completeExceptionally(callbackError);
            }
        }

        @Override public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && current != null) current.cancel(mayInterruptIfRunning);
            return cancelled;
        }
    }
}

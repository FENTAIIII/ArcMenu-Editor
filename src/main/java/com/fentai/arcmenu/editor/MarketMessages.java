package com.fentai.arcmenu.editor;

import com.mojang.authlib.exceptions.InvalidCredentialsException;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.net.http.HttpTimeoutException;

/** Localizes displayed marketplace failures while retaining their original diagnostics. */
final class MarketMessages {
    private static final String PREFIX = "arcmenu_editor.market.error.";
    private static final Set<String> CODES = Set.of(
            "archive_too_large", "author_mismatch", "invalid_archive", "invalid_id", "invalid_json",
            "invalid_manifest", "invalid_uuid", "body_too_large", "invalid_fields", "invalid_length",
            "invalid_path", "invalid_query", "invalid_request", "invalid_token", "length_required",
            "not_found", "archive_unavailable", "auth_rate_limit", "authentication_required",
            "challenge_expired", "identity_mismatch", "invalid_challenge", "invalid_like", "invalid_preview",
            "invalid_rating", "invalid_username", "join_not_verified", "metadata_required", "mojang_unavailable",
            "not_author", "pack_hidden", "pack_not_found", "preview_not_found", "version_exists", "version_not_found");

    private MarketMessages() {}

    static String error(Throwable failure) {
        Throwable cause = cause(failure);
        String key = keyFor(cause);
        return key.isEmpty() ? diagnostic(cause) : EditorI18n.text(PREFIX + key);
    }

    static String diagnostic(Throwable failure) {
        Throwable cause = cause(failure);
        return Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
    }

    static String authentication(String diagnostic) {
        String key = authenticationKey(diagnostic);
        return key.isEmpty() ? diagnostic : EditorI18n.text(PREFIX + key);
    }

    static String keyFor(Throwable failure) {
        Throwable cause = cause(failure);
        if (cause instanceof MarketClient.RequestException request) {
            String dependency = dependencyKey(request.getMessage());
            if (request.code().equals("invalid_manifest") && !dependency.isEmpty()) return dependency;
            return CODES.contains(request.code()) ? request.code() : "";
        }
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SSLHandshakeException || current instanceof SSLPeerUnverifiedException) return "certificate";
            if (current instanceof InvalidCredentialsException) return "session";
        }
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) return "timeout";
        if (cause instanceof CancellationException) return "cancelled";
        if (cause instanceof IOException) return "unavailable";
        return authenticationKey(Objects.toString(cause.getMessage(), ""));
    }

    static String authenticationKey(String diagnostic) {
        if (diagnostic == null || diagnostic.isEmpty()) return "";
        if (diagnostic.startsWith("Marketplace sign-in timed out") || diagnostic.startsWith("Marketplace configuration timed out") || diagnostic.startsWith("Workspace request timed out")) return "timeout";
        if (diagnostic.equals("Marketplace HTTPS endpoint is unavailable")) return "unavailable";
        if (diagnostic.equals("Marketplace authentication has expired")) return "invalid_token";
        if (diagnostic.startsWith("Minecraft session verification failed")) return "session";
        if (diagnostic.equals("Marketplace identity does not match the current Minecraft account or has expired")) return "identity_mismatch";
        if (diagnostic.equals("Marketplace returned an invalid authentication session") || diagnostic.equals("Marketplace returned an invalid JSON response")) return "response";
        if (diagnostic.startsWith("Minecraft connection changed") || diagnostic.equals("Marketplace connection changed during request") || diagnostic.equals("Minecraft account or marketplace connection changed during authentication")) return "connection_changed";
        if (diagnostic.equals("Marketplace sign-in was cancelled")) return "cancelled";
        if (diagnostic.startsWith("Open the project manager before ")) return "open_manager";
        if (diagnostic.startsWith("Unknown local package: ")) return "unknown_package";
        if (diagnostic.equals("Minecraft disconnected")) return "disconnected";
        if (diagnostic.equals("Package preview capture cancelled")) return "capture_cancelled";
        return "";
    }

    private static String dependencyKey(String diagnostic) {
        if (diagnostic == null) return "";
        return switch (diagnostic) {
            case "dependencies must be a list with at most 1024 entries" -> "dependencies_count";
            case "Dependency entries must be strings" -> "dependencies_type";
            case "Dependency entries must not be blank" -> "dependencies_blank";
            case "Dependency entries must contain at most 2048 UTF-16 code units" -> "dependencies_length";
            case "Dependency entries must not contain control characters" -> "dependencies_control";
            case "Dependency entries must be unique" -> "dependencies_duplicate";
            default -> "";
        };
    }

    private static Throwable cause(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof CompletionException || failure instanceof ExecutionException)) failure = failure.getCause();
        return failure;
    }
}

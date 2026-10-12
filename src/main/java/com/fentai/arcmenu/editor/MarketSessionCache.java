package com.fentai.arcmenu.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persists only the marketplace's own account-bound session, never Minecraft credentials. */
public final class MarketSessionCache {
    private static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> FIELDS = Set.of(
        "version", "endpoint", "certificateSha256", "userId", "userName", "token", "expiresAt");

    private MarketSessionCache() {}

    public static Optional<Entry> load(Path file, String profileId, long nowSeconds) {
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) return Optional.empty();
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.keySet().equals(FIELDS) || integer(json, "version") != 1) return Optional.empty();
            var entry = new Entry(string(json, "endpoint"), string(json, "certificateSha256"),
                string(json, "userId"), string(json, "userName"), string(json, "token"), integer(json, "expiresAt"));
            if (!entry.userId().equals(accountId(profileId)) || entry.expiresAt() <= Math.addExact(nowSeconds, 30)) {
                return Optional.empty();
            }
            return Optional.of(entry);
        } catch (IOException | RuntimeException error) {
            return Optional.empty();
        }
    }

    public static void save(Path file, Entry entry) throws IOException {
        var json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("endpoint", entry.endpoint());
        json.addProperty("certificateSha256", entry.certificateSha256());
        json.addProperty("userId", entry.userId());
        json.addProperty("userName", entry.userName());
        json.addProperty("token", entry.token());
        json.addProperty("expiresAt", entry.expiresAt());
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "arcmenu-market-session-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String string(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid marketplace session field");
        }
        return value.getAsString();
    }

    private static long integer(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Invalid marketplace session field");
        }
        return value.getAsBigDecimal().longValueExact();
    }

    private static String accountId(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Invalid marketplace account UUID");
        }
        String compact = value.replace("-", "");
        return UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
            + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20)).toString();
    }

    public static final class Entry {
        private final String endpoint, certificateSha256, userId, userName, token;
        private final long expiresAt;

        public Entry(String endpoint, String certificateSha256, String userId, String userName, String token, long expiresAt) {
            String normalizedEndpoint = endpoint == null ? "" : endpoint.strip().replaceAll("/+$", "");
            URI uri = URI.create(normalizedEndpoint);
            if (normalizedEndpoint.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Marketplace sessions require an HTTPS endpoint");
            }
            String normalizedPin = certificateSha256 == null ? "" : certificateSha256.strip().replace(":", "").toLowerCase(Locale.ROOT);
            if (!normalizedPin.isEmpty() && !normalizedPin.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid marketplace certificate pin");
            }
            if (userName == null || !userName.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("Invalid marketplace account name");
            }
            if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}") || expiresAt <= 0) {
                throw new IllegalArgumentException("Invalid marketplace session");
            }
            this.endpoint = normalizedEndpoint;
            this.certificateSha256 = normalizedPin;
            this.userId = accountId(userId);
            this.userName = userName;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        public String endpoint() { return endpoint; }
        public String certificateSha256() { return certificateSha256; }
        public String userId() { return userId; }
        public String userName() { return userName; }
        public String token() { return token; }
        public long expiresAt() { return expiresAt; }

        @Override public String toString() { return "Marketplace session [credentials redacted]"; }
    }
}

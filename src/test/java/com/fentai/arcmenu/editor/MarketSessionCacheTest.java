package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketSessionCacheTest {
    private static final String ACCOUNT = "20a1028b-5be4-43d1-8e67-8bb9044d6ecf";
    private static final String OTHER_ACCOUNT = "00000000-0000-0000-0000-000000000001";
    private static final String TOKEN = "market_session_fixture_0123456789abcde";
    private static final String PIN = "a".repeat(64);
    private static final long NOW = 1_800_000_000L;
    @TempDir Path directory;

    private MarketSessionCache.Entry entry(long expiry) {
        return new MarketSessionCache.Entry("https://market.example.org/", PIN, ACCOUNT, "DesignLab", TOKEN, expiry);
    }

    @Test void roundTripRestoresTheMatchingAccountWithoutMinecraftCredentials() throws IOException {
        Path file = directory.resolve("config/arcmenu-market-session.json");
        MarketSessionCache.save(file, entry(NOW + 604800));
        var restored = MarketSessionCache.load(file, ACCOUNT.replace("-", "").toUpperCase(), NOW).orElseThrow();
        assertEquals("https://market.example.org", restored.endpoint());
        assertEquals(PIN, restored.certificateSha256());
        assertEquals(ACCOUNT, restored.userId());
        assertEquals("DesignLab", restored.userName());
        assertEquals(TOKEN, restored.token());
        assertEquals(NOW + 604800, restored.expiresAt());
        var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(7, json.size());
        assertFalse(json.has("accessToken"));
        assertFalse(json.has("refreshToken"));
        assertFalse(restored.toString().contains(TOKEN));
    }

    @Test void anotherAccountNeverReceivesTheSavedSession() throws IOException {
        Path file = directory.resolve("session.json");
        MarketSessionCache.save(file, entry(NOW + 3600));
        assertTrue(MarketSessionCache.load(file, OTHER_ACCOUNT, NOW).isEmpty());
        assertTrue(MarketSessionCache.load(file, "invalid-profile", NOW).isEmpty());
        assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isPresent());
    }

    @Test void expiredAndAlmostExpiredSessionsAreNotRestored() throws IOException {
        Path file = directory.resolve("session.json");
        MarketSessionCache.save(file, entry(NOW));
        assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isEmpty());
        MarketSessionCache.save(file, entry(NOW + 30));
        assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isEmpty());
        MarketSessionCache.save(file, entry(NOW + 31));
        assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isPresent());
    }

    @Test void missingUnreadableAndOversizedFilesLeaveAuthenticationAvailableForANewAttempt() throws IOException {
        assertTrue(MarketSessionCache.load(directory.resolve("missing.json"), ACCOUNT, NOW).isEmpty());
        assertTrue(MarketSessionCache.load(directory, ACCOUNT, NOW).isEmpty());
        Path file = directory.resolve("oversized.json");
        Files.writeString(file, " ".repeat(16 * 1024 + 1));
        assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isEmpty());
    }

    @Test void malformedSchemaUntrustedEndpointAndMalformedPinAreIgnored() throws IOException {
        Path file = directory.resolve("session.json");
        Files.writeString(file, "{not-json");
        assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isEmpty());
        MarketSessionCache.save(file, entry(NOW + 3600));
        String valid = Files.readString(file);
        for (String invalid : new String[] {
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"expiresAt\":" + (NOW + 3600), "\"expiresAt\":\"" + (NOW + 3600) + "\""),
            valid.replace("https://market.example.org", "http://market.example.org"),
            valid.replace("https://market.example.org", "https://user@market.example.org"),
            valid.replace(PIN, "incorrect-pin"),
            valid.replace("\"userName\":\"DesignLab\",", ""),
            valid.replace("\"version\":1", "\"accessToken\":\"forbidden\",\"version\":1")
        }) {
            Files.writeString(file, invalid);
            assertTrue(MarketSessionCache.load(file, ACCOUNT, NOW).isEmpty());
        }
    }

    @Test void replacingTheCacheDoesNotLeaveTemporaryFiles() throws IOException {
        Path file = directory.resolve("session.json");
        MarketSessionCache.save(file, entry(NOW + 3600));
        MarketSessionCache.save(file, entry(NOW + 7200));
        assertEquals(NOW + 7200, MarketSessionCache.load(file, ACCOUNT, NOW).orElseThrow().expiresAt());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test void failedWritesCleanUpTheirTemporaryFile() throws IOException {
        Path targetDirectory = directory.resolve("blocked.json");
        Files.createDirectory(targetDirectory);
        Files.writeString(targetDirectory.resolve("keep.txt"), "preserved");
        assertThrows(IOException.class, () -> MarketSessionCache.save(targetDirectory, entry(NOW + 3600)));
        assertEquals("preserved", Files.readString(targetDirectory.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}

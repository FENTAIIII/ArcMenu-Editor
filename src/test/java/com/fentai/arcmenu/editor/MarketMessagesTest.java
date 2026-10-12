package com.fentai.arcmenu.editor;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketMessagesTest {
    @Test void officialMarketplaceErrorsRetainTheirSpecificReason() {
        assertEquals("version_exists", MarketMessages.keyFor(new MarketClient.RequestException(409, "version_exists", "Published package versions are immutable; choose a new version")));
        assertEquals("author_mismatch", MarketMessages.keyFor(new CompletionException(new MarketClient.RequestException(403, "author_mismatch", "Manifest author must match the authenticated Minecraft account"))));
        assertEquals("dependencies_length", MarketMessages.keyFor(new MarketClient.RequestException(400, "invalid_manifest", "Dependency entries must contain at most 2048 UTF-16 code units")));
        assertEquals("dependencies_type", MarketMessages.keyFor(new MarketClient.RequestException(400, "invalid_manifest", "Dependency entries must be strings")));
        assertEquals("invalid_manifest", MarketMessages.keyFor(new MarketClient.RequestException(400, "invalid_manifest", "Manifest package ID does not match the request")));
    }

    @Test void networkAndSessionFailuresHaveDistinctLocalizedMessages() {
        assertEquals("timeout", MarketMessages.keyFor(new CompletionException(new TimeoutException("HTTP request expired"))));
        assertEquals("certificate", MarketMessages.keyFor(new SSLHandshakeException("PKIX path building failed")));
        assertEquals("unavailable", MarketMessages.keyFor(new IOException("Connection refused")));
        assertEquals("session", MarketMessages.authenticationKey("Minecraft session verification failed: invalid credentials"));
        assertEquals("connection_changed", MarketMessages.authenticationKey("Minecraft account or marketplace connection changed during authentication"));
        assertEquals("capture_cancelled", MarketMessages.authenticationKey("Package preview capture cancelled"));
    }

    @Test void UnknownAndAlreadyLocalizedDiagnosticsStayReadableAndUnmodified() {
        String diagnostic = "Specific addon diagnostic: custom-resource-42";
        assertEquals(diagnostic, MarketMessages.error(new MarketClient.RequestException(400, "future_addon_code", diagnostic)));
        assertEquals(diagnostic, MarketMessages.error(new IllegalArgumentException(diagnostic)));
        assertEquals(diagnostic, MarketMessages.authentication(diagnostic));
        String localized = "Configuration package description supplied by the current server language";
        assertEquals(localized, MarketMessages.error(new IllegalArgumentException(localized)));
    }
}

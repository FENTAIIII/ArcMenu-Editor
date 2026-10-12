package com.fentai.arcmenu.editor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EffectSoundResolverTest {
    private static EffectSoundResolver resolver(Map<String, List<String>> definitions, String... files) {
        Set<String> available = Set.of(files);
        return new EffectSoundResolver(new EffectSoundResolver.Resources() {
            @Override public List<String> soundDefinitions(String namespace) { return definitions.getOrDefault(namespace, List.of()); }
            @Override public boolean exists(String resource) { return available.contains(resource); }
        });
    }
    @Test void effectAssetsAndExplicitNamespacedFilesUseLoadedPackPaths() throws Exception {
        var resolver = resolver(Map.of(), "arcmenu:sounds/effect/music/beat.ogg", "ce:sounds/music/intro.ogg");
        assertEquals("arcmenu:sounds/effect/music/beat.ogg", resolver.resolve("sound", "/music/beat.ogg").resourceId());
        assertEquals("ce:sounds/music/intro.ogg", resolver.resolve("sound", "ce:music/intro.ogg").resourceId());
        assertThrows(IOException.class, () -> resolver.resolve("sound", "../secret.ogg"));
        assertThrows(IOException.class, () -> resolver.resolve("sound", "ce:../secret.ogg"));
        assertThrows(IOException.class, () -> resolver.resolve("nbs", "music.nbs"));
    }
    @Test void nativeEnumNamesRetainUnderscoresInsideResourceNames() throws Exception {
        var resolver = resolver(Map.of("minecraft", List.of("{\"block.note_block.pling\":{\"sounds\":[\"note/pling\"]}}")), "minecraft:sounds/note/pling.ogg");
        assertEquals("minecraft:sounds/note/pling.ogg", resolver.resolve("sound", "BLOCK_NOTE_BLOCK_PLING").resourceId());
        assertEquals("minecraft:sounds/note/pling.ogg", resolver.resolve("sound", "block.note_block.pling").resourceId());
    }
    @Test void packReplacementAndEventAliasesCarryTheirGainAndPitch() throws Exception {
        var resolver = resolver(Map.of("ce", List.of(
            "{\"intro\":{\"sounds\":[\"old\"]},\"alias\":{\"sounds\":[{\"name\":\"intro\",\"type\":\"event\",\"volume\":0.5,\"pitch\":2}]} }",
            "{\"intro\":{\"replace\":true,\"sounds\":[{\"name\":\"new\",\"volume\":0.4,\"pitch\":0.5}]}}")),
            "ce:sounds/old.ogg", "ce:sounds/new.ogg");
        var resolved = resolver.resolve("sound", "ce:alias");
        assertEquals("ce:sounds/new.ogg", resolved.resourceId());
        assertEquals(.2, resolved.baseVolume(), .000001);
        assertEquals(1, resolved.basePitch(), .000001);
    }
    @Test void variantsAreStableAndSkipMissingFilesInsteadOfRandomizingOnSeek() throws Exception {
        var resolver = resolver(Map.of("ce", List.of("{\"beat\":{\"sounds\":[{\"name\":\"missing\",\"weight\":10},{\"name\":\"selected\",\"weight\":3},\"other\"]}}")),
            "ce:sounds/selected.ogg", "ce:sounds/other.ogg");
        for (int i = 0; i < 10; i++) assertEquals("ce:sounds/selected.ogg", resolver.resolve("sound", "ce:beat").resourceId());
    }
    @Test void circularAndMissingEventsFailWithUsefulErrors() {
        var resolver = resolver(Map.of("ce", List.of("{\"a\":{\"sounds\":[{\"name\":\"b\",\"type\":\"event\"}]},\"b\":{\"sounds\":[{\"name\":\"a\",\"type\":\"event\"}]}}")));
        assertTrue(assertThrows(IOException.class, () -> resolver.resolve("sound", "ce:a")).getMessage().contains("ce:a"));
        assertTrue(assertThrows(IOException.class, () -> resolver.resolve("sound", "absent.ogg")).getMessage().contains("loaded resource pack"));
    }
    @Test void appendedPackVariantsAndRangesResolveDeterministically() throws Exception {
        var resolver = resolver(Map.of("ce", List.of("{\"beat\":{\"sounds\":[\"old\"]}}",
            "{\"beat\":{\"sounds\":[{\"name\":\"new\",\"weight\":2,\"volume\":{\"min\":0.2,\"max\":0.8},\"pitch\":{\"min_inclusive\":1,\"max_inclusive\":2}}]}}")),
            "ce:sounds/old.ogg", "ce:sounds/new.ogg");
        var resolved = resolver.resolve("sound", "ce:beat");
        assertEquals("ce:sounds/new.ogg", resolved.resourceId());
        assertEquals(.5, resolved.baseVolume(), .000001);
        assertEquals(1.5, resolved.basePitch(), .000001);
    }
}

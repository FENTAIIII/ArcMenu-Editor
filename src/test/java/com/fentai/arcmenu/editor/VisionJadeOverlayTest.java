package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

final class VisionJadeOverlayTest {
    @Test void hidesJadeOnlyWhileVisionIsActiveAndWorksWithoutJadeInstalled() throws Exception {
        var mixin = Class.forName("com.fentai.arcmenu.editor.mixin.VisionJadeOverlayMixin");
        var handler = mixin.getDeclaredMethod("arcmenu$hideJadeDuringVision", CallbackInfo.class);
        handler.setAccessible(true);
        var shown = mixin.getDeclaredField("shown");
        shown.setAccessible(true);
        var state = VisionCursorClient.class.getDeclaredField("state");
        state.setAccessible(true);
        var previous = state.get(null);
        try {
            for (var session : new VisionProtocol.State[] {null,
                    new VisionProtocol.State(1, true, false, 1, 1, 1, 1),
                    new VisionProtocol.State(1, false, false, 1, 1, 1, 1), null}) {
                state.set(null, session);
                shown.setBoolean(null, true);
                var callback = new CallbackInfo("renderOverlay478757", true);
                handler.invoke(null, callback);
                boolean active = session != null && session.active();
                assertEquals(active, callback.isCancelled());
                assertEquals(!active, shown.getBoolean(null));
            }
        } finally {
            state.set(null, previous);
        }
    }

    @Test void runtimeJarRegistersTheOptionalJadeMixinWithoutBundlingJade() throws Exception {
        try (var jar = new JarFile(System.getProperty("arcmenu.runtimeJar"))) {
            try (var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("fabric.mod.json")),
                    StandardCharsets.UTF_8)) {
                var descriptor = JsonParser.parseReader(reader).getAsJsonObject();
                assertFalse(descriptor.getAsJsonObject("depends").has("jade"));
                assertTrue(descriptor.getAsJsonArray("mixins").asList().stream()
                        .anyMatch(entry -> entry.getAsString().equals("arcmenu-editor.jade.mixins.json")));
            }
            assertNotNull(jar.getJarEntry("arcmenu-editor.jade.mixins.json"));
            assertNotNull(jar.getJarEntry("com/fentai/arcmenu/editor/mixin/VisionJadeOverlayMixin.class"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("snownee/jade/")));
        }
    }
}

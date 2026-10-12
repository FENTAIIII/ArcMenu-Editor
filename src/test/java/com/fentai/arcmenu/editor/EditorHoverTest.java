package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.mcp.McpTools;
import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class EditorHoverTest {
    private static final String ENTER = """
            priority: 10
            actions:
              - 'animation: shop-hover'
              - 'message: Keep \\n literal and : punctuation'
            deny:
              - 'animation: shop-idle'
            """;
    private static final String LEAVE = """
            - actions:
                - 'animation: shop-idle'
                - 'delay: 2'
            """;

    @Test void multilineHoverReactionMapsAndListsSurviveTheWireAndAuthoritativeSceneUpdates() {
        var region = region(ENTER, LEAVE);
        var front = new EditorProtocol.NodeSnapshot("card", "", EditorProtocol.KIND_RECTANGLE,
                0, 0, 64, 24, 0, true, false);
        var snapshot = new EditorProtocol.SnapshotPacket(7, "shop", 320, 180, true, false, "26.3",
                List.of(front), List.of(region), List.of(), List.of(new EditorProtocol.TemplateSnapshot("shop-card", "root", 2)));
        var decoded = (EditorProtocol.SnapshotPacket) EditorProtocol.decode(EditorProtocol.encode(snapshot));
        var state = new EditorState();
        state.update(decoded);
        state.activeTab(EditorProtocol.TAB_BACKEND);
        state.selectOnly("shop-button");

        for (var property : region.properties()) {
            var edit = new EditorProtocol.SetPropertyPacket(7, EditorProtocol.TAB_BACKEND, region.id(), property.key(), property.value());
            assertEquals(edit, EditorProtocol.decode(EditorProtocol.encode(edit)));
            assertEquals(property, state.find(region.id()).properties().stream()
                    .filter(value -> value.key().equals(property.key())).findFirst().orElseThrow());
        }

        assertTrue(state.update(new EffectProtocol.SceneFrame(7, "shop", List.of(
                new EffectProtocol.NodeFrame("card", 5, 10, 64, 24, 0, true, List.of())))));
        assertEquals(List.of(region), state.snapshot().backend());
        assertEquals("shop-button", state.selectedId());
        assertEquals(snapshot.templates(), state.snapshot().templates());
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_REGION, "on-hover"));
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_REGION, "on-leave"));
    }

    @Test void emptyHoverEventsAreOrdinaryPropertiesAndAreDiscoverableThroughMcp() {
        for (String key : List.of("on-hover", "on-leave")) {
            var args = new JsonObject();
            args.addProperty("action", "property");
            args.addProperty("tab", "backend");
            args.addProperty("target", "shop-button");
            args.addProperty("property", key);
            args.addProperty("value", "[]");
            assertDoesNotThrow(() -> McpTools.validate("arcmenu_edit", args));
            var empty = new EditorProtocol.SetPropertyPacket(8, EditorProtocol.TAB_BACKEND, "shop-button", key, "[]");
            assertEquals(empty, EditorProtocol.decode(EditorProtocol.encode(empty)));
        }
        var editTool = McpTools.list().asList().stream().map(value -> value.getAsJsonObject())
                .filter(tool -> tool.get("name").getAsString().equals("arcmenu_edit")).findFirst().orElseThrow();
        String description = editTool.get("description").getAsString();
        assertTrue(description.contains("on-hover"));
        assertTrue(description.contains("on-leave"));
        assertTrue(description.contains("animation: track-id"));
        assertTrue(description.contains("[] clears an event"));
    }

    private static EditorProtocol.NodeSnapshot region(String enter, String leave) {
        return new EditorProtocol.NodeSnapshot("shop-button", "", EditorProtocol.KIND_REGION,
                0, 0, 64, 24, 0, true, false, List.of(
                new EditorProtocol.PropertySnapshot("on-hover", EditorProtocol.PROPERTY_MULTILINE, enter),
                new EditorProtocol.PropertySnapshot("on-leave", EditorProtocol.PROPERTY_MULTILINE, leave)));
    }
}

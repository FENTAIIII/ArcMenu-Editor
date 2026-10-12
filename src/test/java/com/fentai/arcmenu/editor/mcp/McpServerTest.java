package com.fentai.arcmenu.editor.mcp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import com.fentai.arcmenu.editor.EffectCommands;
import com.fentai.arcmenu.protocol.EditorProtocol;
import java.util.List;

final class McpServerTest {
    @Test void gifCreationIsDiscoverableAndUsesAnAppendedWireKind() {
        McpTools.validate("arcmenu_edit",JsonParser.parseString("{\"action\":\"create\",\"kind\":\"gif\",\"initialSource\":\"/arcpack-demo/banner.gif\"}").getAsJsonObject());
        McpTools.validate("arcmenu_edit",JsonParser.parseString("{\"action\":\"property\",\"target\":\"banner\",\"property\":\"speed\",\"value\":\"0.5\"}").getAsJsonObject());
        assertEquals(EditorProtocol.KIND_GIF,McpTools.nodeKind("gif"));
        assertEquals(EditorProtocol.KIND_IMAGE,McpTools.nodeKind("image"));
        assertEquals(EditorProtocol.KIND_LINE,McpTools.nodeKind("line"));
        assertThrows(IllegalArgumentException.class,()->McpTools.nodeKind("video"));
        assertTrue(EffectCommands.list().stream().anyMatch(command->command.id().equals("new-gif")));
    }
    private static final String TOKEN = "a".repeat(64);
    private HttpResponse<String> post(McpServer server, String body, String token, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp")).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", McpServer.PROTOCOL);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.header("Origin", origin);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void streamableHttpNegotiatesAndReturnsDiscoverableToolsAndRealImageContent() throws Exception {
        try (var server = new McpServer(0, TOKEN, (name, args) -> {
            JsonObject result = McpTools.result(JsonParser.parseString("{\"frame\":10}").getAsJsonObject());
            if (name.equals("arcmenu_screenshot")) result.getAsJsonArray("content").add(JsonParser.parseString("{\"type\":\"image\",\"data\":\"iVBORw0KGgo=\",\"mimeType\":\"image/png\"}"));
            return result;
        })) {
            var init = post(server, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}", TOKEN, null);
            assertEquals(200, init.statusCode()); JsonObject result = JsonParser.parseString(init.body()).getAsJsonObject().getAsJsonObject("result"); assertEquals(McpServer.PROTOCOL, result.get("protocolVersion").getAsString()); assertTrue(result.getAsJsonObject("capabilities").has("tools"));
            assertEquals(202, post(server, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN, null).statusCode());
            var list = post(server, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", TOKEN, null); assertEquals(18, JsonParser.parseString(list.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools").size());
            var shot = post(server, "{\"jsonrpc\":\"2.0\",\"id\":\"shot\",\"method\":\"tools/call\",\"params\":{\"name\":\"arcmenu_screenshot\",\"arguments\":{\"scope\":\"viewport\",\"maxWidth\":1280}}}", TOKEN, null);
            var image = JsonParser.parseString(shot.body()).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("content").get(1).getAsJsonObject(); assertEquals("image", image.get("type").getAsString()); assertEquals("image/png", image.get("mimeType").getAsString());
            var resource = post(server, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\"arcmenu://editor/state\"}}", TOKEN, null); assertTrue(resource.body().contains("application/json"));
            var get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp")).header("Authorization", "Bearer " + TOKEN).GET().build(); assertEquals(405, HttpClient.newHttpClient().send(get, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void authenticationOriginAndSchemasRejectBeforeCallingGame() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = new McpServer(0, TOKEN, (name, args) -> { calls.incrementAndGet(); return McpTools.result(new JsonObject()); })) {
            String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            assertEquals(401, post(server, ping, null, null).statusCode()); assertEquals(401, post(server, ping, "bad", null).statusCode()); assertEquals(403, post(server, ping, TOKEN, "https://example.com").statusCode());
            var invalid = post(server, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"effect_keyframe\",\"arguments\":{\"target\":\"title\",\"property\":\"offset.x\",\"frame\":1.5}}}", TOKEN, null);
            assertTrue(invalid.body().contains("isError")); assertEquals(0, calls.get());
            assertEquals(400, post(server, "[1,2]", TOKEN, null).statusCode()); assertEquals(400, post(server, "{invalid}", TOKEN, null).statusCode());
            assertTrue(post(server, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown\"}", TOKEN, null).body().contains("-32601"));
            assertTrue(post(server, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\"}}", TOKEN, null).body().contains("isError"));
            assertFalse(McpServer.validOrigin("null")); assertTrue(McpServer.validOrigin("http://localhost:3456"));
        }
    }
    @Test void allPublishedSchemasAreUsableAndRejectExtraFieldsOrWrongCurves() {
        assertEquals(18, McpTools.list().size());
        McpTools.validate("effect_keyframe", JsonParser.parseString("{\"target\":\"title\",\"property\":\"offset.x\",\"frame\":10,\"value\":\"40\",\"curve\":[0.42,0,0.58,1]}").getAsJsonObject());
        assertThrows(IllegalArgumentException.class, () -> McpTools.validate("effect_keyframe", JsonParser.parseString("{\"target\":\"title\",\"property\":\"offset.x\",\"frame\":10,\"curve\":[0,1]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> McpTools.validate("arcmenu_state", JsonParser.parseString("{\"extra\":true}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> McpTools.validate("effect_capture_frames", JsonParser.parseString("{\"frames\":[-1]}").getAsJsonObject()));
        McpTools.validate("effect_keyframes",JsonParser.parseString("{\"action\":\"move\",\"keys\":[{\"target\":\"title\",\"property\":\"offset.y\",\"frame\":0,\"newFrame\":10}]}").getAsJsonObject());
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("effect_keyframes",JsonParser.parseString("{\"action\":\"move\",\"keys\":[{\"target\":\"title\",\"frame\":0}]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("effect_keyframes",JsonParser.parseString("{\"action\":\"move\",\"keys\":[{\"target\":\"title\",\"property\":\"offset.y\",\"frame\":0.5}]}").getAsJsonObject()));
        McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"select\",\"ids\":[\"menu:main\",\"effect:intro\"]}").getAsJsonObject());
        McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"capture\",\"parameters\":\"{\\\"filename\\\":\\\"example.arcpack\\\",\\\"projects\\\":[{\\\"type\\\":\\\"effect\\\",\\\"id\\\":\\\"intro\\\"}]}\"}").getAsJsonObject());
        McpTools.validate("workspace_market",JsonParser.parseString("{\"action\":\"login\"}").getAsJsonObject());
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_market",JsonParser.parseString("{\"action\":\"login\",\"accessToken\":\"never-transmit\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_market",JsonParser.parseString("{\"action\":\"rate\",\"id\":\"pack\",\"stars\":6}").getAsJsonObject()));
    }
    @Test void workspaceInputSchemaAcceptsActualFormValuesAndPreservesExistingCommands() {
        McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"input\",\"field\":\"field_description\",\"value\":\"First paragraph.\\nSecond paragraph.\"}").getAsJsonObject());
        McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"input\",\"field\":\"search\",\"value\":\"\"}").getAsJsonObject());
        McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"click\",\"x\":123.5,\"y\":82,\"button\":0}").getAsJsonObject());
        McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"keypress\",\"key\":\"escape\"}").getAsJsonObject());
        for(String command:List.of("modal_confirm","modal_cancel","nav_projects","nav_local_packages","nav_installed","nav_market","close","escape","new_menu","configure","export","publish")) {
            var args=new JsonObject();args.addProperty("action","command");args.addProperty("command",command);McpTools.validate("workspace_projects",args);
        }
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"input\",\"field\":\"title\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"input\",\"value\":\"Title\"}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"input\",\"field\":\"width\",\"value\":800}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"click\",\"x\":50}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"click\",\"x\":-1,\"y\":82}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->McpTools.validate("workspace_projects",JsonParser.parseString("{\"action\":\"keypress\",\"key\":\"unknown\"}").getAsJsonObject()));
    }
}

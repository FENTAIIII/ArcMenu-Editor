package com.fentai.arcmenu.editor.mcp;

import com.google.gson.*;
import java.util.*;

/** Stable, discoverable composition/layer/keyframe tools over the existing server-authoritative editor. */
public final class McpTools {
    static byte nodeKind(String name) {
        int kind=List.of("group","rectangle","frame","text","item","block","region","image","line","gif").indexOf(name);
        if(kind<0)throw new IllegalArgumentException("Unknown element kind: "+name);
        return (byte)kind;
    }
    private static final Gson JSON = new Gson();
    private static final LinkedHashMap<String, JsonObject> TOOLS = new LinkedHashMap<>();
    static {
        tool("arcmenu_state", "Read the open menu, composition, layers, properties, tracks, cues, assets, selection and revisions.", true, "{}", "");
        tool("workspace_projects", "Manage server projects, saved local ArcPack archives and installed packages, or open the native manager. select uses type:id keys; select_pack uses parameters.filename. input sets the actual visible modal field or search/sort field using field and value; field may include field_. click sends a real pointer press/release at GUI-scaled x/y, optionally button 0/1/2; keypress sends a named key through the native screen handler. Read state.projectManager.modal for form mode, fields, busy and error, and controls for actual pointer bounds. command invokes real controls including modal_confirm, modal_cancel, nav_projects, nav_local_packages, nav_installed, nav_market, close and escape. Other actions take JSON parameters and wait for acknowledgement. Delete requires confirm:true; opening dirty projects requires confirmDiscard:true. Export never silently includes CE resources. capture stages real 720p bundled-scene previews with filename and projects; prepare takes filename and stores all previews in that archive without publishing.", false,
                "{\"action\":{\"enum\":[\"state\",\"show\",\"select\",\"select_pack\",\"command\",\"input\",\"click\",\"keypress\",\"list\",\"open\",\"create\",\"configure\",\"delete\",\"export\",\"capture\",\"prepare\",\"pack-configure\",\"pack-preview\",\"pack-delete\",\"import\",\"uninstall\"]},\"parameters\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"},\"field\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"x\":{\"type\":\"number\",\"minimum\":0},\"y\":{\"type\":\"number\",\"minimum\":0},\"button\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2},\"key\":{\"enum\":[\"escape\",\"enter\",\"tab\",\"backspace\",\"delete\",\"left\",\"right\",\"up\",\"down\",\"home\",\"end\"]},\"ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":256}}", "action");
        tool("workspace_market", "Use the native marketplace with automatic genuine Minecraft session verification. login retries through Mojang without disclosing credentials. list/detail return cover, author, likes, downloads and ratings; open displays a loaded resource in the native storefront; like updates liked independently of rate stars; edit updates the verified author's title/description; install downloads a selected version. show opens the native marketplace. No access token is returned.", false,
                "{\"action\":{\"enum\":[\"state\",\"show\",\"open\",\"login\",\"logout\",\"list\",\"detail\",\"rate\",\"like\",\"edit\",\"install\"]},\"id\":{\"type\":\"string\"},\"version\":{\"type\":\"string\"},\"q\":{\"type\":\"string\"},\"page\":{\"type\":\"integer\",\"minimum\":1},\"sort\":{\"enum\":[\"popular\",\"newest\",\"updated\",\"rating\",\"likes\",\"downloads\"]},\"stars\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5},\"liked\":{\"type\":\"boolean\"},\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}}", "action");
        tool("arcmenu_open", "Open a menu or create/open an Effect composition. Effect may clone an existing menu via fromMenu. Requires server admin permission.", false,
                "{\"mode\":{\"enum\":[\"menu\",\"effect\"]},\"id\":{\"type\":\"string\"},\"create\":{\"type\":\"boolean\"},\"fromMenu\":{\"type\":\"string\"}}", "mode,id");
        tool("arcmenu_select", "Select frontend or backend elements in the visual editor.", true,
                "{\"ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":256},\"tab\":{\"enum\":[\"frontend\",\"backend\"]}}", "ids");
        tool("arcmenu_edit", "Edit the scene. Read state first for exact property keys and choices. Set offset/rotation/scale through properties. Backend regions support on-hover and on-leave as YAML reaction lists or maps, with the existing priority/condition/deny/actions grammar. Use animation: track-id actions to play existing menu animations once on pointer entry or exit without clicking; [] clears an event. Create accepts an optional YAML initialSource. GIF layers use kind gif, the original .gif source in images, and positive speed in frames per Minecraft tick; speed 2 skips alternate frames and 0.5 holds each frame for two ticks. Operations wait for server acknowledgement; save explicitly persists. nodeIds is used for duplicate/group/delete-many/reorder/reparent.", false,
                "{\"action\":{\"enum\":[\"create\",\"property\",\"delete\",\"delete-many\",\"duplicate\",\"group\",\"reorder\",\"reparent\",\"undo\",\"redo\",\"save\",\"apply\",\"template-save\",\"template-instantiate\",\"template-delete\"]},\"tab\":{\"enum\":[\"frontend\",\"backend\"]},\"target\":{\"type\":\"string\"},\"parent\":{\"type\":\"string\"},\"before\":{\"type\":\"string\"},\"property\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"template\":{\"type\":\"string\"},\"initialSource\":{\"type\":\"string\"},\"kind\":{\"enum\":[\"group\",\"rectangle\",\"frame\",\"text\",\"item\",\"block\",\"region\",\"image\",\"line\",\"gif\"]},\"nodeIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":256},\"preserveParents\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "action");
        tool("effect_keyframe", "Set, replace, move or delete one keyframe at an absolute frame. Curve is [x1,y1,x2,y2]; interpolation belongs to the outgoing segment. Non-numeric values use hold interpolation. newFrame is required for replace/move. Read the composition and available node properties first.", false,
                "{\"action\":{\"enum\":[\"set\",\"replace\",\"move\",\"delete\"]},\"target\":{\"type\":\"string\"},\"property\":{\"type\":\"string\"},\"frame\":{\"type\":\"integer\",\"minimum\":0},\"newFrame\":{\"type\":\"integer\",\"minimum\":0},\"value\":{\"type\":\"string\"},\"interpolation\":{\"enum\":[\"linear\",\"hold\",\"bezier\",\"auto-bezier\",\"ease-in\",\"ease-out\",\"ease-in-out\"]},\"curve\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":4,\"maxItems\":4},\"expectedRevision\":{\"type\":\"integer\"}}", "target,property,frame");
        tool("effect_layer", "Set a layer's [start,end) span, visibility or solo state.", false,
                "{\"target\":{\"type\":\"string\"},\"start\":{\"type\":\"integer\",\"minimum\":0},\"end\":{\"type\":\"integer\",\"minimum\":1},\"enabled\":{\"type\":\"boolean\"},\"solo\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "target,start,end");
        tool("effect_track", "Enable, mute or delete a property track.", false,
                "{\"target\":{\"type\":\"string\"},\"property\":{\"type\":\"string\"},\"delete\":{\"type\":\"boolean\"},\"enabled\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "target,property");
        tool("effect_cue", "Set or delete a timed cue. kind=command executes as player; console as server; action uses ArcMenu action syntax; sound accepts Minecraft names, CE keys or relative .ogg; nbs accepts a relative .nbs asset. Editor sound preview decodes loaded resource-pack audio and seeks to the playhead; business cues never execute in preview. NBS playback remains server-side. end=-1 means natural duration.", false,
                "{\"id\":{\"type\":\"string\"},\"delete\":{\"type\":\"boolean\"},\"frame\":{\"type\":\"integer\",\"minimum\":0},\"end\":{\"type\":\"integer\",\"minimum\":-1},\"kind\":{\"enum\":[\"command\",\"console\",\"action\",\"sound\",\"nbs\"]},\"source\":{\"type\":\"string\"},\"volume\":{\"type\":\"number\",\"minimum\":0,\"maximum\":4},\"pitch\":{\"type\":\"number\",\"minimum\":0.01,\"maximum\":4},\"enabled\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "id");
        tool("effect_marker", "Set or delete a composition marker at an absolute frame.", false,
                "{\"frame\":{\"type\":\"integer\",\"minimum\":0},\"label\":{\"type\":\"string\"},\"delete\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "frame");
        tool("effect_composition", "Set fps, duration, work area and loop for visual preview. Existing keys/cues must fit the new duration.", false,
                "{\"fps\":{\"type\":\"number\",\"minimum\":1,\"maximum\":120},\"duration\":{\"type\":\"integer\",\"minimum\":1},\"workStart\":{\"type\":\"integer\",\"minimum\":0},\"workEnd\":{\"type\":\"integer\",\"minimum\":1},\"loop\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "fps,duration,workStart,workEnd");
        tool("effect_control", "Seek or control visual and decoded audio preview, enable/disable local audio-preview with enabled, toggle auto-key, undo/redo timeline, save both scene and timeline, or switch the client workspace between timeline/value graph/speed graph. Seeking auditions a short sound segment. Preview suppresses business cues.", false,
                "{\"action\":{\"enum\":[\"seek\",\"play\",\"stop\",\"audio-preview\",\"auto-key\",\"undo\",\"redo\",\"save\",\"value-graph\",\"speed-graph\",\"timeline\"]},\"frame\":{\"type\":\"integer\",\"minimum\":0},\"enabled\":{\"type\":\"boolean\"},\"expectedRevision\":{\"type\":\"integer\"}}", "action");
        tool("effect_keyframes", "Atomically set, move, copy, delete or interpolate up to 512 keyframes. A move/copy uses newFrame and preserves values unless a new value is explicitly supplied. Collisions reject the whole operation. One operation creates one undo entry.", false,
                "{\"action\":{\"enum\":[\"set\",\"move\",\"copy\",\"delete\",\"interpolate\"]},\"keys\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":512,\"items\":{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"},\"property\":{\"type\":\"string\"},\"frame\":{\"type\":\"integer\",\"minimum\":0},\"newFrame\":{\"type\":\"integer\",\"minimum\":0},\"value\":{\"type\":\"string\"},\"interpolation\":{\"type\":\"string\"},\"x1\":{\"type\":\"number\"},\"y1\":{\"type\":\"number\"},\"x2\":{\"type\":\"number\"},\"y2\":{\"type\":\"number\"}},\"required\":[\"target\",\"property\",\"frame\"],\"additionalProperties\":false}},\"interpolation\":{\"enum\":[\"linear\",\"hold\",\"bezier\",\"auto-bezier\",\"ease-in\",\"ease-out\",\"ease-in-out\"]},\"curve\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":4,\"maxItems\":4},\"expectedRevision\":{\"type\":\"integer\"}}", "action,keys");
        tool("effect_layer_operation", "Split selected layers into two independent tracks at frame, move a layer and its animation by delta frames, or copy/cut/paste full layers with their animation. Ungroup preserves safe group transforms and rejects groups whose animation cannot be preserved. Split and paste return selected new layer IDs.", false,
                "{\"action\":{\"enum\":[\"split\",\"move\",\"copy\",\"cut\",\"paste\",\"ungroup\"]},\"target\":{\"type\":\"string\"},\"targets\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,\"maxItems\":256},\"frame\":{\"type\":\"integer\",\"minimum\":0},\"delta\":{\"type\":\"integer\"},\"expectedRevision\":{\"type\":\"integer\"}}", "action");
        tool("effect_workspace", "Read workspace panel geometry and all executable commands, invoke a command or keyboard shortcut, or interact with the actual Effect workspace using GUI-scaled pointer coordinates. Commands and input use the same handlers as the visible editor. Context menus expose layer/project/composition actions. Returns state after server acknowledgement when an edit is sent.", false,
                "{\"operation\":{\"enum\":[\"state\",\"command\",\"shortcut\",\"click\",\"drag\",\"release\",\"scroll\",\"context-menu\"]},\"command\":{\"type\":\"string\"},\"shortcut\":{\"type\":\"string\"},\"x\":{\"type\":\"number\",\"minimum\":0},\"y\":{\"type\":\"number\",\"minimum\":0},\"button\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2},\"ctrl\":{\"type\":\"boolean\"},\"shift\":{\"type\":\"boolean\"},\"alt\":{\"type\":\"boolean\"},\"dx\":{\"type\":\"number\"},\"dy\":{\"type\":\"number\"}}", "operation");
        tool("arcmenu_screenshot", "Return a fresh PNG of actual Minecraft rendering as MCP image content. scope=viewport crops the game aperture; window includes editor UI. Optional frame seeks an Effect preview first. waitTicks allows server/display interpolation to settle. No cached/mock image is used.", true,
                "{\"scope\":{\"enum\":[\"viewport\",\"window\"]},\"maxWidth\":{\"type\":\"integer\",\"minimum\":64,\"maximum\":2048},\"frame\":{\"type\":\"integer\",\"minimum\":0},\"waitTicks\":{\"type\":\"integer\",\"minimum\":2,\"maximum\":40}}", "");
        tool("effect_capture_frames", "Seek and capture up to 16 requested frames as fresh PNG images with frame/timestamp metadata. Useful for inspecting timing/easing. Leaves preview at the last frame and does not execute cues.", false,
                "{\"frames\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"minimum\":0},\"minItems\":1,\"maxItems\":16},\"scope\":{\"enum\":[\"viewport\",\"window\"]},\"maxWidth\":{\"type\":\"integer\",\"minimum\":64,\"maximum\":2048},\"waitTicks\":{\"type\":\"integer\",\"minimum\":2,\"maximum\":40}}", "frames");
    }
    private McpTools() {}
    private static void tool(String name, String description, boolean read, String properties, String required) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties)); schema.addProperty("additionalProperties", false);
        JsonArray req = new JsonArray(); if (!required.isBlank()) for (String key : required.split(",")) req.add(key); schema.add("required", req);
        JsonObject definition = new JsonObject(); definition.addProperty("name", name); definition.addProperty("description", description); definition.add("inputSchema", schema);
        JsonObject annotations = new JsonObject(); annotations.addProperty("readOnlyHint", read); annotations.addProperty("destructiveHint", !read); annotations.addProperty("openWorldHint", false); definition.add("annotations", annotations);
        TOOLS.put(name, definition);
    }
    public static JsonArray list() { JsonArray list = new JsonArray(); TOOLS.values().forEach(value -> list.add(value.deepCopy())); return list; }
    public static void validate(String name, JsonObject args) {
        var tool = TOOLS.get(name); if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        JsonObject schema = tool.getAsJsonObject("inputSchema"), properties = schema.getAsJsonObject("properties");
        for (var key : schema.getAsJsonArray("required")) if (!args.has(key.getAsString())) throw new IllegalArgumentException("Missing argument: " + key.getAsString());
        for (var entry : args.entrySet()) { if (!properties.has(entry.getKey())) throw new IllegalArgumentException("Unknown argument: " + entry.getKey()); validateValue(entry.getKey(), entry.getValue(), properties.getAsJsonObject(entry.getKey())); }
        if(name.equals("workspace_projects")){var fields=switch(args.get("action").getAsString()){case "input"->List.of("field","value");case "click"->List.of("x","y");case "keypress"->List.of("key");default->List.<String>of();};for(String field:fields)if(!args.has(field))throw new IllegalArgumentException("Missing argument: "+field);}
    }
    private static void validateValue(String key, JsonElement value, JsonObject schema) {
        if (value.isJsonNull()) throw new IllegalArgumentException("Null argument: " + key);
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) throw new IllegalArgumentException("Invalid choice: " + key);
        String type = schema.has("type") ? schema.get("type").getAsString() : "string";
        boolean valid = switch (type) {
            case "array" -> value.isJsonArray();
            case "object" -> value.isJsonObject();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number", "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble()) && (!type.equals("integer") || value.getAsDouble() == Math.rint(value.getAsDouble()) && Math.abs(value.getAsDouble()) <= 9_007_199_254_740_991d);
            default -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().length() <= 16_384;
        };
        if (!valid) throw new IllegalArgumentException("Invalid type: " + key);
        if(type.equals("object")) {
            var object=value.getAsJsonObject();var properties=schema.getAsJsonObject("properties");
            if(schema.has("required"))for(var entry:schema.getAsJsonArray("required"))if(!object.has(entry.getAsString()))throw new IllegalArgumentException("Missing field: "+entry.getAsString());
            for(var entry:object.entrySet()){if(!properties.has(entry.getKey()))throw new IllegalArgumentException("Unknown field: "+entry.getKey());validateValue(entry.getKey(),entry.getValue(),properties.getAsJsonObject(entry.getKey()));}
        } else if (type.equals("array")) {
            int n = value.getAsJsonArray().size(); if (schema.has("minItems") && n < schema.get("minItems").getAsInt() || schema.has("maxItems") && n > schema.get("maxItems").getAsInt()) throw new IllegalArgumentException("Invalid array length: " + key);
            value.getAsJsonArray().forEach(item -> validateValue(key, item, schema.getAsJsonObject("items")));
        } else if (type.equals("number") || type.equals("integer")) {
            double n = value.getAsDouble(); if (schema.has("minimum") && n < schema.get("minimum").getAsDouble() || schema.has("maximum") && n > schema.get("maximum").getAsDouble()) throw new IllegalArgumentException("Argument is outside its range: " + key);
        }
    }
    public static JsonObject result(Object value) {
        JsonObject data = value instanceof JsonObject object ? object : JSON.toJsonTree(value).getAsJsonObject();
        JsonObject result = new JsonObject(); result.add("structuredContent", data); result.add("content", textContent(JSON.toJson(data))); return result;
    }
    public static JsonArray textContent(String value) { JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", value); JsonArray list = new JsonArray(); list.add(text); return list; }
    public static JsonObject failure(String message) { JsonObject result = new JsonObject(); result.addProperty("isError", true); result.add("content", textContent(message)); return result; }
}

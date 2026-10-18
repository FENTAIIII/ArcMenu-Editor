package com.fentai.arcmenu.editor;

import com.google.gson.JsonObject;

/** Earlier game adapters retain their existing menu input without advertising Vision support. */
public final class VisionCursorClient {
    private VisionCursorClient() {}
    public static void initialize() {}
    public static boolean active() { return false; }
    public static void resourcesReloaded() {}
    public static JsonObject metadata() {
        var result = new JsonObject();
        result.addProperty("active", false);
        result.addProperty("controlling", false);
        return result;
    }
}

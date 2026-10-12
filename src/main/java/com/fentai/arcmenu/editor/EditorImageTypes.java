package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import java.util.Locale;

/** Original image paths identify their editor kind without adding protocol fields. */
public final class EditorImageTypes {
    public static boolean gif(String path) { return path.toLowerCase(Locale.ROOT).endsWith(".gif"); }
    public static boolean image(String path) { return path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp|gif)$"); }
    public static boolean imageKind(byte kind) { return kind == EditorProtocol.KIND_IMAGE || kind == EditorProtocol.KIND_GIF; }
    public static byte kind(String path) { return gif(path) ? EditorProtocol.KIND_GIF : EditorProtocol.KIND_IMAGE; }
    public static boolean matches(byte kind, String path) { return kind == EditorProtocol.KIND_GIF ? gif(path) : !gif(path); }
    public static String hint(byte kind, String property) {
        return kind == EditorProtocol.KIND_GIF && property.equals("speed")
                ? EditorI18n.text("arcmenu_editor.images.gif_speed_hint") : "";
    }
    private EditorImageTypes() {}
}

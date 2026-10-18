package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;

/** Older render adapters retain editor features without advertising the Vision scene protocol. */
public final class VisionSceneClient {
    private VisionSceneClient() {}
    public static void initialize() {}
    public static boolean supported() { return false; }
    public static boolean ready() { return false; }
    public static void receive(VisionProtocol.Scene scene) {}
    public static void clear() {}
    public static void receivePreview(VisionProtocol.PreviewScene scene) {}
    public static boolean previewReady() { return false; }
    public static void clearPreview() {}
    public static void extract(GuiGraphicsExtractor graphics) {}
    public static Matrix4f viewProjection(int width, int height) { return null; }
    public static float verticalFov() { return VisionProtocol.MENU_VERTICAL_FOV; }
}

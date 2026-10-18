package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

/** Native Vision text input is enabled only by its implemented renderer adapter. */
public final class VisionTextClient {
    private VisionTextClient() {}
    public static boolean editing() { return false; }
    public static void receive(VisionProtocol.TextState state) {}
    public static void tick() {}
    public static void key(KeyEvent event, int action) {}
    public static void render(GuiGraphicsExtractor graphics) {}
    public static void finish() {}
    public static void clear() {}
}

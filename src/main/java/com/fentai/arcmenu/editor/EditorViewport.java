package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/** Shares the docked game rectangle with render mixins without changing GUI dimensions. */
public final class EditorViewport {
    private static volatile EditorLayout.Rect guiRect;
    private static volatile double verticalFov = 70;

    private EditorViewport() {}

    public static void update(EditorLayout.Rect rect) {
        guiRect = rect;
    }

    public static double verticalFov() {
        return verticalFov;
    }

    public static void verticalFov(double value) {
        if (Double.isFinite(value) && value > 0 && value < 180) verticalFov = value;
    }

    /** Optional Effect composition crop; ordinary menu editors retain the full source frame. */
    public static EditorLayout.Rect sourceFrame(int sourceWidth, int sourceHeight) {
        var client = Minecraft.getInstance();
        if (!(client.screen instanceof EffectEditorScreen screen)) return null;
        var plane = ArcMenuEditorClient.EFFECT.viewport();
        var scene = ArcMenuEditorClient.state().snapshot();
        var viewport = guiRect;
        if (plane == null || scene == null || viewport == null || sourceWidth < 1 || sourceHeight < 1) return null;
        var base = screen.fitView() ? EffectProjection.fitCrop(sourceWidth, sourceHeight, viewport.width() / (double) viewport.height(),
                scene.canvasWidth(), scene.canvasHeight(), plane.pixelsPerBlock(), plane.distance(),
                plane.offsetX(), plane.offsetY(), verticalFov()) : new EditorLayout.Rect(0,0,sourceWidth,sourceHeight);
        if (!screen.fitView() && screen.viewZoom()==1 && screen.viewPanX()==0 && screen.viewPanY()==0) return null;
        return EffectViewNavigation.crop(base,sourceWidth,sourceHeight,screen.viewZoom(),screen.viewPanX(),screen.viewPanY());
    }

    public static void clear() {
        guiRect = null;
        EditorWorldCompositor.release();
    }

    public static Physical physical(Window window) {
        EditorLayout.Rect rect = guiRect;
        if (rect == null || window.getGuiScaledWidth() < 1 || window.getGuiScaledHeight() < 1) return null;

        double scaleX = window.getWidth() / (double) window.getGuiScaledWidth();
        double scaleY = window.getHeight() / (double) window.getGuiScaledHeight();
        int availableWidth = Math.max(16, (int) Math.floor(rect.width() * scaleX));
        int availableHeight = Math.max(9, (int) Math.floor(rect.height() * scaleY));
        int unit = Math.max(1, Math.min(availableWidth / 16, availableHeight / 9));
        int width = unit * 16;
        int height = unit * 9;

        int centerX = (int) Math.round((rect.x() + rect.width() / 2.0) * scaleX);
        int centerYFromTop = (int) Math.round((rect.y() + rect.height() / 2.0) * scaleY);
        int x = clamp(centerX - width / 2, 0, Math.max(0, window.getWidth() - width));
        int top = clamp(centerYFromTop - height / 2, 0, Math.max(0, window.getHeight() - height));
        int y = window.getHeight() - top - height;
        return new Physical(x, y, width, height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record Physical(int x, int y, int width, int height) {}
}

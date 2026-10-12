package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.render.Graph;
import com.fentai.arcmenu.editor.aui.internal.spi.MeshBuilder;
import org.joml.Matrix4f;

/** Keeps insertion carets visible on the retained surface at fractional scales. */
public final class AuiCaret {
    private static final float CSS_WIDTH = 0.7f;
    private static Blink activeBlink = new Blink();

    private AuiCaret() {}

    static void bind(Blink blink) { activeBlink = blink; }

    public static boolean visible(long lastBlinkTime) {
        return phase(System.currentTimeMillis(), Math.max(lastBlinkTime, activeBlink.lastInteraction)) == 0;
    }

    private static int phase(long now, long epoch) {
        return (int) (Math.max(0, now - epoch) % 1000 / 500);
    }

    static final class Blink {
        private long lastInteraction;
        void interact(long now) { lastInteraction = now; }
        int phase(long now) { return AuiCaret.phase(now, lastInteraction); }
    }

    public static void addRect(MeshBuilder mesh, Matrix4f pose, float x, float y,
                               float height, int color) {
        double scale = Math.hypot(pose.m00(), pose.m01());
        if (!Double.isFinite(scale) || scale <= 0 || !Float.isFinite(x)
                || !Float.isFinite(y) || !Float.isFinite(height) || height <= 0) return;

        float right = x;
        float width = (float) Math.max(CSS_WIDTH, 1.0 / scale);
        if (Math.abs(pose.m01()) < 0.00001f && Math.abs(pose.m10()) < 0.00001f) {
            double physicalX = pose.m00() * (double) x + pose.m10() * (double) y + pose.m30();
            right += (float) ((Math.round(physicalX) - physicalX) / pose.m00());
            width = (float) (Math.max(1L, Math.round(CSS_WIDTH * scale)) / scale);
        }
        Graph.addRect(mesh, pose, right - width, y, right, y + height, color | 0xff000000);
    }
}

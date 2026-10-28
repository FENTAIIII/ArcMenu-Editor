package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionHoverTable;
import com.fentai.arcmenu.protocol.VisionProtocol;
import com.fentai.arcmenu.protocol.VisionVisuals;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/** Predictions affect extracted GUI states only; native entity metadata remains authoritative. */
public final class VisionVisualClient {
    private static VisionVisuals.Hover hover;
    private static VisionHoverTable table;
    private static final Map<Integer, VisionVisuals.HoverTarget> targets = new HashMap<>();
    private static final Map<Integer, VisionVisuals.Follow> follows = new HashMap<>();
    private static VisionVisuals.Vector eye;
    private static VisionVisuals.Vector direction;
    private static double tick;

    private VisionVisualClient() {}

    public static void receive(VisionVisuals.Hover next) {
        if (next.sessionId() != VisionCursorClient.sessionId() || next.equals(hover)) return;
        hover = next;
        table = next.words().isEmpty() ? null : new VisionHoverTable(next.words());
        targets.clear();
        next.targets().forEach(target -> targets.put(target.entityId(), target));
    }

    public static void receive(VisionVisuals.Follow next) {
        if (next.sessionId() != VisionCursorClient.sessionId()) return;
        if (next.matrix().isEmpty()) follows.remove(next.entityId());
        else follows.put(next.entityId(), next);
    }

    public static void clear() {
        hover = null;
        table = null;
        targets.clear();
        follows.clear();
        eye = null;
        direction = null;
    }

    public static void frame(VisionProtocol.Scene scene) {
        var aim = VisionCursorClient.frameAim();
        tick = System.nanoTime() / 50_000_000.0;
        eye = new VisionVisuals.Vector(scene.eyeX(), scene.eyeY(), scene.eyeZ());
        direction = null;
        if (aim != null && !VisionTextClient.editing()) {
            double yaw = Math.toRadians(aim.yaw()), pitch = Math.toRadians(aim.pitch());
            direction = new VisionVisuals.Vector(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        }
        if (table != null) table.update(point(hover.plane()), tick);
    }

    public static boolean choosing(boolean fallback) { return table == null ? fallback : table.choosing(); }

    /** False hides a tooltip as soon as its server-authorized region is left. */
    public static boolean apply(int entityId, DisplayEntityRenderState state) {
        var follow = follows.get(entityId);
        if (follow != null && state.renderState != null) {
            var point = point(follow.plane());
            if (follow.targetRegion() >= 0 && VisionVisuals.hit(follow.regions(), point) != follow.targetRegion()) return false;
            if (point != null) {
                float[] values = new float[16];
                for (int i = 0; i < values.length; i++) values[i] = follow.matrix().get(i);
                var offset = follow.stepX().scale(point.x()).add(follow.stepY().scale(point.y()));
                var transform = new Matrix4f().set(values);
                transform.m30(transform.m30() + (float) offset.x());
                transform.m31(transform.m31() + (float) offset.y());
                transform.m32(transform.m32() + (float) offset.z());
                var original = state.renderState;
                state.renderState = new Display.RenderState(Display.GenericInterpolator.constant(new Transformation(transform)),
                        original.billboardConstraints(), original.brightnessOverride(), original.shadowRadius(), original.shadowStrength(), original.glowColorOverride());
            }
        }
        var target = targets.get(entityId);
        if (target != null && table != null && state instanceof TextDisplayEntityRenderState text && text.textRenderState != null) {
            int argb = table.argb(target.owner(), tick);
            if (target.text() && text.cachedInfo != null) {
                var lines = text.cachedInfo.lines().stream().map(line -> new Display.TextDisplay.CachedLine(
                        sink -> line.contents().accept((index, style, codepoint) -> sink.accept(index, style.withColor(argb & 0xFFFFFF), codepoint)), line.width())).toList();
                text.cachedInfo = new Display.TextDisplay.CachedInfo(lines, text.cachedInfo.width());
            } else if (!target.text()) {
                var original = text.textRenderState;
                text.textRenderState = new Display.TextDisplay.TextRenderState(original.text(), original.lineWidth(), original.textOpacity(),
                        Display.IntInterpolator.constant(argb), original.flags());
            }
        }
        return true;
    }

    private static VisionVisuals.Point point(VisionVisuals.Plane plane) {
        return eye == null || direction == null ? null : plane.project(eye, direction);
    }
}

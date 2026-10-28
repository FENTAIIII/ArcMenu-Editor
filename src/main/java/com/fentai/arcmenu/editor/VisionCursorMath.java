package com.fentai.arcmenu.editor;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Native screen coordinates and the camera's actual projection share one cursor ray. */
public final class VisionCursorMath {
    private VisionCursorMath() {}

    public record Point(double x, double y) {}
    public record Aim(float yaw, float pitch) {}

    public static boolean inside(Point point, int width, int height) {
        return point.x() >= 0 && point.x() < width && point.y() >= 0 && point.y() < height;
    }

    public static Aim aim(Point point, int width, int height, Matrix4f viewProjection) {
        float x = (float) (2 * Math.clamp(point.x() / width, 0, 1) - 1);
        float y = (float) (1 - 2 * Math.clamp(point.y() / height, 0, 1));
        Vector3f direction = new Matrix4f(viewProjection).invert().transformProject(new Vector3f(x, y, .5f));
        return new Aim((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }

}

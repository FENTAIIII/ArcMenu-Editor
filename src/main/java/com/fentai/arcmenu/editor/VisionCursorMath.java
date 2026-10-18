package com.fentai.arcmenu.editor;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Native screen coordinates and the camera's actual projection share one cursor ray. */
public final class VisionCursorMath {
    private VisionCursorMath() {}

    public record Point(double x, double y) {}
    public record Aim(float yaw, float pitch) {}

    public static Point move(Point point, double dx, double dy, int width, int height,
                             double sensitivityX, double sensitivityY) {
        return new Point(Math.clamp(point.x() + dx * sensitivityX, 0, Math.max(0, width - 1)),
                Math.clamp(point.y() + dy * sensitivityY, 0, Math.max(0, height - 1)));
    }

    public static Aim aim(Point point, int width, int height, Matrix4f viewProjection) {
        float x = (float) (2 * Math.clamp(point.x() / width, 0, 1) - 1);
        float y = (float) (1 - 2 * Math.clamp(point.y() / height, 0, 1));
        Vector3f direction = new Matrix4f(viewProjection).invert().transformProject(new Vector3f(x, y, .5f));
        return new Aim((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }

    public static double spriteHeight(double size, double projection, int viewportHeight, double verticalFov) {
        return size * viewportHeight / (2 * projection * Math.tan(Math.toRadians(verticalFov) / 2));
    }
}

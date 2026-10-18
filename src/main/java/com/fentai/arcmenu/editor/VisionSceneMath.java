package com.fentai.arcmenu.editor;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** The menu's projection is independent of gameplay FOV, effects, and camera bobbing. */
public final class VisionSceneMath {
    private VisionSceneMath() {}

    public static Quaternionf orientation(float yaw, float pitch) {
        return new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yaw),
                (float) -Math.toRadians(pitch), 0);
    }

    public static Matrix4f viewProjection(float yaw, float pitch, float verticalFov, int width, int height) {
        return new Matrix4f().perspective((float) Math.toRadians(verticalFov),
                Math.max(1, width) / (float) Math.max(1, height), .05f, 1024f)
                .rotate(orientation(yaw, pitch).conjugate());
    }
}

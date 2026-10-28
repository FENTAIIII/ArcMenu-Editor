package com.fentai.arcmenu.editor;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VisionCursorMathTest {
    @Test void nativePointerCanLeaveEveryWindowEdgeWithoutSelectingAnEdgePixel() {
        assertTrue(VisionCursorMath.inside(new VisionCursorMath.Point(0, 0), 800, 600));
        assertTrue(VisionCursorMath.inside(new VisionCursorMath.Point(799.9, 599.9), 800, 600));
        for (var point : new VisionCursorMath.Point[]{new VisionCursorMath.Point(-1, 300),
                new VisionCursorMath.Point(800, 300), new VisionCursorMath.Point(400, -1),
                new VisionCursorMath.Point(400, 600)}) {
            assertFalse(VisionCursorMath.inside(point, 800, 600));
        }
    }

    @Test void unprojectionUsesTheRealCameraAspectAndFov() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 100);
        var center = VisionCursorMath.aim(new VisionCursorMath.Point(800, 450), 1600, 900, projection);
        assertEquals(180, Math.abs(center.yaw()), .001);
        assertEquals(0, center.pitch(), .001);
        var right = VisionCursorMath.aim(new VisionCursorMath.Point(1200, 450), 1600, 900, projection);
        var up = VisionCursorMath.aim(new VisionCursorMath.Point(800, 225), 1600, 900, projection);
        assertTrue(right.yaw() < 0);
        assertTrue(up.pitch() < 0);
    }
}

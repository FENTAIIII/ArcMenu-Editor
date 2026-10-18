package com.fentai.arcmenu.editor;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VisionCursorMathTest {
    @Test void equalRawMotionMovesBothAxesEqually() {
        var start = new VisionCursorMath.Point(400, 300);
        var point = VisionCursorMath.move(start, 60, 60, 800, 600, 1, 1);
        assertEquals(60, point.x() - start.x());
        assertEquals(60, point.y() - start.y());
        assertEquals(new VisionCursorMath.Point(490, 390),
                VisionCursorMath.move(start, 60, 60, 800, 600, 1.5, 1.5));
    }

    @Test void cursorStaysInsideThePhysicalViewport() {
        assertEquals(new VisionCursorMath.Point(799, 0), VisionCursorMath.move(
                new VisionCursorMath.Point(400, 300), 1000, -1000, 800, 600, 1, 1));
        assertEquals(new VisionCursorMath.Point(799, 599), VisionCursorMath.move(
                new VisionCursorMath.Point(400, 300), 1000, 1000, 800, 600, 1, 1));
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
        assertEquals(5 * 900 / (2 * 128.1 * Math.tan(Math.toRadians(35))),
                VisionCursorMath.spriteHeight(5, 128.1, 900, 70), 1e-9);
    }
}

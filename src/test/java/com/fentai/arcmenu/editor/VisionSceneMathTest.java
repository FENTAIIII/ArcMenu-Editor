package com.fentai.arcmenu.editor;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VisionSceneMathTest {
    @Test void centerRayMatchesFixedCameraAtAnyRotation() {
        for (float yaw : new float[]{-170, -90, 0, 65, 170}) {
            for (float pitch : new float[]{-60, 0, 40}) {
                var matrix = VisionSceneMath.viewProjection(yaw, pitch, 70, 427, 241);
                var aim = VisionCursorMath.aim(new VisionCursorMath.Point(640.5, 360.5), 1281, 721, matrix);
                assertEquals(yaw, aim.yaw(), .0001);
                assertEquals(pitch, aim.pitch(), .0001);
            }
        }
    }

    @Test void nonDivisibleGuiScaleKeepsVisibleElementAndClickOnSameRay() {
        int width = 1281, height = 721, scale = 3;
        int guiWidth = Math.ceilDiv(width, scale), guiHeight = Math.ceilDiv(height, scale);
        var matrix = VisionSceneMath.viewProjection(0, 0, 70, guiWidth, guiHeight);
        var element = new Vector3f(25, 15, 100);
        var projected = matrix.transformProject(new Vector3f(element));
        var cursor = new VisionCursorMath.Point((projected.x + 1) * width / 2, (1 - projected.y) * height / 2);
        var aim = VisionCursorMath.aim(cursor, width, height, matrix);
        assertEquals(Math.toDegrees(Math.atan2(-25, 100)), aim.yaw(), .0001);
        assertEquals(-Math.toDegrees(Math.atan2(15, Math.hypot(25, 100))), aim.pitch(), .0001);
        assertTrue(cursor.x() < width / 2.0);
        assertTrue(cursor.y() < height / 2.0);
    }

    @Test void fixedProjectionUsesSeventyDegreeVerticalField() {
        var matrix = VisionSceneMath.viewProjection(0, 0, 70, 16, 9);
        var upperEdge = VisionCursorMath.aim(new VisionCursorMath.Point(800, 0), 1600, 900, matrix);
        assertEquals(-35, upperEdge.pitch(), .0001);
        var center = matrix.transformProject(new Vector3f(0, 0, 100));
        assertEquals(0, center.x, .000001);
        assertEquals(0, center.y, .000001);
    }
}

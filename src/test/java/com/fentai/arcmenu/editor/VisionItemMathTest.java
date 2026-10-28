package com.fentai.arcmenu.editor;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VisionItemMathTest {
    private static final Matrix3f FLAT_LIGHTS = new Matrix3f().rotationY((float) (-Math.PI / 8))
            .rotateX((float) (Math.PI * 3 / 4));
    private static final Matrix3f BLOCK_LIGHTS = new Matrix3f().scaling(1, -1, 1)
            .rotateYXZ(1.0821041f, 3.2375858f, 0).mul(FLAT_LIGHTS);

    @Test void frontLitSpritesKeepFullBrightnessAlongsideShadedBlocks() {
        var front = new Vector3f(0, 0, 1);
        assertEquals(1, shade(front, FLAT_LIGHTS), 1e-5f);
        assertTrue(shade(front, BLOCK_LIGHTS) < .6f);
    }

    @Test void cameraRotationAndDepthCompressionDoNotFlattenCubeLighting() {
        var face = new Vector3f(.5f, .7f, .5f).normalize();
        var expected = new Vector3f(face.x, -face.y, face.z);
        for (float yaw : new float[]{-2.4f, 0f, 1.5f}) {
            var model = new Matrix4f().rotateY(yaw).scale(.4f, .4f, .05f);
            var view = new Matrix4f().rotateY(-yaw).mul(model);
            var normal = view.normal(new Matrix3f());
            var corrected = VisionItemMath.guiNormals(normal, VisionItemMath.squash(model));
            assertEquals(shade(expected, BLOCK_LIGHTS),
                    shade(corrected.transform(new Vector3f(face)).normalize(), FLAT_LIGHTS), 1e-5f);
        }
    }

    @Test void cubeFacesKeepDistinctInventoryShades() {
        var correction = VisionItemMath.guiNormals(new Matrix3f().scaling(1, 1, 8), 8);
        float top = shade(correction.transform(new Vector3f(0, .8f, .6f)).normalize(), FLAT_LIGHTS);
        float left = shade(correction.transform(new Vector3f(-.8f, 0, .6f)).normalize(), FLAT_LIGHTS);
        float right = shade(correction.transform(new Vector3f(.8f, 0, .6f)).normalize(), FLAT_LIGHTS);
        assertTrue(top > right + .2f);
        assertTrue(left > right + .2f);
        assertNotEquals(top, left);
    }

    @Test void degenerateTransformsCannotIntroduceNonFiniteNormals() {
        var normal = new Matrix3f();
        assertTrue(Double.isNaN(VisionItemMath.squash(new Matrix4f().scale(1, 1, 0))));
        assertEquals(normal, VisionItemMath.guiNormals(normal, Double.NaN));
    }

    private static float shade(Vector3f normal, Matrix3f lights) {
        var first = lights.transform(new Vector3f(.2f, 1, -.7f).normalize());
        var second = lights.transform(new Vector3f(-.2f, 1, .7f).normalize());
        return Math.min(1, .4f + .6f * (Math.max(0, normal.dot(first)) + Math.max(0, normal.dot(second))));
    }
}

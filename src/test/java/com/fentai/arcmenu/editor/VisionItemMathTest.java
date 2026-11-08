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

    @Test void thinAuthoredGeometryKeepsItsVerticesAndEstablishedInventoryShades() {
        var faces = new Vector3f[]{new Vector3f(0, .8f, .6f), new Vector3f(-.8f, 0, .6f), new Vector3f(.8f, 0, .6f)};
        for (double ratio : new double[]{1, 8, 614.9}) {
            for (float yaw : new float[]{-1.4f, 0, 2.1f}) {
                for (float mirror : new float[]{-1, 1}) {
                    var authored = new Matrix4f().translation(2.3f, -.7f, 1.1f)
                            .rotateXYZ(.31f, yaw, -.23f).scale(mirror * 6.149f, 6.149f, (float) (6.149 / ratio));
                    var unchanged = new Matrix4f(authored);
                    var view = new Matrix4f().rotateXYZ(-.17f, .63f, .09f).mul(authored);
                    var normal = view.normal(new Matrix3f());
                    var beforeNormal = new Matrix3f(normal);
                    var correction = VisionItemMath.lightingCorrection(normal, VisionItemMath.squash(authored));
                    assertNotNull(correction);

                    // A non-orthogonal layer transform makes vanilla rebuild its normal matrix.
                    // The light correction must remain valid when applied after that rebuild.
                    var layer = new Matrix4f().rotateY((float) Math.PI).rotateXYZ(.52f, 3.93f, -.11f)
                            .scale(.7f, .9f, 1.1f);
                    var actual = new Matrix3f(correction).mul(new Matrix4f(view).mul(layer).normal(new Matrix3f()));

                    // Reference the previous geometry's inverse transpose, independently of the
                    // new correction helper. Its positions are deliberately not used for drawing.
                    double referenceRatio = Math.min(ratio, 8);
                    var reference = new Matrix4f(view).scale(1, 1, (float) (ratio / referenceRatio))
                            .normal(new Matrix3f()).mul(layer.normal(new Matrix3f()));
                    for (var face : faces) {
                        var expected = reference.transform(new Vector3f(face));
                        expected.y = -expected.y;
                        expected.z /= (float) referenceRatio;
                        expected.normalize();
                        var result = actual.transform(new Vector3f(face)).normalize();
                        assertEquals(shade(expected, BLOCK_LIGHTS), shade(result, FLAT_LIGHTS), 4e-4f,
                                "lighting changed for ratio=" + ratio + ", yaw=" + yaw + ", mirror=" + mirror);
                    }
                    assertEquals(unchanged, authored, "lighting must not change the authored vertex transformation");
                    assertEquals(beforeNormal, normal, "capturing a correction must not mutate the source normal matrix");
                    var corner = new Vector3f(.5f, .5f, .5f);
                    assertEquals(unchanged.transformPosition(new Vector3f(corner)), authored.transformPosition(corner));
                }
            }
        }
    }

    @Test void thinCubeFacesRetainDistinctShadingBeforeNormalQuantization() {
        var geometry = new Matrix4f().scale(6.149f, 6.149f, .01f);
        var normal = geometry.normal(new Matrix3f());
        var correction = VisionItemMath.lightingCorrection(normal, VisionItemMath.squash(geometry));
        assertNotNull(correction);
        var lighting = new Matrix3f(correction).mul(normal);
        float top = shade(lighting.transform(new Vector3f(0, .8f, .6f)).normalize(), FLAT_LIGHTS);
        float left = shade(lighting.transform(new Vector3f(-.8f, 0, .6f)).normalize(), FLAT_LIGHTS);
        float right = shade(lighting.transform(new Vector3f(.8f, 0, .6f)).normalize(), FLAT_LIGHTS);
        assertEquals(shade(new Vector3f(0, -.8f, .6f), BLOCK_LIGHTS), top, 1e-5f);
        assertEquals(shade(new Vector3f(-.8f, 0, .6f), BLOCK_LIGHTS), left, 1e-5f);
        assertEquals(shade(new Vector3f(.8f, 0, .6f), BLOCK_LIGHTS), right, 1e-5f);
        assertTrue(left > right + .2f);
        assertTrue(top > right + .2f);
        assertNotEquals(top, left);
        assertEquals(.01f, geometry.m22());
    }

    @Test void invalidLightingTransformsLeaveTheCallerStateUntouched() {
        var singular = new Matrix3f().scaling(1, 1, 0);
        var unchanged = new Matrix3f(singular);
        assertNull(VisionItemMath.lightingCorrection(singular, 614.9));
        assertEquals(unchanged, singular);
        assertNull(VisionItemMath.lightingCorrection(new Matrix3f(), Double.NaN));
        assertNull(VisionItemMath.lightingCorrection(new Matrix3f(), Double.POSITIVE_INFINITY));
    }

    private static float shade(Vector3f normal, Matrix3f lights) {
        var first = lights.transform(new Vector3f(.2f, 1, -.7f).normalize());
        var second = lights.transform(new Vector3f(-.2f, 1, .7f).normalize());
        return Math.min(1, .4f + .6f * (Math.max(0, normal.dot(first)) + Math.max(0, normal.dot(second))));
    }
}

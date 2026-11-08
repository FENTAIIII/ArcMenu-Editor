package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class VisionItemLightingTest {
    @Test void layerNormalRebuildsPreserveAuthoredVerticesAndLighting() {
        var authored = new Matrix4f().translate(.3f, -.2f, .7f).rotateXYZ(.3f, -.7f, .2f)
                .scale(6.149f, 6.149f, .01f);
        var state = state(authored);
        var originalState = state.renderState;
        var pose = new PoseStack();
        pose.mulPose(authored);
        var correction = VisionItemMath.lightingCorrection(pose.last().normal(), VisionItemMath.squash(authored));
        assertNotNull(correction);
        VisionItemLighting.begin(true);
        try {
            VisionItemLighting.capture(state, pose);
            var local = new Matrix4f().rotateXYZ(.52f, 3.93f, -.11f).scale(.7f, .9f, 1.1f);
            pose.mulPose(local);
            var vertices = new Matrix4f(pose.last().pose());
            var physicalNormals = new Matrix3f(pose.last().normal());
            VisionItemLighting.applyLayer(pose.last());
            assertEquals(vertices, pose.last().pose());
            assertSame(originalState, state.renderState);
            assertEquals(authored, state.renderState.transformation().get(0).getMatrix());
            for (var face : new Vector3f[]{new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1)}) {
                var expected = new Matrix3f(correction).mul(physicalNormals).transform(new Vector3f(face)).normalize();
                var actual = pose.last().transformNormal(face, new Vector3f());
                assertEquals(expected.x, actual.x, 1e-5f);
                assertEquals(expected.y, actual.y, 1e-5f);
                assertEquals(expected.z, actual.z, 1e-5f);
            }
        } finally {
            VisionItemLighting.end();
        }
    }

    @Test void frontLitAndOrdinaryRenderingNeverReceiveTheVisionCorrection() {
        var authored = new Matrix4f().rotateY(.6f).scale(6.149f, 6.149f, .01f);
        var state = state(authored);
        var pose = new PoseStack();
        pose.mulPose(authored);
        var originalVertices = new Matrix4f(pose.last().pose());
        var originalNormals = new Matrix3f(pose.last().normal());
        VisionItemLighting.begin(false);
        try {
            VisionItemLighting.capture(state, pose);
            VisionItemLighting.applyLayer(pose.last());
            assertEquals(originalVertices, pose.last().pose());
            assertEquals(originalNormals, pose.last().normal());
        } finally {
            VisionItemLighting.end();
        }
        VisionItemLighting.begin(true);
        VisionItemLighting.capture(state, pose);
        VisionItemLighting.end();
        VisionItemLighting.applyLayer(pose.last());
        assertEquals(originalVertices, pose.last().pose());
        assertEquals(originalNormals, pose.last().normal());
    }

    private static DisplayEntityRenderState state(Matrix4f authored) {
        var state = new DisplayEntityRenderState() {
            @Override public boolean hasSubState() { return true; }
        };
        state.renderState = new Display.RenderState(Display.GenericInterpolator.constant(new Transformation(authored)),
                Display.BillboardConstraints.FIXED, 0xF000F0,
                Display.FloatInterpolator.constant(0), Display.FloatInterpolator.constant(0), -1);
        return state;
    }
}

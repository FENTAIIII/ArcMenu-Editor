package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import org.joml.Matrix3f;

/** Inventory lighting is scoped to block-lit GUI items in the isolated Vision render pass. */
public final class VisionItemLighting {
    private static boolean guiItem;
    private static Matrix3f correction;

    private VisionItemLighting() {}

    public static void begin(boolean gui) {
        guiItem = gui;
        correction = null;
    }
    public static void end() { guiItem = false; correction = null; }

    /** Capture the display-space lighting reference without changing its vertex transformation. */
    public static void capture(DisplayEntityRenderState state, PoseStack poseStack) {
        correction = null;
        if (!guiItem || state.renderState == null) return;
        double squash = VisionItemMath.squash(state.renderState.transformation().get(state.interpolationProgress).getMatrix());
        correction = VisionItemMath.lightingCorrection(poseStack.last().normal(), squash);
    }

    /** Local model matrices may rebuild normals, so apply after them and before either submission path. */
    public static void applyLayer(PoseStack.Pose pose) {
        if (guiItem && correction != null) {
            pose.normal().set(new Matrix3f(correction).mul(pose.normal()));
        }
    }
}

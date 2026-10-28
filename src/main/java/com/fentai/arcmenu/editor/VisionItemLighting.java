package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Inventory lighting is scoped to block-lit GUI items in the isolated Vision render pass. */
public final class VisionItemLighting {
    private static boolean guiItem;

    private VisionItemLighting() {}

    public static void begin(boolean gui) { guiItem = gui; }
    public static void end() { guiItem = false; }

    public static void prepare(DisplayEntityRenderState state) {
        if (state.renderState == null) return;
        var original = state.renderState;
        var matrix = new Matrix4f(original.transformation().get(state.interpolationProgress).getMatrix());
        double squash = VisionItemMath.squash(matrix);
        if (!(squash > 8) || !Double.isFinite(squash)) return;
        matrix.scale(1, 1, (float) (squash / 8));
        state.renderState = new Display.RenderState(Display.GenericInterpolator.constant(new Transformation(matrix)),
                original.billboardConstraints(), original.brightnessOverride(), original.shadowRadius(), original.shadowStrength(), original.glowColorOverride());
    }

    /** Undo the icon's depth compression before Minecraft packs vertex normals into signed bytes. */
    public static void apply(DisplayEntityRenderState state, PoseStack poseStack) {
        if (!guiItem || state.renderState == null) return;
        double squash = VisionItemMath.squash(state.renderState.transformation().get(state.interpolationProgress).getMatrix());
        var normal = poseStack.last().normal();
        normal.set(VisionItemMath.guiNormals(new Matrix3f(normal), squash));
    }
}

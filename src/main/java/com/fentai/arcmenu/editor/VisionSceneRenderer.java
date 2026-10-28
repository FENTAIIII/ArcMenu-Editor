package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Native GUI rendering runs after world post-processing, with its own transparent color and depth targets. */
public final class VisionSceneRenderer extends PictureInPictureRenderer<VisionSceneRenderState> {
    private final EntityRenderDispatcher dispatcher;
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("ArcMenu Vision menu");

    public VisionSceneRenderer(EntityRenderDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @Override public Class<VisionSceneRenderState> getRenderStateClass() { return VisionSceneRenderState.class; }
    @Override protected String getTextureLabel() { return "ArcMenu Vision menu"; }

    @Override protected void renderToTexture(VisionSceneRenderState state, PoseStack pose, SubmitNodeCollector collector) {
        submitScene(state, pose, collector);
    }

    private void submitScene(VisionSceneRenderState state, PoseStack pose, SubmitNodeCollector collector) {
        var client = Minecraft.getInstance();
        var scene = state.scene();
        projection.setupPerspective(.05f, 1024f, scene.verticalFov(), state.viewport().width(), state.viewport().height());
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
        RenderSystem.getModelViewStack().identity();
        pose.last().pose().identity();
        pose.last().normal().identity();
        var orientation = VisionSceneMath.orientation(scene.yaw(), scene.pitch());
        pose.mulPose(orientation.conjugate(new Quaternionf()));
        client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_FLAT);
        var camera = new CameraRenderState();
        camera.orientation.set(orientation);
        camera.pos = new Vec3(scene.eyeX(), scene.eyeY(), scene.eyeZ());
        camera.xRot = scene.pitch();
        camera.yRot = scene.yaw();
        camera.initialized = true;
        for (var entity : state.entities()) {
            VisionItemLighting.begin(state.guiItems().contains(entity));
            try {
                dispatcher.submit(entity, camera, entity.x - scene.eyeX(), entity.y - scene.eyeY(), entity.z - scene.eyeZ(), pose, collector);
            } finally {
                VisionItemLighting.end();
            }
        }
    }

    @Override public void close() { projectionBuffer.close(); super.close(); }
}

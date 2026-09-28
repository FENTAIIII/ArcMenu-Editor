package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.util.Optional;

/** Uses the engine render backend so the viewport also works with Vulkan. */
public final class EditorWorldCompositor {
    private static final RenderPipeline VIEWPORT_BLIT = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("arcmenu_editor", "pipeline/viewport_blit"))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("arcmenu_editor", "core/viewport_blit"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler")
                    .withUniform("EditorViewport", UniformType.UNIFORM_BUFFER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(Optional.empty())
            .withCull(false)
            .build());
    private static TextureTarget worldCopy;

    private EditorWorldCompositor() {}

    /** Register the pipeline before the initial shader resource reload. */
    public static void initialize() {}

    public static void compose() {
        var minecraft = Minecraft.getInstance();
        var viewport = EditorViewport.physical(minecraft.getWindow());
        if (viewport == null || !(minecraft.gui.screen() instanceof EditorScreen)) return;
        RenderSystem.assertOnRenderThread();
        var main = minecraft.gameRenderer.mainRenderTarget();
        if (main.width < 1 || main.height < 1) return;
        if (worldCopy == null) {
            worldCopy = new TextureTarget("ArcMenu editor world frame", main.width, main.height,
                    false, main.getColorTexture().getFormat());
        } else if (worldCopy.width != main.width || worldCopy.height != main.height) {
            worldCopy.resize(main.width, main.height);
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), worldCopy.getColorTexture(),
                0, 0, 0, 0, 0, main.width, main.height);
        encoder.clearColorTexture(main.getColorTexture(), new Vector4f(16 / 255f, 19 / 255f, 26 / 255f, 1));
        // RenderArea only clips; it does not scale the backend's full-target
        // viewport. Remap texture coordinates explicitly to fit the full frame.
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc(16);
            data.putFloat(viewport.x() / (float) main.width).putFloat(viewport.y() / (float) main.height)
                    .putFloat(viewport.width() / (float) main.width).putFloat(viewport.height() / (float) main.height).flip();
            var uniform = encoder.transientMemory().uploadGpu(data,
                    RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
            try (var pass = encoder.createRenderPass(() -> "ArcMenu editor viewport",
                    main.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(VIEWPORT_BLIT);
                pass.setUniform("EditorViewport", uniform);
                pass.bindTexture("InSampler", worldCopy.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.draw(3, 1, 0, 0);
            }
        }
        encoder.submit();
    }

    public static void release() {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(EditorWorldCompositor::release);
            return;
        }
        if (worldCopy != null) {
            worldCopy.destroyBuffers();
            worldCopy = null;
        }
    }
}

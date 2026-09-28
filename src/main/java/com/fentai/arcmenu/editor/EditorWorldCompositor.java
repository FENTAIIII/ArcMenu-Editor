package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Objects;

/**
 * Composites the complete, post-processed game frame into the editor aperture.
 *
 * <p>A render-level viewport is insufficient on the current renderer because
 * individual render passes restore a full-target viewport. This compositor
 * snapshots the completed world color target, clears the real main target, and
 * performs a filtered framebuffer blit into the exact aperture. GUI rendering
 * happens afterwards and remains full-window.</p>
 */
public final class EditorWorldCompositor {
    private static final int WORKSPACE_COLOR = 0xFF10131A;
    private static TextureTarget worldCopy;
    private static int readFramebuffer;
    private static int drawFramebuffer;

    private EditorWorldCompositor() {}

    public static void initialize() {}

    public static void compose() {
        Minecraft minecraft = Minecraft.getInstance();
        EditorViewport.Physical viewport = EditorViewport.physical(minecraft.getWindow());
        if (viewport == null || !(minecraft.screen instanceof EditorScreen)) return;

        RenderSystem.assertOnRenderThread();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main.width < 1 || main.height < 1) return;
        ensureResources(main.width, main.height);

        GpuTexture mainColor = Objects.requireNonNull(main.getColorTexture(), "Minecraft main color texture");
        GpuTexture copyColor = Objects.requireNonNull(worldCopy.getColorTexture(), "ArcMenu world copy texture");
        var encoder = RenderSystem.getDevice().createCommandEncoder();

        // Preserve all world, Display Entity and post-effect pixels before the
        // main target becomes the full-window editor canvas.
        encoder.copyTextureToTexture(mainColor, copyColor, 0, 0, 0, 0, 0, main.width, main.height);
        encoder.clearColorTexture(mainColor, WORKSPACE_COLOR);
        blit((GlTexture) copyColor, (GlTexture) mainColor, main.width, main.height, viewport);
    }

    private static void ensureResources(int width, int height) {
        if (worldCopy == null) {
            worldCopy = new TextureTarget("ArcMenu editor world frame", width, height, false);
        } else if (worldCopy.width != width || worldCopy.height != height) {
            worldCopy.resize(width, height);
        }
        if (readFramebuffer == 0) readFramebuffer = GlStateManager.glGenFramebuffers();
        if (drawFramebuffer == 0) drawFramebuffer = GlStateManager.glGenFramebuffers();
    }

    private static void blit(GlTexture source, GlTexture destination, int sourceWidth, int sourceHeight,
                             EditorViewport.Physical viewport) {
        int previousRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
        try {
            GlStateManager._disableScissorTest();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(
                    GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, source.glId(), 0);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glFramebufferTexture2D(
                    GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, destination.glId(), 0);
            GlStateManager._glBlitFrameBuffer(
                    0, 0, sourceWidth, sourceHeight,
                    viewport.x(), viewport.y(), viewport.x() + viewport.width(), viewport.y() + viewport.height(),
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            // Do not retain texture references in our FBOs; Minecraft may
            // replace the main target on a window resize.
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._glFramebufferTexture2D(
                    GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glFramebufferTexture2D(
                    GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }

    public static void release() {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(EditorWorldCompositor::releaseNow);
            return;
        }
        releaseNow();
    }

    private static void releaseNow() {
        if (worldCopy != null) {
            worldCopy.destroyBuffers();
            worldCopy = null;
        }
        if (readFramebuffer != 0) {
            GlStateManager._glDeleteFramebuffers(readFramebuffer);
            readFramebuffer = 0;
        }
        if (drawFramebuffer != 0) {
            GlStateManager._glDeleteFramebuffers(drawFramebuffer);
            drawFramebuffer = 0;
        }
    }
}

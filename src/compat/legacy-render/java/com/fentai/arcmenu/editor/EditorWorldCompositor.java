package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Framebuffer compositor for the OpenGL-only 1.21 renderers. */
public final class EditorWorldCompositor {
    private static int copyTexture, readFramebuffer, copyFramebuffer;
    private static int width, height;

    private EditorWorldCompositor() {}

    public static void initialize() {}

    public static void compose() {
        var minecraft = Minecraft.getInstance();
        var view = EditorViewport.physical(minecraft.getWindow());
        if (view == null || !(minecraft.screen instanceof EditorScreen)) return;
        RenderSystem.assertOnRenderThread();
        var main = minecraft.getMainRenderTarget();
        if (main.width < 1 || main.height < 1) return;
        int texture = ((com.mojang.blaze3d.opengl.GlTexture) main.getColorTexture()).glId();
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        float[] clearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        try {
            if (copyTexture == 0) {
                copyTexture = GL11.glGenTextures();
                readFramebuffer = GL30.glGenFramebuffers();
                copyFramebuffer = GL30.glGenFramebuffers();
            }
            if (width != main.width || height != main.height) {
                width = main.width;
                height = main.height;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTexture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, copyTexture, 0);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, readFramebuffer);
            GL11.glClearColor(16 / 255f, 19 / 255f, 26 / 255f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyFramebuffer);
            GL30.glBlitFramebuffer(0, 0, width, height,
                    view.x(), view.y(), view.x() + view.width(), view.y() + view.height(),
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, readFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    public static void release() {
        if (!RenderSystem.isOnRenderThread()) {
            Minecraft.getInstance().execute(EditorWorldCompositor::release);
            return;
        }
        if (copyTexture != 0) GL11.glDeleteTextures(copyTexture);
        if (readFramebuffer != 0) GL30.glDeleteFramebuffers(readFramebuffer);
        if (copyFramebuffer != 0) GL30.glDeleteFramebuffers(copyFramebuffer);
        copyTexture = readFramebuffer = copyFramebuffer = width = height = 0;
    }
}

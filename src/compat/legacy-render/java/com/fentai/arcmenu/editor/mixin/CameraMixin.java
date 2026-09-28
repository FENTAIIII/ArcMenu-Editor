package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorViewport;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
abstract class CameraMixin {
    @Redirect(method = "getProjectionMatrix", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I"))
    private int arcmenu$projectionWidth(Window window) {
        var viewport = EditorViewport.physical(window);
        return viewport == null ? window.getWidth() : viewport.width();
    }

    @Redirect(method = "getProjectionMatrix", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I"))
    private int arcmenu$projectionHeight(Window window) {
        var viewport = EditorViewport.physical(window);
        return viewport == null ? window.getHeight() : viewport.height();
    }
}

package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorViewport;
import com.fentai.arcmenu.editor.EffectEditorScreen;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.joml.Matrix4f;

@Mixin(GameRenderer.class)
abstract class CameraMixin {
    @Inject(method = "getProjectionMatrix", at = @At("RETURN"))
    private void arcmenu$recordEffectFov(CallbackInfoReturnable<Matrix4f> result) {
        var client = Minecraft.getInstance();
        if (client.screen instanceof EffectEditorScreen) {
            EditorViewport.verticalFov(Math.toDegrees(2 * Math.atan(1.0 / result.getReturnValue().m11())));
        }
    }

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

package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorViewport;
import com.fentai.arcmenu.editor.EffectEditorScreen;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Inject(method = "update", at = @At("RETURN"))
    private void arcmenu$recordEffectFov(CallbackInfo info) {
        var client = Minecraft.getInstance();
        if (client.screen instanceof EffectEditorScreen) EditorViewport.verticalFov(((Camera) (Object) this).getFov());
    }

    @Redirect(method = {"update", "createProjectionMatrixForCulling"},
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I"))
    private int arcmenu$projectionWidth(Window window) {
        EditorViewport.Physical viewport = EditorViewport.physical(window);
        return viewport == null ? window.getWidth() : viewport.width();
    }

    @Redirect(method = {"update", "createProjectionMatrixForCulling"},
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I"))
    private int arcmenu$projectionHeight(Window window) {
        EditorViewport.Physical viewport = EditorViewport.physical(window);
        return viewport == null ? window.getHeight() : viewport.height();
    }
}

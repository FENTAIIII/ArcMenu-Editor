package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionItemLighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
abstract class VisionItemLightingMixin {
    @Inject(method = "submitInner(Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V", at = @At("HEAD"))
    private void arcmenu$inventoryNormals(ItemDisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                         int light, float progress, CallbackInfo info) {
        VisionItemLighting.apply(state, poseStack);
    }
}

package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionItemLighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.LayerRenderState.class)
abstract class VisionItemLayerLightingMixin {
    @Inject(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/model/ItemTransform;apply(ZLcom/mojang/blaze3d/vertex/PoseStack$Pose;)V",
            shift = At.Shift.AFTER))
    private void arcmenu$layerNormals(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                     int overlay, int outline, CallbackInfo info) {
        VisionItemLighting.applyLayer(poseStack.last());
    }
}

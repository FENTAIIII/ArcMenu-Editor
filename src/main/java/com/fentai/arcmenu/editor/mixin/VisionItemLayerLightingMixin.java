package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionItemLighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.LayerRenderState.class)
abstract class VisionItemLayerLightingMixin {
    @Inject(method = "applyTransform", at = @At("RETURN"))
    private void arcmenu$layerNormals(PoseStack.Pose pose, CallbackInfo info) {
        VisionItemLighting.applyLayer(pose);
    }
}

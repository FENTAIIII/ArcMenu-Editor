package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionSceneClient;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
abstract class VisionLevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void arcmenu$excludeVisionMenu(Entity entity, Frustum frustum, double x, double y, double z,
                                          float partialTick, long fadeDuration, CallbackInfoReturnable<Boolean> info) {
        if (VisionSceneClient.owns(entity)) info.setReturnValue(false);
    }
}

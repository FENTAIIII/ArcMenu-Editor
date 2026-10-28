package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionCursorClient;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class VisionHudMixin {
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void arcmenu$hideCrosshairDuringVision(CallbackInfo info) {
        if (VisionCursorClient.active()) info.cancel();
    }
}

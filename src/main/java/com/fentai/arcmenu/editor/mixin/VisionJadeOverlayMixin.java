package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionCursorClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Jade is optional; its world tooltip must not cover an active Vision menu. */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.OverlayRenderer", remap = false)
abstract class VisionJadeOverlayMixin {
    @Shadow public static boolean shown;

    @Inject(method = "renderOverlay478757", at = @At("HEAD"), cancellable = true)
    private static void arcmenu$hideJadeDuringVision(CallbackInfo info) {
        if (VisionCursorClient.active()) {
            shown = false;
            info.cancel();
        }
    }
}

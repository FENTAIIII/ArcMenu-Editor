package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionCursorClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class VisionMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void arcmenu$moveVisionCursor(double elapsed, CallbackInfo info) {
        if (VisionCursorClient.move(accumulatedDX, accumulatedDY)) info.cancel();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void arcmenu$clickVisionCursor(long window, MouseButtonInfo button, int action, CallbackInfo info) {
        if (VisionCursorClient.controlling()) {
            VisionCursorClient.move(accumulatedDX, accumulatedDY);
            accumulatedDX = 0;
            accumulatedDY = 0;
        }
        if (VisionCursorClient.button(button.button(), action, button.modifiers())) info.cancel();
    }
}

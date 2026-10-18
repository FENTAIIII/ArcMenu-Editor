package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionCursorClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class VisionKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void arcmenu$closeVisionCursor(long window, int action, KeyEvent event, CallbackInfo info) {
        if (VisionCursorClient.key(event, action)) info.cancel();
    }
}

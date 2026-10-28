package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionTextClient;
import com.fentai.arcmenu.editor.VisionTextInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class VisionTextKeyboardMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void arcmenu$typeVisionText(long window, CharacterEvent event, CallbackInfo info) {
        if (window == Minecraft.getInstance().getWindow().handle() && VisionTextClient.character(event)) info.cancel();
    }

    @Inject(method = "textEditing", at = @At("HEAD"))
    private void arcmenu$composeVisionText(long window, PreeditEvent event, CallbackInfo info) {
        if (window == Minecraft.getInstance().getWindow().handle()) VisionTextInput.preedit(event);
    }
}

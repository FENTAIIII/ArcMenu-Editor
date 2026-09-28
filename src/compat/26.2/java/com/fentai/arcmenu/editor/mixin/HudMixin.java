package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void arcmenu$hideVanillaHudInEditor(CallbackInfo info) {
        if (Minecraft.getInstance().gui.screen() instanceof EditorScreen) info.cancel();
    }
}

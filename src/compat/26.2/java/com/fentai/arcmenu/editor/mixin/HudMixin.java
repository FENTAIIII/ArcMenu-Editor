package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorScreen;
import com.fentai.arcmenu.editor.VisionCursorClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void arcmenu$hideVanillaHudInEditor(CallbackInfo info) {
        if (EditorScreen.isEditor(Minecraft.getInstance().gui.screen())) info.cancel();
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void arcmenu$drawVisionCursor(GuiGraphicsExtractor graphics, DeltaTracker tracker, CallbackInfo info) {
        VisionCursorClient.render(graphics);
    }
}

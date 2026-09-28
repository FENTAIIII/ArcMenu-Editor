package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorScreen;
import com.fentai.arcmenu.editor.EditorWorldCompositor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void arcmenu$composeEditorWorld(DeltaTracker tracker, boolean advanceGameTime, CallbackInfo info) {
        EditorWorldCompositor.compose();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void arcmenu$hideVanillaHudInEditor(Gui gui, GuiGraphics graphics, DeltaTracker tracker) {
        if (!(Minecraft.getInstance().screen instanceof EditorScreen)) gui.render(graphics, tracker);
    }
}

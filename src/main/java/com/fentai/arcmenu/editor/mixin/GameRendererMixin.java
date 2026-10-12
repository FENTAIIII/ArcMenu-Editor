package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorScreen;
import com.fentai.arcmenu.editor.EditorWorldCompositor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    /**
     * World rendering and post-processing have finished here, while the GUI has
     * not been drawn yet. Preserve that complete frame and composite it into
     * the editor's 16:9 opening before Minecraft adds the editor widgets.
     */
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void arcmenu$composeEditorWorld(DeltaTracker tracker, boolean advanceGameTime, CallbackInfo info) {
        EditorWorldCompositor.compose();
    }

    /** The editor viewport is a clean camera feed; the editor draws its own UI. */
    @Redirect(
            method = "extractGui",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
            )
    )
    private void arcmenu$hideVanillaHudInEditor(Gui gui, GuiGraphicsExtractor graphics, DeltaTracker tracker) {
        if (!(EditorScreen.isEditor(Minecraft.getInstance().screen))) {
            gui.extractRenderState(graphics, tracker);
        }
    }
}

package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorLayout;
import com.fentai.arcmenu.editor.EditorScreen;
import com.fentai.arcmenu.editor.VisionSceneClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native editor cursor and widgets keep their input; only its preview shares Vision rendering. */
@Mixin(EditorScreen.class)
abstract class VisionEditorScreenMixin {
    @Shadow private EditorLayout.Layout currentLayout;

    @Inject(method = "drawViewport", at = @At("HEAD"))
    private void arcmenu$drawIsolatedPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo info) {
        VisionSceneClient.extractPreview(graphics, currentLayout.viewport());
    }
}

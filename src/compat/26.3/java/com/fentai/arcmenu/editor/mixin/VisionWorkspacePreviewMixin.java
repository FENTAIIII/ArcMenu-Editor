package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EditorLayout;
import com.fentai.arcmenu.editor.VisionSceneClient;
import com.fentai.arcmenu.editor.WorkspacePreviewScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorkspacePreviewScreen.class)
abstract class VisionWorkspacePreviewMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void arcmenu$drawIsolatedPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        var screen = (WorkspacePreviewScreen) (Object) this;
        VisionSceneClient.extractPreview(graphics, EditorLayout.fit16By9(new EditorLayout.Rect(0, 0, screen.width, screen.height)));
    }
}

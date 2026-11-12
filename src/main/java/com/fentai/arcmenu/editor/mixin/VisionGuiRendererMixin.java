package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.VisionSceneRenderState;
import com.fentai.arcmenu.editor.VisionSceneRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(GuiRenderer.class)
abstract class VisionGuiRendererMixin
{
    @Shadow
    @Final
    @Mutable
    private Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> pictureInPictureRenderers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arcmenu$registerMenuRenderer(CallbackInfo info)
    {
        var renderers = new HashMap<>(pictureInPictureRenderers);
        renderers.put(VisionSceneRenderState.class,
                new VisionSceneRenderer(Minecraft.getInstance().getEntityRenderDispatcher()));
        pictureInPictureRenderers = Map.copyOf(renderers);
    }
}

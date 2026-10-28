package com.fentai.arcmenu.editor.mixin;

import com.fentai.arcmenu.editor.EffectAudioAssets;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pack replacements invalidate decoded audio. */
@Mixin(SoundManager.class)
abstract class EffectAudioReloadMixin {
    @Inject(method = {"apply", "reload"}, at = @At("RETURN"))
    private void arcmenu$invalidateEffectAudio(CallbackInfo info) {
        EffectAudioAssets.INSTANCE.invalidate();
    }
}

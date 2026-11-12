package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol.ImageSnapshot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Uses pack image textures and the first generated GIF frame without decoding GIFs locally. */
final class EditorImagePreview
{
    static boolean draw(GuiGraphicsExtractor graphics, ImageSnapshot image, EditorLayout.Rect box)
    {
        Identifier texture = Identifier.tryParse("arcmenu:" + EditorAssetLibrary.texturePath(image.path()));
        if (texture == null || image.width() <= 0 || image.height() <= 0
                || Minecraft.getInstance().getResourceManager().getResource(texture).isEmpty())
        {
            return false;
        }
        EditorLayout.Rect fit = fit(image.width(), image.height(), box);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, fit.x(), fit.y(), 0f, 0f, fit.width(), fit.height(),
                image.width(), image.height(), image.width(), image.height());
        return true;
    }

    static EditorLayout.Rect fit(int width, int height, EditorLayout.Rect box)
    {
        double scale = Math.min((double) box.width() / Math.max(1, width), (double) box.height() / Math.max(1, height));
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        return new EditorLayout.Rect(box.x() + (box.width() - scaledWidth) / 2,
                box.y() + (box.height() - scaledHeight) / 2, scaledWidth, scaledHeight);
    }
}

package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol.ImageSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;

/**
 * Draws pack images from their glyph atlas cells, or from a texture of their own in packs built
 * before atlases. GIFs show their first generated frame without decoding the GIF locally.
 */
final class EditorImagePreview
{
    private static final Identifier ATLAS_PREVIEWS = Identifier.tryParse("arcmenu:atlas/previews.json");

    /** One atlas cell and where it lies within the full image, in source pixels. */
    private record AtlasPiece(Identifier texture, int sheetWidth, int sheetHeight, int u, int v, int width,
            int height, int x, int y)
    {}

    /** Every resource reload opens new pack instances, so the pack identifies the parsed index. */
    private static PackResources previewPack;
    private static Map<String, List<AtlasPiece>> previews = Map.of();

    static boolean draw(GuiGraphicsExtractor graphics, ImageSnapshot image, EditorLayout.Rect box)
    {
        if (image.width() <= 0 || image.height() <= 0)
        {
            return false;
        }
        EditorLayout.Rect fit = fit(image.width(), image.height(), box);
        List<AtlasPiece> pieces = atlasPreviews().get("/" + EditorAssetLibrary.relative(image.path()));
        if (pieces != null && !pieces.isEmpty())
        {
            drawPieces(graphics, image, fit, pieces);
            return true;
        }
        Identifier texture = Identifier.tryParse("arcmenu:" + EditorAssetLibrary.texturePath(image.path()));
        if (texture == null || Minecraft.getInstance().getResourceManager().getResource(texture).isEmpty())
        {
            return false;
        }
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

    /** Neighbouring pieces round their shared edge to the same screen pixel, leaving no seams. */
    private static void drawPieces(GuiGraphicsExtractor graphics, ImageSnapshot image, EditorLayout.Rect fit,
            List<AtlasPiece> pieces)
    {
        for (AtlasPiece piece : pieces)
        {
            int left = fit.x() + scaled(piece.x(), fit.width(), image.width());
            int top = fit.y() + scaled(piece.y(), fit.height(), image.height());
            int right = fit.x() + scaled(piece.x() + piece.width(), fit.width(), image.width());
            int bottom = fit.y() + scaled(piece.y() + piece.height(), fit.height(), image.height());
            if (right <= left || bottom <= top)
            {
                continue;
            }
            graphics.blit(RenderPipelines.GUI_TEXTURED, piece.texture(), left, top, piece.u(), piece.v(),
                    right - left, bottom - top, piece.width(), piece.height(), piece.sheetWidth(),
                    piece.sheetHeight());
        }
    }

    private static int scaled(int pixel, int size, int imageSize)
    {
        return (int) Math.round((double) pixel * size / imageSize);
    }

    private static Map<String, List<AtlasPiece>> atlasPreviews()
    {
        Optional<Resource> resource = ATLAS_PREVIEWS == null ? Optional.empty()
                : Minecraft.getInstance().getResourceManager().getResource(ATLAS_PREVIEWS);
        if (resource.isEmpty())
        {
            previewPack = null;
            previews = Map.of();
            return previews;
        }
        if (resource.get().source() != previewPack)
        {
            previewPack = resource.get().source();
            previews = readPreviews(resource.get());
        }
        return previews;
    }

    private static Map<String, List<AtlasPiece>> readPreviews(Resource resource)
    {
        var result = new HashMap<String, List<AtlasPiece>>();
        try (Reader reader = resource.openAsReader())
        {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> image : root.entrySet())
            {
                var pieces = new ArrayList<AtlasPiece>();
                for (JsonElement element : image.getValue().getAsJsonArray())
                {
                    JsonArray cell = element.getAsJsonArray();
                    Identifier texture = Identifier.tryParse(cell.get(0).getAsString());
                    if (texture == null)
                    {
                        continue;
                    }
                    pieces.add(new AtlasPiece(texture, cell.get(1).getAsInt(), cell.get(2).getAsInt(),
                            cell.get(3).getAsInt(), cell.get(4).getAsInt(), cell.get(5).getAsInt(),
                            cell.get(6).getAsInt(), cell.get(7).getAsInt(), cell.get(8).getAsInt()));
                }
                result.put(image.getKey(), List.copyOf(pieces));
            }
        }
        catch (IOException | RuntimeException ignored)
        {
            // A damaged index only loses previews; the cards fall back to the unavailable label.
            return Map.of();
        }
        return result;
    }
}

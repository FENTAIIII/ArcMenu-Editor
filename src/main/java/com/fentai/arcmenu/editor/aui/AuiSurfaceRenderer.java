package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.layout.Position;
import com.fentai.arcmenu.editor.aui.internal.render.AABB;
import com.fentai.arcmenu.editor.aui.internal.spi.AuiRenderService;
import com.fentai.arcmenu.editor.aui.internal.spi.AuiRenderService.RenderStateScope;
import com.fentai.arcmenu.editor.aui.internal.spi.FboHandle;
import com.fentai.arcmenu.editor.aui.internal.spi.MeshBuilder;
import com.fentai.arcmenu.editor.aui.internal.spi.MeshMode;
import com.fentai.arcmenu.editor.aui.internal.spi.RenderHandle;
import com.fentai.arcmenu.editor.aui.internal.style.Text;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** AUI's paint SPI targets a retained image, independent of Minecraft's GPU API. */
final class AuiSurfaceRenderer implements InvocationHandler
{
    private final Map<Object, SurfaceTexture> textures = new HashMap<>();
    private BufferedImage target;
    private Graphics2D graphics;
    private Shape clip;
    private double scale = 1;
    private boolean depth;
    private boolean depthWrite;
    private boolean cull;
    private final Matrix4f projection = new Matrix4f();
    private final AuiRenderService service = (AuiRenderService) Proxy
            .newProxyInstance(AuiRenderService.class.getClassLoader(), new Class<?>[] { AuiRenderService.class }, this);

    AuiRenderService service()
    {
        return service;
    }

    BufferedImage image()
    {
        return target;
    }

    void begin(int width, int height, double pixelScale)
    {
        if (target == null || target.getWidth() != width || target.getHeight() != height)
        {
            target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }
        if (graphics != null)
        {
            graphics.dispose();
        }
        graphics = target.createGraphics();
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        clip = null;
        scale = pixelScale;
    }

    void end()
    {
        if (graphics != null)
        {
            graphics.dispose();
            graphics = null;
        }
    }

    void clip(AABB rect)
    {
        clip = rect == null ? null
                : new Rectangle2D.Double(rect.x() * scale, rect.y() * scale, rect.width() * scale,
                        rect.height() * scale);
        if (graphics != null)
        {
            graphics.setClip(clip);
        }
    }

    void defaultText(Matrix4f pose, Text text, String content, Position position)
    {
        if (graphics == null)
        {
            return;
        }
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.transform(affine(pose));
        canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16).deriveFont((float) text.renderedFontSize()));
        canvas.setColor(new Color(text.color.getValue(), true));
        canvas.drawString(content, (float) position.x, (float) (position.y + canvas.getFontMetrics().getAscent()));
        canvas.dispose();
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] raw)
    {
        Object[] arguments = raw == null ? new Object[0] : raw;
        return switch (method.getName())
        {
        case "beginMesh" -> MeshBuilder.of(new Mesh((MeshMode) arguments[0]));
        case "emitVertex" ->
        {
            ((Mesh) arguments[0]).add((Matrix4f) arguments[1], (float) arguments[2], (float) arguments[3],
                    ((int) arguments[8] << 24) | ((int) arguments[5] << 16) | ((int) arguments[6] << 8)
                            | (int) arguments[7]);
            yield null;
        }
        case "emitVertexUV" -> null;
        case "submitMesh" ->
        {
            paintMesh((Mesh) arguments[0]);
            yield null;
        }
        case "beginTextureBatch" -> new TextureBatch(((RenderHandle) arguments[0]).as());
        case "emitTextureQuad" ->
        {
            TextureBatch batch = (TextureBatch) arguments[0];
            batch.quads.add(new Quad(new Matrix4f((Matrix4f) arguments[1]), (float) arguments[2], (float) arguments[3],
                    (float) arguments[4], (float) arguments[5], (float) arguments[6], (float) arguments[7],
                    (float) arguments[8], (float) arguments[9],
                    arguments.length > 10 ? (int) arguments[10] : 0xffffffff));
            yield null;
        }
        case "flushTextureBatch" ->
        {
            paintTextures((TextureBatch) arguments[0]);
            yield null;
        }
        case "createDynamicTexture" ->
        {
            SurfaceTexture texture = new SurfaceTexture(copy((NativeImage) arguments[1]));
            textures.put(normalize(arguments[0]), texture);
            yield texture;
        }
        case "uploadTextureRegion" ->
        {
            SurfaceTexture texture = (SurfaceTexture) arguments[0];
            NativeImage image = (NativeImage) arguments[1];
            int x = (int) arguments[2];
            int y = (int) arguments[3];
            int regionWidth = (int) arguments[4];
            int regionHeight = (int) arguments[5];
            for (int pixelY = y; pixelY < y + regionHeight; pixelY++)
            {
                for (int pixelX = x; pixelX < x + regionWidth; pixelX++)
                {
                    texture.image.setRGB(pixelX, pixelY, AuiPixels.read(image, pixelX, pixelY));
                }
            }
            texture.tinted.clear();
            yield null;
        }
        case "setImagePixel" ->
        {
            AuiPixels.write((NativeImage) arguments[0], (int) arguments[1], (int) arguments[2],
                    abgr((int) arguments[3]));
            yield null;
        }
        case "writeImagePixels" ->
        {
            NativeImage image = (NativeImage) arguments[0];
            int x = (int) arguments[1];
            int y = (int) arguments[2];
            int imageWidth = (int) arguments[3];
            int imageHeight = (int) arguments[4];
            int[] pixels = (int[]) arguments[5];
            for (int pixelY = 0, i = 0; pixelY < imageHeight; pixelY++)
            {
                for (int pixelX = 0; pixelX < imageWidth; pixelX++)
                {
                    AuiPixels.write(image, x + pixelX, y + pixelY, abgr(pixels[i++]));
                }
            }
            yield null;
        }
        case "registerTexture" ->
        {
            textures.put(normalize(arguments[1]), (SurfaceTexture) arguments[0]);
            yield null;
        }
        case "releaseTexture" ->
        {
            textures.remove(normalize(arguments[0]));
            yield null;
        }
        case "closeTexture" ->
        {
            textures.values().removeIf(texture -> texture == arguments[0]);
            yield null;
        }
        case "createOffscreenTarget" ->
            FboHandle.of(new BufferedImage((int) arguments[0], (int) arguments[1], BufferedImage.TYPE_INT_ARGB),
                    (int) arguments[0], (int) arguments[1]);
        case "getMainRenderTarget" ->
            target == null ? null : FboHandle.of(target, target.getWidth(), target.getHeight());
        case "getProjectionMatrix" -> new Matrix4f(projection);
        case "setProjectionMatrix" ->
        {
            projection.set((Matrix4f) arguments[0]);
            yield null;
        }
        case "isOnRenderThread" -> true;
        case "recordRenderCall" ->
        {
            ((Runnable) arguments[0]).run();
            yield null;
        }
        case "enableDepthTest" ->
        {
            depth = true;
            yield null;
        }
        case "disableDepthTest" ->
        {
            depth = false;
            yield null;
        }
        case "isDepthTestEnabled" -> depth;
        case "setDepthMask" ->
        {
            depthWrite = (boolean) arguments[0];
            yield null;
        }
        case "isDepthMaskEnabled" -> depthWrite;
        case "enableCull" ->
        {
            cull = true;
            yield null;
        }
        case "disableCull" ->
        {
            cull = false;
            yield null;
        }
        case "isCullEnabled" -> cull;
        case "supportsStencil", "currentTargetHasStencil" -> false;
        case "pushFilterRenderState" -> RenderStateScope.NOOP;
        case "getGLVersionString" -> "AUI retained surface";
        case "toString" -> "ArcMenu AUI retained renderer";
        case "hashCode" -> System.identityHashCode(this);
        case "equals" -> ignored == arguments[0];
        default -> defaultValue(method.getReturnType());
        };
    }

    private void paintMesh(Mesh mesh)
    {
        if (graphics == null)
        {
            return;
        }
        int stride = mesh.mode == MeshMode.QUADS ? 4 : 3;
        Path2D.Float path = new Path2D.Float();
        int last = 0;
        boolean pending = false;
        for (int i = 0; i + stride <= mesh.vertices.size(); i += stride)
        {
            Vertex first = mesh.vertices.get(i);
            int color = first.color;
            if (pending && color != last)
            {
                graphics.setColor(new Color(last, true));
                graphics.fill(path);
                path.reset();
                pending = false;
            }
            path.moveTo(first.x, first.y);
            for (int j = 1; j < stride; j++)
            {
                Vertex vertex = mesh.vertices.get(i + j);
                path.lineTo(vertex.x, vertex.y);
            }
            path.closePath();
            last = color;
            pending = true;
        }
        if (pending)
        {
            graphics.setColor(new Color(last, true));
            graphics.fill(path);
        }
    }

    private void paintTextures(TextureBatch batch)
    {
        if (graphics == null || batch == null)
        {
            return;
        }
        SurfaceTexture texture = textures.get(normalize(batch.key));
        if (texture == null)
        {
            return;
        }
        for (Quad quad : batch.quads)
        {
            BufferedImage image = texture.image;
            int sx0 = Math.round(quad.u0 * image.getWidth());
            int sy0 = Math.round(quad.v0 * image.getHeight());
            int sx1 = Math.round(quad.u1 * image.getWidth());
            int sy1 = Math.round(quad.v1 * image.getHeight());
            if (sx1 <= sx0 || sy1 <= sy0)
            {
                continue;
            }
            int left = Math.max(0, sx0);
            int top = Math.max(0, sy0);
            int right = Math.min(image.getWidth(), sx1);
            int bottom = Math.min(image.getHeight(), sy1);
            if (right <= left || bottom <= top)
            {
                continue;
            }
            BufferedImage region = quad.tint == 0xffffffff ? image.getSubimage(left, top, right - left, bottom - top)
                    : texture.tintedRegion(quad.tint, left, top, right - left, bottom - top);
            // Font quads are already aligned to physical pixels. Rounding CSS coordinates
            // here would stretch each glyph differently at fractional document scales.
            AffineTransform placement = AffineTransform.getTranslateInstance(quad.x, quad.y);
            placement.scale((double) quad.w / (sx1 - sx0), (double) quad.h / (sy1 - sy0));
            placement.translate(left - sx0, top - sy0);
            Graphics2D canvas = (Graphics2D) graphics.create();
            try
            {
                canvas.transform(affine(quad.pose));
                canvas.drawImage(region, placement, null);
            }
            finally
            {
                canvas.dispose();
            }
        }
    }

    private static Object normalize(Object key)
    {
        String value = String.valueOf(key);
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static AffineTransform affine(Matrix4f matrix)
    {
        return new AffineTransform(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m30(), matrix.m31());
    }

    private static BufferedImage copy(NativeImage image)
    {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, image.getWidth(), image.getHeight(), AuiPixels.pixels(image), 0, image.getWidth());
        return result;
    }

    private static int abgr(int value)
    {
        return (value & 0xff00ff00) | ((value & 255) << 16) | ((value >>> 16) & 255);
    }

    static Object defaultValue(Class<?> type)
    {
        if (type == boolean.class)
        {
            return false;
        }
        if (type == int.class)
        {
            return 0;
        }
        if (type == long.class)
        {
            return 0L;
        }
        if (type == float.class)
        {
            return 0f;
        }
        if (type == double.class)
        {
            return 0d;
        }
        return null;
    }

    private record Vertex(float x, float y, int color)
    {}

    private static final class Mesh
    {
        final MeshMode mode;
        final ArrayList<Vertex> vertices = new ArrayList<>();

        Mesh(MeshMode mode)
        {
            this.mode = mode;
        }

        void add(Matrix4f pose, float x, float y, int color)
        {
            Vector4f transformed = pose.transform(new Vector4f(x, y, 0, 1));
            vertices.add(new Vertex(transformed.x, transformed.y, color));
        }
    }

    private record Quad(Matrix4f pose, float x, float y, float w, float h, float u0, float v0, float u1, float v1,
            int tint)
    {}

    private static final class TextureBatch
    {
        final Object key;
        final ArrayList<Quad> quads = new ArrayList<>();

        TextureBatch(Object key)
        {
            this.key = key;
        }
    }

    static final class SurfaceTexture
    {
        final BufferedImage image;
        final Map<TintRegion, BufferedImage> tinted = new LinkedHashMap<>(128, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TintRegion, BufferedImage> entry)
            {
                return size() > 2048;
            }
        };

        SurfaceTexture(BufferedImage image)
        {
            this.image = image;
        }

        BufferedImage tinted(int color)
        {
            return color == 0xffffffff ? image : tintedRegion(color, 0, 0, image.getWidth(), image.getHeight());
        }

        BufferedImage tintedRegion(int color, int x, int y, int width, int height)
        {
            return tinted.computeIfAbsent(new TintRegion(color, x, y, width, height), region ->
            {
                BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                int[] pixels = image.getRGB(x, y, width, height, null, 0, width);
                int key = region.color;
                int a = key >>> 24;
                int r = (key >> 16) & 255;
                int g = (key >> 8) & 255;
                int b = key & 255;
                for (int i = 0; i < pixels.length; i++)
                {
                    int pixel = pixels[i];
                    pixels[i] = ((pixel >>> 24) * a / 255 << 24) | (((pixel >> 16) & 255) * r / 255 << 16)
                            | (((pixel >> 8) & 255) * g / 255 << 8) | ((pixel & 255) * b / 255);
                }
                output.setRGB(0, 0, width, height, pixels, 0, width);
                return output;
            });
        }
    }

    private record TintRegion(int color, int x, int y, int width, int height)
    {}
}

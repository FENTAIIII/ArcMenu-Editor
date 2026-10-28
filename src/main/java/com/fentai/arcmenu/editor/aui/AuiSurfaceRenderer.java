package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.spi.*;
import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fentai.arcmenu.editor.aui.internal.layout.Position;
import com.fentai.arcmenu.editor.aui.internal.render.AABB;
import com.fentai.arcmenu.editor.aui.internal.style.Text;
import java.awt.geom.Rectangle2D;

/** AUI's paint SPI targets a retained image, independent of Minecraft's GPU API. */
final class AuiSurfaceRenderer implements InvocationHandler {
    private final Map<Object, SurfaceTexture> textures = new HashMap<>();
    private BufferedImage target;
    private Graphics2D graphics;
    private Shape clip;
    private double scale = 1;
    private boolean depth, depthWrite, cull;
    private final Matrix4f projection = new Matrix4f();
    private final AuiRenderService service = (AuiRenderService) Proxy.newProxyInstance(
            AuiRenderService.class.getClassLoader(), new Class<?>[]{AuiRenderService.class}, this);

    AuiRenderService service() { return service; }
    BufferedImage image() { return target; }
    void begin(int width, int height, double pixelScale) {
        if (target == null || target.getWidth() != width || target.getHeight() != height)
            target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (graphics != null) graphics.dispose();
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
    void end() { if (graphics != null) { graphics.dispose(); graphics = null; } }
    void clip(AABB rect) {
        clip = rect == null ? null : new Rectangle2D.Double(
                rect.x() * scale, rect.y() * scale, rect.width() * scale, rect.height() * scale);
        if (graphics != null) graphics.setClip(clip);
    }
    void defaultText(Matrix4f pose, Text text,
                     String content, Position p) {
        if (graphics == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.transform(affine(pose));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16).deriveFont((float) text.renderedFontSize()));
        g.setColor(new Color(text.color.getValue(), true));
        g.drawString(content, (float) p.x, (float) (p.y + g.getFontMetrics().getAscent()));
        g.dispose();
    }

    @Override public Object invoke(Object ignored, Method method, Object[] raw) {
        Object[] a = raw == null ? new Object[0] : raw;
        return switch (method.getName()) {
            case "beginMesh" -> MeshBuilder.of(new Mesh((MeshMode) a[0]));
            case "emitVertex" -> { ((Mesh) a[0]).add((Matrix4f) a[1], (float) a[2], (float) a[3],
                    ((int) a[8] << 24) | ((int) a[5] << 16) | ((int) a[6] << 8) | (int) a[7]); yield null; }
            case "emitVertexUV" -> null;
            case "submitMesh" -> { paintMesh((Mesh) a[0]); yield null; }
            case "beginTextureBatch" -> new TextureBatch(((RenderHandle) a[0]).as());
            case "emitTextureQuad" -> { TextureBatch b = (TextureBatch) a[0];
                b.quads.add(new Quad(new Matrix4f((Matrix4f) a[1]), (float) a[2], (float) a[3],
                        (float) a[4], (float) a[5], (float) a[6], (float) a[7], (float) a[8],
                        (float) a[9], a.length > 10 ? (int) a[10] : 0xffffffff)); yield null; }
            case "flushTextureBatch" -> { paintTextures((TextureBatch) a[0]); yield null; }
            case "createDynamicTexture" -> { SurfaceTexture t = new SurfaceTexture(copy((NativeImage) a[1]));
                textures.put(normalize(a[0]), t); yield t; }
            case "uploadTextureRegion" -> { SurfaceTexture t = (SurfaceTexture) a[0];
                NativeImage image = (NativeImage) a[1]; int x=(int)a[2], y=(int)a[3], w=(int)a[4], h=(int)a[5];
                for(int py=y;py<y+h;py++)for(int px=x;px<x+w;px++)t.image.setRGB(px,py,AuiPixels.read(image,px,py));
                t.tinted.clear(); yield null; }
            case "setImagePixel" -> { AuiPixels.write((NativeImage)a[0],(int)a[1],(int)a[2],abgr((int)a[3])); yield null; }
            case "writeImagePixels" -> { NativeImage image=(NativeImage)a[0]; int x=(int)a[1],y=(int)a[2],w=(int)a[3],h=(int)a[4]; int[] pixels=(int[])a[5];
                for(int py=0,i=0;py<h;py++)for(int px=0;px<w;px++)AuiPixels.write(image,x+px,y+py,abgr(pixels[i++])); yield null; }
            case "registerTexture" -> { textures.put(normalize(a[1]), (SurfaceTexture) a[0]); yield null; }
            case "releaseTexture" -> { textures.remove(normalize(a[0])); yield null; }
            case "closeTexture" -> { textures.values().removeIf(t -> t == a[0]); yield null; }
            case "createOffscreenTarget" -> FboHandle.of(new BufferedImage((int)a[0],(int)a[1],BufferedImage.TYPE_INT_ARGB),(int)a[0],(int)a[1]);
            case "getMainRenderTarget" -> target == null ? null : FboHandle.of(target,target.getWidth(),target.getHeight());
            case "getProjectionMatrix" -> new Matrix4f(projection);
            case "setProjectionMatrix" -> { projection.set((Matrix4f)a[0]); yield null; }
            case "isOnRenderThread" -> true;
            case "recordRenderCall" -> { ((Runnable)a[0]).run(); yield null; }
            case "enableDepthTest" -> { depth=true; yield null; }
            case "disableDepthTest" -> { depth=false; yield null; }
            case "isDepthTestEnabled" -> depth;
            case "setDepthMask" -> { depthWrite=(boolean)a[0]; yield null; }
            case "isDepthMaskEnabled" -> depthWrite;
            case "enableCull" -> { cull=true; yield null; }
            case "disableCull" -> { cull=false; yield null; }
            case "isCullEnabled" -> cull;
            case "supportsStencil", "currentTargetHasStencil" -> false;
            case "pushFilterRenderState" -> AuiRenderService.RenderStateScope.NOOP;
            case "getGLVersionString" -> "AUI retained surface";
            case "toString" -> "ArcMenu AUI retained renderer";
            case "hashCode" -> System.identityHashCode(this);
            case "equals" -> ignored == a[0];
            default -> defaultValue(method.getReturnType());
        };
    }

    private void paintMesh(Mesh mesh) {
        if (graphics == null) return;
        int stride=mesh.mode==MeshMode.QUADS?4:3;
        Path2D.Float path = new Path2D.Float(); int last=0; boolean pending=false;
        for(int i=0;i+stride<=mesh.vertices.size();i+=stride){
            Vertex first=mesh.vertices.get(i); int color=first.color;
            if(pending && color!=last){graphics.setColor(new Color(last,true));graphics.fill(path);path.reset();pending=false;}
            path.moveTo(first.x,first.y);
            for(int j=1;j<stride;j++){Vertex v=mesh.vertices.get(i+j);path.lineTo(v.x,v.y);}
            path.closePath();last=color;pending=true;
        }
        if(pending){graphics.setColor(new Color(last,true));graphics.fill(path);}
    }
    private void paintTextures(TextureBatch batch) {
        if(graphics==null || batch==null)return;
        SurfaceTexture texture=textures.get(normalize(batch.key));if(texture==null)return;
        for(Quad q:batch.quads){
            BufferedImage image=texture.image;
            int sx0=Math.round(q.u0*image.getWidth()),sy0=Math.round(q.v0*image.getHeight());
            int sx1=Math.round(q.u1*image.getWidth()),sy1=Math.round(q.v1*image.getHeight());
            if(sx1<=sx0||sy1<=sy0)continue;
            int left=Math.max(0,sx0),top=Math.max(0,sy0);
            int right=Math.min(image.getWidth(),sx1),bottom=Math.min(image.getHeight(),sy1);
            if(right<=left||bottom<=top)continue;
            BufferedImage region=q.tint==0xffffffff?image.getSubimage(left,top,right-left,bottom-top)
                    :texture.tintedRegion(q.tint,left,top,right-left,bottom-top);
            // Font quads are already aligned to physical pixels. Rounding CSS coordinates
            // here would stretch each glyph differently at fractional document scales.
            AffineTransform placement=AffineTransform.getTranslateInstance(q.x,q.y);
            placement.scale((double)q.w/(sx1-sx0),(double)q.h/(sy1-sy0));
            placement.translate(left-sx0,top-sy0);
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                g.transform(affine(q.pose));
                g.drawImage(region,placement,null);
            } finally {
                g.dispose();
            }
        }
    }
    private static Object normalize(Object key) { String value=String.valueOf(key);int colon=value.indexOf(':');return colon>=0?value.substring(colon+1):value; }
    private static AffineTransform affine(Matrix4f m) { return new AffineTransform(m.m00(),m.m01(),m.m10(),m.m11(),m.m30(),m.m31()); }
    private static BufferedImage copy(NativeImage image) { BufferedImage result=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0,0,image.getWidth(),image.getHeight(),AuiPixels.pixels(image),0,image.getWidth());return result; }
    private static int abgr(int value){return (value&0xff00ff00)|((value&255)<<16)|((value>>>16)&255);}
    static Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;return null;}
    private record Vertex(float x,float y,int color){}
    private static final class Mesh {final MeshMode mode;final ArrayList<Vertex> vertices=new ArrayList<>();Mesh(MeshMode mode){this.mode=mode;}
        void add(Matrix4f m,float x,float y,int c){Vector4f p=m.transform(new Vector4f(x,y,0,1));vertices.add(new Vertex(p.x,p.y,c));}}
    private record Quad(Matrix4f pose,float x,float y,float w,float h,float u0,float v0,float u1,float v1,int tint){}
    private static final class TextureBatch {final Object key;final ArrayList<Quad> quads=new ArrayList<>();TextureBatch(Object key){this.key=key;}}
    static final class SurfaceTexture {
        final BufferedImage image;
        final Map<TintRegion,BufferedImage> tinted=new LinkedHashMap<>(128,0.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<TintRegion,BufferedImage> entry){return size()>2048;}
        };
        SurfaceTexture(BufferedImage image){this.image=image;}
        BufferedImage tinted(int color){return color==0xffffffff?image:tintedRegion(color,0,0,image.getWidth(),image.getHeight());}
        BufferedImage tintedRegion(int color,int x,int y,int width,int height){return tinted.computeIfAbsent(new TintRegion(color,x,y,width,height),region->{
            BufferedImage output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            int[] pixels=image.getRGB(x,y,width,height,null,0,width);
            int key=region.color;int a=key>>>24,r=(key>>16)&255,g=(key>>8)&255,b=key&255;
            for(int i=0;i<pixels.length;i++){int p=pixels[i];pixels[i]=((p>>>24)*a/255<<24)|(((p>>16)&255)*r/255<<16)|(((p>>8)&255)*g/255<<8)|((p&255)*b/255);}
            output.setRGB(0,0,width,height,pixels,0,width);return output;});}
    }
    private record TintRegion(int color,int x,int y,int width,int height){}
}

package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.io.IOException;
import java.util.concurrent.CompletionException;

/** Downloaded preview textures are scoped to their native marketplace screen. */
final class MarketPreview implements AutoCloseable {
    private static final AtomicLong IDS=new AtomicLong();
    private static final ExecutorService DECODER=Executors.newFixedThreadPool(2,runnable->{var thread=new Thread(runnable,"ArcMenu preview decoder");thread.setDaemon(true);return thread;});
    private final AtomicLong generation=new AtomicLong();
    private CompletableFuture<Void> loading;
    private Identifier texture;
    private DynamicTexture dynamic;
    private int imageWidth,imageHeight;
    int width(){return imageWidth;}
    int height(){return imageHeight;}
    void load(byte[] bytes) throws IOException {
        if(bytes.length>8*1024*1024)throw new IOException("Marketplace preview exceeds 8 MiB");
        loadAsync(bytes);
    }
    CompletableFuture<Void> loadAsync(byte[] bytes){
        close();long version=generation.get();var result=new CompletableFuture<Void>();loading=result;
        CompletableFuture.supplyAsync(()->{
            if(version!=generation.get()||result.isCancelled())return null;
            try{
                var image=NativeImage.read(bytes);
                if(image.getWidth()>4096||image.getHeight()>4096){image.close();throw new IOException("Marketplace preview exceeds 4096 pixels");}
                return image;
            }catch(IOException error){throw new CompletionException(error);}
        },DECODER).whenComplete((image,error)->Minecraft.getInstance().execute(()->{
            if(version!=generation.get()||result.isCancelled()){if(image!=null)image.close();result.cancel(false);return;}
            if(error!=null){result.completeExceptionally(error);return;}
            if(image==null){result.cancel(false);return;}
            try{
                imageWidth=image.getWidth();imageHeight=image.getHeight();dynamic=new DynamicTexture(()->"ArcMenu marketplace preview",image);
                texture=Identifier.fromNamespaceAndPath("arcmenu_editor","market_preview/"+IDS.incrementAndGet());Minecraft.getInstance().getTextureManager().register(texture,dynamic);
                result.complete(null);
            }catch(RuntimeException failure){image.close();texture=null;dynamic=null;imageWidth=imageHeight=0;result.completeExceptionally(failure);}
        }));
        return result;
    }
    void draw(GuiGraphicsExtractor graphics,EditorLayout.Rect box){
        if(texture==null)return;MarketPreview image=this;double scale=Math.min(box.width()/(double)width(),box.height()/(double)height());int w=Math.max(1,(int)(width()*scale)),h=Math.max(1,(int)(height()*scale));var fit=new EditorLayout.Rect(box.x()+(box.width()-w)/2,box.y()+(box.height()-h)/2,w,h);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, fit.x(), fit.y(), 0f, 0f,
                fit.width(), fit.height(), image.width(), image.height(), image.width(), image.height());
    }
    @Override public void close(){generation.incrementAndGet();if(loading!=null)loading.cancel(false);loading=null;if(texture!=null)Minecraft.getInstance().getTextureManager().release(texture);texture=null;dynamic=null;imageWidth=imageHeight=0;}
}

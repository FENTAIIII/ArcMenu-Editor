package com.fentai.arcmenu.editor;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Isolated, cue-free saved-project previews are restored before an archive is uploaded. */
final class WorkspacePublication {
    private record Saved(boolean hideGui,boolean audio,int width,int height,boolean resized) {}
    record CaptureProgress(String filename,int completed,int total,boolean cancelling) {}
    private static final class CaptureJob {
        final String filename;final int total;
        volatile int completed;volatile boolean cancelled,hidden;
        CaptureJob(String filename,int total){this.filename=filename;this.total=total;}
        void check(){if(cancelled)throw new CancellationException("Package preview capture cancelled");}
    }
    private static volatile CaptureJob captureJob;
    private WorkspacePublication(){}
    static boolean capturing(){return captureJob!=null;}
    static CaptureProgress progress(){var job=captureJob;return job==null?null:new CaptureProgress(job.filename,job.completed,job.total,job.cancelled);}
    static boolean overlayVisible(){var job=captureJob;return job!=null&&!job.hidden;}
    static boolean cancelCapture(){var job=captureJob;if(job==null)return false;job.cancelled=true;return true;}
    private static boolean hudHidden(Minecraft client){return client.options.hideGui;}
    private static void hideHud(Minecraft client,boolean value){client.options.hideGui=value;}
    private static <T> CompletableFuture<T> main(Supplier<T> action){var result=new CompletableFuture<T>();Minecraft.getInstance().execute(()->{try{result.complete(action.get());}catch(Exception error){result.completeExceptionally(error);}});return result;}
    private static CompletableFuture<JsonObject> request(String action,JsonObject json){return main(()->WorkspaceClient.request(action,json)).thenCompose(f->f);}
    static CompletableFuture<JsonObject> publish(WorkspaceScreen screen,String filename,List<WorkspaceState.Project> projects){
        return capturePreviews(screen,filename,projects).thenCompose(nothing->{var args=new JsonObject();args.addProperty("filename",filename);args.addProperty("token",WorkspaceClient.MARKET.token());args.addProperty("authorUuid",WorkspaceClient.MARKET.userId());args.addProperty("authorName",WorkspaceClient.MARKET.userName());return request("publish",args);});
    }
    static CompletableFuture<JsonObject> prepare(WorkspaceScreen screen,WorkspaceState.LocalPack pack){
        return capturePreviews(screen,pack.filename(),pack.captureProjects()).thenCompose(nothing->{var args=new JsonObject();args.addProperty("filename",pack.filename());return request("pack-prepare",args);});
    }
    static CompletableFuture<JsonObject> publish(WorkspaceScreen screen,WorkspaceState.LocalPack pack){
        CompletableFuture<JsonObject> ready=pack.prepared()?CompletableFuture.completedFuture(new JsonObject()):prepare(screen,pack);
        return ready.thenCompose(nothing->{var args=new JsonObject();args.addProperty("filename",pack.filename());args.addProperty("token",WorkspaceClient.MARKET.token());args.addProperty("authorUuid",WorkspaceClient.MARKET.userId());args.addProperty("authorName",WorkspaceClient.MARKET.userName());return request("publish",args);});
    }
    static CompletableFuture<JsonObject> capturePreviews(WorkspaceScreen screen,String filename,List<WorkspaceState.Project> projects){
        if(projects.isEmpty()||projects.size()>512)return CompletableFuture.failedFuture(new IllegalArgumentException("Select between 1 and 512 projects for capture"));
        if(captureJob!=null)return CompletableFuture.failedFuture(new IllegalStateException("A package preview capture is already running"));
        var job=new CaptureJob(filename,projects.size());captureJob=job;
        var client=Minecraft.getInstance();boolean audio=EffectAudioPreview.INSTANCE.enabled();var window=client.getWindow();int width=window.getScreenWidth(),height=window.getScreenHeight();boolean resize=window.getWidth()<1280||window.getHeight()<720;
        var connection=client.getConnection();
        var saved=new Saved(hudHidden(client),audio,width,height,resize);EffectAudioPreview.INSTANCE.enabled(false);hideHud(client,true);
        if(resize)window.setWindowed(Math.max(1280,width),Math.max(720,height));
        CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
        for(var project:projects)chain=chain.thenCompose(ignored->{job.check();var arguments=project.reference();arguments.addProperty("filename",filename);return request("preview-open",arguments).thenCompose(geometry->main(()->{job.check();client.setScreen(new WorkspacePreviewScreen());return null;}).thenCompose(nothing->WorkspaceClient.waitTicks(4)).thenCompose(nothing->main(()->{job.check();job.hidden=true;return null;})).thenCompose(nothing->WorkspaceClient.waitTicks(2)).thenCompose(nothing->{job.check();return capture(geometry);}).thenCompose(bytes->main(()->{job.hidden=false;job.check();return bytes;})).thenCompose(bytes->uploadPreview(filename,project.type()+"-"+project.id()+".png",bytes,job)).thenRun(()->job.completed++));});
        var operation=chain.handle((nothing,error)->error).thenCompose(error->request("preview-restore",new JsonObject()).handle((restored,restoreError)->{
            if(error!=null&&restoreError!=null)error.addSuppressed(restoreError);return error!=null?error:restoreError;
        })).thenCompose(error->main(()->{
            hideHud(client,saved.hideGui());EffectAudioPreview.INSTANCE.enabled(saved.audio());
            if(saved.resized())window.setWindowed(saved.width(),saved.height());if(client.getConnection()==connection)client.setScreen(screen);
            if(error!=null)throw new CompletionException(error);job.check();return null;
        })).thenApply(nothing->{var reply=new JsonObject();reply.addProperty("filename",filename);var previews=new JsonArray();projects.forEach(p->previews.add(p.type()+"-"+p.id()+".png"));reply.add("previews",previews);return reply;});
        operation.whenComplete((reply,error)->{if(captureJob==job)captureJob=null;});
        return operation.thenApply(reply->reply);
    }
    private static CompletableFuture<byte[]> capture(JsonObject geometry){
        var image=new CompletableFuture<NativeImage>();
        return main(()->{Minecraft client=Minecraft.getInstance();Screenshot.takeScreenshot(client.getMainRenderTarget(), value -> { if (!image.complete(value)) value.close(); });return EditorViewport.physical(client.getWindow());}).thenCompose(bounds->image.orTimeout(15,TimeUnit.SECONDS).thenApplyAsync(captured->{
            try(captured){
                if(captured.getWidth()<1280||captured.getHeight()<720)throw new IllegalStateException("A native render resolution of at least 1280 by 720 is required for publication");
                int apertureX=bounds==null?0:bounds.x(),apertureY=bounds==null?0:captured.getHeight()-bounds.y()-bounds.height(),apertureW=bounds==null?captured.getWidth():bounds.width(),apertureH=bounds==null?captured.getHeight():bounds.height();
                var plane=EffectProjection.calculate(new EditorLayout.Rect(apertureX,apertureY,apertureW,apertureH),WorkspaceState.number(geometry,"width",WorkspaceState.DEFAULT_WIDTH),WorkspaceState.number(geometry,"height",WorkspaceState.DEFAULT_HEIGHT),WorkspaceState.number(geometry,"pixelsPerBlock",WorkspaceState.DEFAULT_PIXELS_PER_BLOCK),WorkspaceState.number(geometry,"distance",WorkspaceState.DEFAULT_DISTANCE),WorkspaceState.number(geometry,"offsetX",0),WorkspaceState.number(geometry,"offsetY",0),70);
                var geometryCrop=WorkspacePreviewGeometry.crop(captured.getWidth(),captured.getHeight(),plane);int x=geometryCrop.x(),y=geometryCrop.y(),w=geometryCrop.width(),h=geometryCrop.height(),targetW=geometryCrop.outputWidth(),targetH=geometryCrop.outputHeight();
                try(var crop=new NativeImage(captured.format(),targetW,targetH,false);var output=new NativeImage(captured.format(),1280,720,false)){
                    captured.resizeSubRectTo(x,y,w,h,crop);output.fillRect(0,0,1280,720,0xFF101010);int ox=geometryCrop.offsetX(),oy=geometryCrop.offsetY();
                    for(int py=0;py<targetH;py++)for(int px=0;px<targetW;px++)output.setPixel(ox+px,oy+py,crop.getPixel(px,py));
                    Path temp=Files.createTempFile("arcmenu-publication-",".png");try{output.writeToFile(temp);return PreviewPngOptimizer.optimize(Files.readAllBytes(temp));}finally{Files.deleteIfExists(temp);}
                }
            }catch(Exception error){throw new CompletionException(error);}
        }));
    }
    private static CompletableFuture<Void> uploadPreview(String filename,String name,byte[] png,CaptureJob job){
        String base64=Base64.getEncoder().encodeToString(png);int count=(base64.length()+19999)/20000;CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
        for(int i=0;i<count;i++){var args=new JsonObject();args.addProperty("filename",filename);args.addProperty("name",name);args.addProperty("index",i);args.addProperty("count",count);args.addProperty("data",base64.substring(i*20000,Math.min(base64.length(),(i+1)*20000)));chain=chain.thenCompose(nothing->{job.check();return request("preview-chunk",args).thenApply(reply->null);});}return chain;
    }
}

package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.net.*;
import com.fentai.arcmenu.protocol.WorkspaceProtocol;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.IOException;

/** Optional workspace operations do not change the editor's existing wire contracts. */
public final class WorkspaceClient {
    public static final WorkspaceState STATE=new WorkspaceState();
    public static final MarketClient MARKET=new MarketClient();
    private static final FrameAssembler ASSEMBLER=new FrameAssembler();
    private record Pending(CompletableFuture<JsonObject> result,long deadline) {}
    private static final Map<Long,Pending> pending=new HashMap<>();
    private record TickWait(CompletableFuture<Void> result,int ticks) {}
    private static final List<TickWait> ticks=new ArrayList<>();
    private static long sequence, connectionGeneration;
    private static boolean automaticSignInChecked;
    private static CompletableFuture<JsonObject> configurationRequest;
    private static CompletableFuture<JsonObject> refreshRequest;
    private static volatile String configurationError="";
    private static CompletableFuture<Void> startupLoad=CompletableFuture.completedFuture(null);
    private static final ExecutorService SESSION_IO=Executors.newFixedThreadPool(2,r->{var thread=new Thread(r,"ArcMenu-Market-Session");thread.setDaemon(true);return thread;});
    private static boolean opening;
    private static String openingType="",openingId="";
    private WorkspaceClient() {}
    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(WorkspacePayload.TYPE,WorkspacePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorkspacePayload.TYPE,WorkspacePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(WorkspacePayload.TYPE,(payload,context)->receive(context.client(),payload));
        ClientLifecycleEvents.CLIENT_STARTED.register(WorkspaceClient::startAuthentication);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->SESSION_IO.shutdownNow());
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(!automaticSignInChecked&&(client.screen instanceof EditorScreen||client.screen instanceof EffectEditorScreen||client.screen instanceof WorkspaceScreen)){
                automaticSignInChecked=true;ensureAuthentication().exceptionally(error->null);
            }
            for(int i=ticks.size()-1;i>=0;i--){var wait=ticks.get(i);if(wait.result().isDone()||wait.ticks()<=1){ticks.remove(i);wait.result().complete(null);}else ticks.set(i,new TickWait(wait.result(),wait.ticks()-1));}
            pending.entrySet().removeIf(entry->entry.getValue().result().isDone());
            long now=System.nanoTime();var expired=pending.entrySet().stream().filter(e->e.getValue().deadline()<now).map(Map.Entry::getKey).toList();
            for(long id:expired)pending.remove(id).result().completeExceptionally(new IllegalStateException("Workspace reply timed out; inspect state before retrying because the operation may have completed"));
        });
    }
    private record StartupConfiguration(String endpoint,String certificate,Optional<MarketSessionCache.Entry> session) {}
    private static Path sessionFile(){return FabricLoader.getInstance().getConfigDir().resolve("arcmenu-market-session.json");}
    private static void startAuthentication(Minecraft client){
        String profile=client.getUser().getProfileId().toString();long marketGeneration=MARKET.generation();
        var configuration=CompletableFuture.supplyAsync(()->{
            String endpoint="",certificate="";
            try(var stream=WorkspaceClient.class.getResourceAsStream("/assets/arcmenu_editor/market-defaults.json")){
                if(stream!=null){var json=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();endpoint=WorkspaceState.string(json,"endpoint","");certificate=WorkspaceState.string(json,"certificateSha256","");}
            }catch(IOException|RuntimeException ignored){ /* A connected server can supply its configuration later. */ }
            return new StartupConfiguration(endpoint,certificate,MarketSessionCache.load(sessionFile(),profile,System.currentTimeMillis()/1000));
        });
        startupLoad=configuration.thenCompose(settings->onMain(()->{
            if(!client.getUser().getProfileId().toString().equals(profile))return CompletableFuture.completedFuture(null);
            if(MARKET.generation()==marketGeneration)MARKET.endpoint(settings.endpoint(),settings.certificate());
            settings.session().filter(session->session.endpoint().equals(MARKET.endpoint())&&session.certificateSha256().equals(MARKET.certificateSha256())).ifPresent(session->{
                var reply=new JsonObject();reply.addProperty("uuid",session.userId());reply.addProperty("username",session.userName());reply.addProperty("token",session.token());reply.addProperty("expiresAt",session.expiresAt());MARKET.authenticate(reply);
            });
            return CompletableFuture.completedFuture(null);
        }));
        startupLoad.thenCompose(ignored->onMain(()->MARKET.available()?signInReady(false):CompletableFuture.completedFuture(new JsonObject()))).exceptionally(error->null);
    }
    static CompletableFuture<Void> waitTicks(int count){var future=new CompletableFuture<Void>();Minecraft.getInstance().execute(()->ticks.add(new TickWait(future,count)));return future.orTimeout(15,TimeUnit.SECONDS);}
    private static <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> operation){
        var client=Minecraft.getInstance();
        if(client.isSameThread()){try{return operation.get();}catch(RuntimeException error){return CompletableFuture.failedFuture(error);}}
        var result=new CompletableFuture<T>();client.execute(()->{try{operation.get().whenComplete((reply,error)->{if(error==null)result.complete(reply);else result.completeExceptionally(error);});}catch(RuntimeException error){result.completeExceptionally(error);}});return result;
    }
    public static void open(Screen parent,boolean market) {
        if(market){
            marketConfig().whenComplete((reply,error)->Minecraft.getInstance().execute(()->{
                if(error!=null){ArcMenuEditorClient.state().error(WorkspaceClient.error(error));return;}
                if(!STATE.marketAllowed()){ArcMenuEditorClient.state().error(EditorI18n.text("arcmenu_editor.market.operator_required"));return;}
                openWorkspace(parent,true);
            }));
        }else openWorkspace(parent,false);
    }
    private static void openWorkspace(Screen parent,boolean market) {var client=Minecraft.getInstance();if(client.screen instanceof WorkspaceScreen workspace){workspace.navigate(market);return;}if(parent instanceof WorkspaceScreen workspace)parent=workspace.returnScreen();client.setScreen(new WorkspaceScreen(parent,market));}
    public static boolean keepingScreen(Screen screen,boolean effect,String id) { return (screen instanceof WorkspaceScreen || screen instanceof WorkspaceFormScreen || screen instanceof WorkspacePreviewScreen)&&!(opening&&openingType.equals(effect?"effect":"menu")&&openingId.equals(id)); }
    public static void opened(boolean effect,String id) {if(openingType.equals(effect?"effect":"menu")&&openingId.equals(id))opening=false;}
    public static CompletableFuture<JsonObject> request(String action,JsonObject args) {
        var client=Minecraft.getInstance();if(!client.isSameThread()){var snapshot=args.deepCopy();return onMain(()->request(action,snapshot));}
        if(!ClientPlayNetworking.canSend(WorkspacePayload.TYPE))return CompletableFuture.failedFuture(new IllegalStateException("This server does not support project management"));
        try {
            long id=++sequence;byte[] bytes=WorkspaceProtocol.encode(new WorkspaceProtocol.Request(id,action,args.toString()));
            var result=new CompletableFuture<JsonObject>();long timeout=action.equals("install")||action.equals("publish")?180_000_000_000L:30_000_000_000L;pending.put(id,new Pending(result,System.nanoTime()+timeout));
            ClientPlayNetworking.send(new WorkspacePayload(bytes));return result;
        }catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
    }
    public static CompletableFuture<JsonObject> refresh() {return onMain(()->{
        if(refreshRequest!=null&&!refreshRequest.isDone())return refreshRequest.thenApply(json->json);
        long generation=connectionGeneration;
        var result=request("list",new JsonObject()).thenApply(json->{if(generation!=connectionGeneration)throw new IllegalStateException("Minecraft connection changed during project refresh");STATE.update(json);long previous=MARKET.generation();MARKET.endpoint(STATE.marketUrl(),STATE.marketCertificateSha256());if(previous!=MARKET.generation()&&MARKET.available())signIn(false).exceptionally(error->null);return json;});
        refreshRequest=result;result.whenComplete((json,error)->{if(refreshRequest==result)refreshRequest=null;});return result.thenApply(json->json);
    });}
    public static CompletableFuture<JsonObject> marketConfig(){return onMain(()->{
        if(configurationRequest!=null&&!configurationRequest.isDone())return configurationRequest.thenApply(json->json);
        long generation=connectionGeneration;
        configurationError="";
        var result=request("market-config",new JsonObject()).orTimeout(10,TimeUnit.SECONDS).thenApply(json->{if(generation!=connectionGeneration)throw new IllegalStateException("Minecraft connection changed during marketplace configuration");STATE.updateCapabilities(json);MARKET.endpoint(WorkspaceState.string(json,"marketUrl",""),WorkspaceState.string(json,"marketCertificateSha256",""));return json;});
        configurationRequest=result;result.whenComplete((json,error)->{if(configurationRequest==result){configurationError=error==null?"":error instanceof TimeoutException?"Marketplace configuration timed out after 10 seconds":MarketMessages.diagnostic(error);configurationRequest=null;}});return result.thenApply(json->json);
    });}
    public static String authStatus(){return configurationRequest!=null&&!configurationRequest.isDone()?"signing_in":!configurationError.isEmpty()?"failed":MARKET.authenticationStatus();}
    public static String authStage(){return configurationRequest!=null&&!configurationRequest.isDone()?"configuring":MARKET.authenticationStage();}
    public static String authError(){return !configurationError.isEmpty()?configurationError:MARKET.authenticationError();}
    public static CompletableFuture<JsonObject> ensureAuthentication(){
        return onMain(()->MARKET.available()?signIn(false):marketConfig().thenCompose(reply->signIn(false)));
    }
    public static CompletableFuture<JsonObject> openProject(WorkspaceState.Project project,boolean discard) {
        if(project.previewOnly())return onMain(()->{
            var client=Minecraft.getInstance();
            if(!(client.screen instanceof WorkspaceScreen manager))return CompletableFuture.failedFuture(new IllegalStateException("Open the project manager before previewing templates"));
            return request(project.openAction(),project.reference()).thenCompose(reply->onMain(()->{
                client.setScreen(new WorkspacePreviewScreen(manager,project));return CompletableFuture.completedFuture(reply);
            }));
        });
        var args=project.reference();args.addProperty("confirmDiscard",discard);opening=true;openingType=project.type();openingId=project.id();
        return request(project.openAction(),args).whenComplete((reply,error)->{if(error!=null)opening=false;});
    }
    public static boolean dirty() {var scene=ArcMenuEditorClient.state().snapshot();return scene!=null&&scene.dirty()||ArcMenuEditorClient.EFFECT.active()&&ArcMenuEditorClient.EFFECT.snapshot().dirty();}
    public static CompletableFuture<JsonObject> capturePreviews(String filename,JsonArray references) {
        var client=Minecraft.getInstance();
        if(!(client.screen instanceof WorkspaceScreen screen))return CompletableFuture.failedFuture(new IllegalStateException("Open the project manager before capturing package previews"));
        var projects=new ArrayList<WorkspaceState.Project>();
        for(var entry:references){var ref=entry.getAsJsonObject();String type=ref.get("type").getAsString(),id=ref.get("id").getAsString();
            projects.add(new WorkspaceState.Project(type,id,"",WorkspaceState.DEFAULT_WIDTH,WorkspaceState.DEFAULT_HEIGHT,WorkspaceState.DEFAULT_PIXELS_PER_BLOCK,WorkspaceState.DEFAULT_DISTANCE,""));}
        return WorkspacePublication.capturePreviews(screen,filename,List.copyOf(projects));
    }
    public static CompletableFuture<JsonObject> preparePackage(String filename){
        var client=Minecraft.getInstance();
        if(!(client.screen instanceof WorkspaceScreen screen))return CompletableFuture.failedFuture(new IllegalStateException("Open the project manager before preparing package previews"));
        var pack=STATE.localPacks().stream().filter(p->p.filename().equals(filename)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown local package: "+filename));
        return WorkspacePublication.prepare(screen,pack);
    }
    public static JsonObject captureProgress(){var json=new JsonObject();var progress=WorkspacePublication.progress();json.addProperty("active",progress!=null);if(progress!=null){json.addProperty("filename",progress.filename());json.addProperty("completed",progress.completed());json.addProperty("total",progress.total());json.addProperty("cancelling",progress.cancelling());}return json;}
    public static boolean cancelCapture(){return WorkspacePublication.cancelCapture();}
    public static CompletableFuture<JsonObject> login() {
        return onMain(()->MARKET.available()?signIn(true):marketConfig().thenCompose(reply->signIn(true)));
    }
    private static CompletableFuture<JsonObject> signIn(boolean retry){
        return startupLoad.thenCompose(ignored->onMain(()->signInReady(retry)));
    }
    private static CompletableFuture<JsonObject> signInReady(boolean retry){
        configurationError="";
        var user=Minecraft.getInstance().getUser();String identity=MARKET.generation()+":"+user.getProfileId();
        var result=MARKET.signIn(identity,retry,()->MarketAuthentication.retry(()->authenticateSession(identity),MarketClient::isTransient,()->MARKET.stage(identity,"retry")));
        result.thenAccept(reply->MARKET.sessionEntry().ifPresent(entry->CompletableFuture.runAsync(()->{try{MarketSessionCache.save(sessionFile(),entry);}catch(IOException ignored){ /* Authentication remains usable when the cache is not writable. */ }})));
        return result;
    }
    private static CompletableFuture<JsonObject> authenticateSession(String identity) {
        var client=Minecraft.getInstance();var user=client.getUser();var args=new JsonObject();args.addProperty("uuid",user.getProfileId().toString());args.addProperty("username",user.getName());
        long marketGeneration=MARKET.generation();MARKET.stage(identity,"challenge");
        var result=new CompletableFuture<JsonObject>();
        var pipeline=MARKET.request("POST","/v1/auth/challenge",args,false).thenCompose(challenge->{if(result.isDone())return CompletableFuture.<JsonObject>failedFuture(new CancellationException());MARKET.stage(identity,"session");return CompletableFuture.supplyAsync(()->{
            if(result.isDone())throw new CancellationException();
            try {client.services().sessionService().joinServer(user.getProfileId(),user.getAccessToken(),challenge.get("serverId").getAsString());}
            catch(Exception error){throw new CompletionException(new IllegalStateException("Minecraft session verification failed: "+error.getMessage(),error));}
            var verify=new JsonObject();verify.addProperty("challengeId",challenge.get("challengeId").getAsString());return verify;
        },SESSION_IO);}).thenCompose(verify->{if(result.isDone())return CompletableFuture.<JsonObject>failedFuture(new CancellationException());MARKET.stage(identity,"verify");return MARKET.request("POST","/v1/auth/verify",verify,false);}).thenApply(reply->{
            if(marketGeneration!=MARKET.generation()||!client.getUser().getProfileId().equals(user.getProfileId()))throw new IllegalStateException("Minecraft account or marketplace connection changed during authentication");return reply;
        });
        pipeline.whenComplete((reply,error)->{if(error==null)result.complete(reply);else result.completeExceptionally(error);});
        result.whenComplete((reply,error)->{if(result.isCancelled())pipeline.cancel(true);});return result;
    }
    private static void receive(Minecraft client,WorkspacePayload payload) {
        if(!client.isSameThread()){client.execute(()->receive(client,payload));return;}
        try {
            byte[] bytes=ASSEMBLER.accept(payload.data());if(bytes==null)return;
            if(!(WorkspaceProtocol.decode(bytes) instanceof WorkspaceProtocol.Reply reply))return;
            if(reply.requestId()==0) {if(reply.ok()&&reply.action().equals("show")){var data=JsonParser.parseString(reply.json()).getAsJsonObject();STATE.updateCapabilities(data);open(client.screen,WorkspaceState.string(data,"view","projects").equals("market"));}return;}
            var current=pending.remove(reply.requestId());if(current==null)return;
            if(!reply.ok()){current.result().completeExceptionally(new IllegalStateException(reply.error()));return;}
            current.result().complete(reply.json().isBlank()?new JsonObject():JsonParser.parseString(reply.json()).getAsJsonObject());
        }catch(RuntimeException error){ArcMenuEditorClient.state().error(error.getMessage());}
    }
    public static void reset() {
        connectionGeneration++;automaticSignInChecked=false;configurationRequest=refreshRequest=null;configurationError="";opening=false;openingType=openingId="";ASSEMBLER.clear();STATE.clear();WorkspacePublication.cancelCapture();
        var requests=new ArrayList<>(pending.values());pending.clear();requests.forEach(p->p.result().completeExceptionally(new IllegalStateException("Minecraft disconnected")));
        ticks.forEach(t->t.result().completeExceptionally(new IllegalStateException("Minecraft disconnected")));ticks.clear();
    }
    static String error(Throwable error) {return MarketMessages.error(error);}
}

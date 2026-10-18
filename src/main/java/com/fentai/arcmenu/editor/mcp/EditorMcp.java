package com.fentai.arcmenu.editor.mcp;

import com.fentai.arcmenu.editor.*;
import com.fentai.arcmenu.protocol.*;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.fentai.arcmenu.editor.net.EditorPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import com.fentai.arcmenu.editor.EffectAudioPreview;
import java.io.IOException;

/** Marshals every game operation onto the client thread and waits for authoritative server replies. */
public final class EditorMcp {
    private static final Gson JSON = new Gson();
    private static final Object CALL_LOCK = new Object();
    private static final ArrayList<TickWait> TICKS = new ArrayList<>();
    private static McpServer server;
    private static Properties config;
    private static String status = "";
    private static volatile Pending pending;
    private record TickWait(CompletableFuture<Void> result, int remaining) {}
    private static final class Pending {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        int operation;
        long effectRequest;
        String openId;
        boolean openingEffect;
        boolean anyAck;
    }
    private EditorMcp() {}
    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (int i = TICKS.size() - 1; i >= 0; i--) { var wait = TICKS.get(i); if (wait.result.isDone() || wait.remaining <= 1) { TICKS.remove(i); wait.result.complete(null); } else TICKS.set(i, new TickWait(wait.result, wait.remaining - 1)); }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
        try {
            config = new Properties(); Path file = configFile();
            if (Files.isRegularFile(file)) try (var in = Files.newInputStream(file)) { config.load(in); }
            config.putIfAbsent("enabled", "true"); config.putIfAbsent("port", "18766"); config.putIfAbsent("token", UUID.randomUUID().toString() + UUID.randomUUID());
            saveConfig(); if (Boolean.parseBoolean(config.getProperty("enabled"))) start();
        } catch (Exception error) { status = error.getMessage(); LoggerFactory.getLogger("ArcMenu MCP").warn("MCP initialization failed: {}", status); }
    }
    public static Path configFile() { return FabricLoader.getInstance().getConfigDir().resolve("arcmenu-editor-mcp.properties"); }
    private static void saveConfig() throws IOException { Files.createDirectories(configFile().getParent()); try (var out = Files.newOutputStream(configFile())) { config.store(out, "ArcMenu Editor MCP: loopback HTTP with bearer authentication"); } }
    public static synchronized void start() throws IOException {
        if (server != null) return;
        int port = Integer.parseInt(config.getProperty("port")); if (port < 1024 || port > 65535) throw new IllegalArgumentException("MCP port must be between 1024 and 65535");
        server = new McpServer(port, config.getProperty("token"), EditorMcp::call); status = "";
    }
    public static synchronized void stop() { if (server != null) server.close(); server = null; disconnected(); }
    public static boolean running() { return server != null; }
    public static String endpoint() { return "http://127.0.0.1:" + (config == null ? "18766" : config.getProperty("port")) + "/mcp"; }
    public static String status() { return status; }
    public static String connectionJson() { JsonObject entry = new JsonObject(); entry.addProperty("url", endpoint()); JsonObject headers = new JsonObject(); headers.addProperty("Authorization", "Bearer " + config.getProperty("token")); entry.add("headers", headers); JsonObject servers = new JsonObject(); servers.add("arcmenu", entry); JsonObject root = new JsonObject(); root.add("mcpServers", servers); return JSON.toJson(root); }
    public static void enabled(boolean value) {
        try { if (value) start(); else stop(); config.setProperty("enabled", Boolean.toString(value)); saveConfig(); }
        catch (Exception error) { status = error.getMessage(); }
    }
    public static void disconnected() {
        Pending current = pending; if (current != null) current.result.completeExceptionally(new IllegalStateException("Minecraft disconnected or MCP stopped"));
    }
    public static void scenePacket(EditorProtocol.Packet packet) {
        Pending current = pending; if (current == null) return;
        if (packet instanceof EditorProtocol.ErrorPacket error) current.result.completeExceptionally(new IllegalStateException(error.message()));
        else if (packet instanceof EditorProtocol.AckPacket ack && current.openId == null && current.effectRequest == 0 && (current.anyAck || ack.operation() == current.operation)) current.result.complete(null);
        else if (packet instanceof EditorProtocol.SnapshotPacket snapshot && current.openId != null && !current.openingEffect && snapshot.menuId().equals(current.openId)) current.result.complete(null);
    }
    public static void effectPacket(EffectProtocol.Packet packet) {
        Pending current = pending; if (current == null) return;
        if (packet instanceof EffectProtocol.Error error) current.result.completeExceptionally(new IllegalStateException(error.message()));
        else if (packet instanceof EffectProtocol.Ack ack && (current.anyAck || current.effectRequest != 0 && ack.requestId() == current.effectRequest)) current.result.complete(null);
        else if (packet instanceof EffectProtocol.Snapshot snapshot && current.openId != null && current.openingEffect && snapshot.id().equals(current.openId)) current.result.complete(null);
        else if (packet instanceof EffectProtocol.Closed && current.effectRequest != 0) current.result.completeExceptionally(new IllegalStateException("Effect editor closed"));
    }
    private static <T> T main(Supplier<T> action) throws Exception {
        var result = new CompletableFuture<T>(); Minecraft.getInstance().execute(() -> { if (result.isDone()) return; try { result.complete(action.get()); } catch (Exception error) { result.completeExceptionally(error); } });
        try { return result.get(10, TimeUnit.SECONDS); } catch (TimeoutException timeout) { result.cancel(false); throw new IllegalStateException("Minecraft client did not respond; keep the game rendering"); }
    }
    private static JsonObject state() {
        Minecraft client = Minecraft.getInstance(); JsonObject result = new JsonObject();
        result.addProperty("connected", client.player != null && client.getConnection() != null);
        result.addProperty("screen",client.screen==null?"":client.screen.getClass().getSimpleName());
        result.addProperty("editorOpen", EditorScreen.isEditor(client.screen) || client.screen instanceof EffectWorkspaceChild || client.screen instanceof McpScreen);
        result.add("visionCursor", VisionCursorClient.metadata());
        result.add("scene", JSON.toJsonTree(ArcMenuEditorClient.state().snapshot())); result.add("effect", JSON.toJsonTree(ArcMenuEditorClient.EFFECT.snapshot()));
        result.add("viewport", JSON.toJsonTree(ArcMenuEditorClient.EFFECT.viewport()));
        result.add("audioPreview", EffectAudioPreview.INSTANCE.metadata());
        result.add("selection", JSON.toJsonTree(ArcMenuEditorClient.state().selectedIds())); result.addProperty("tab", ArcMenuEditorClient.state().activeTab() == 0 ? "frontend" : "backend"); result.addProperty("status", ArcMenuEditorClient.state().status());
        var screen=client.screen instanceof EffectEditorScreen workspace?workspace:client.screen instanceof EffectWorkspaceChild child?child.workspace():null;
        if(screen!=null)result.add("workspace",screen.workspaceMetadata());
        result.add("projects",JSON.toJsonTree(WorkspaceClient.STATE.projects()));result.add("packs",JSON.toJsonTree(WorkspaceClient.STATE.packs()));result.add("localPacks",JSON.toJsonTree(WorkspaceClient.STATE.localPacks()));
        if(client.screen instanceof WorkspaceScreen manager)result.add("projectManager",manager.metadata());
        if(client.screen instanceof WorkspacePreviewScreen preview)result.add("projectPreview",preview.metadata());
        result.addProperty("playhead", ArcMenuEditorClient.EFFECT.playhead()); result.addProperty("pendingTimelineEdits", ArcMenuEditorClient.EFFECT.busy()); result.addProperty("capturedAt", Instant.now().toString()); return result;
    }
    private static JsonObject call(String name, JsonObject args) throws Exception {
        synchronized (CALL_LOCK) {
            if (name.equals("arcmenu_state")) return McpTools.result(main(EditorMcp::state));
            if(name.equals("workspace_projects"))return workspaceProjects(args);
            if(name.equals("workspace_market"))return workspaceMarket(args);
            if(name.equals("effect_workspace")) {
                String operation=required(args,"operation");
                if(!operation.equals("state")) {
                    Pending request=new Pending();request.anyAck=true;pending=request;
                    try {
                        boolean sent=main(()-> {Minecraft client=Minecraft.getInstance();var screen=client.screen instanceof EffectEditorScreen workspace?workspace:client.screen instanceof EffectWorkspaceChild child?child.workspace():null;if(screen==null)throw new IllegalStateException("Open the Effect workspace before using workspace input");if(ArcMenuEditorClient.EFFECT.busy())throw new IllegalStateException("Wait for pending Effect edits");long before=ArcMenuEditorClient.sceneRequestCount();
                            boolean handled=screen.workspaceInput(operation,string(args,"command",""),string(args,"shortcut",""),number(args,"x",0),number(args,"y",0),integer(args,"button",0),(bool(args,"ctrl",false)?2:0)|(bool(args,"shift",false)?1:0)|(bool(args,"alt",false)?4:0),number(args,"dx",0),number(args,"dy",0));
                            if(!handled)throw new IllegalArgumentException("The workspace did not handle that command or input");return ArcMenuEditorClient.sceneRequestCount()!=before||ArcMenuEditorClient.EFFECT.busy();});
                        if(sent)await(request);
                        waitTicks(3);
                    }finally{if(pending==request)pending=null;}
                }
                return McpTools.result(main(EditorMcp::state));
            }
            if (name.equals("arcmenu_screenshot")) {
                if (args.has("frame")) effectEdit("seek", "", "", integer(args, "frame", 0), 0, "", "linear", new double[]{0,0,0,0}, args);
                return capture(args, args.has("frame") ? integer(args, "frame", 0) : -1);
            }
            if (name.equals("effect_capture_frames")) {
                JsonArray frames = args.getAsJsonArray("frames"), content = new JsonArray(); JsonArray metadata = new JsonArray();
                for (JsonElement frame : frames) { int f = frame.getAsInt(); effectEdit("seek", "", "", f, 0, "", "linear", new double[]{0,0,0,0}, args); JsonObject shot = capture(args, f); shot.getAsJsonArray("content").forEach(content::add); metadata.add(shot.get("structuredContent")); }
                JsonObject result = new JsonObject(), data = new JsonObject(); data.add("frames", metadata); result.add("structuredContent", data); result.add("content", content); return result;
            }
            if (name.equals("arcmenu_open")) {
                String id = required(args, "id"); identifier(id); String from = string(args, "fromMenu", ""); if (!from.isEmpty()) identifier(from);
                Pending request = new Pending(); request.openId = id; request.openingEffect = required(args, "mode").equals("effect"); pending = request;
                try { main(() -> { Minecraft client = Minecraft.getInstance(); if (client.player == null || client.getConnection() == null) throw new IllegalStateException("Join an ArcMenu server first"); client.getConnection().sendCommand("arcmenu " + (request.openingEffect ? "editeffect " : "edit ") + id + (request.openingEffect ? (from.isEmpty() ? "" : " " + from) : (bool(args, "create", false) ? " create" + (from.isEmpty() ? "" : " " + from) : ""))); return null; }); await(request); }
                finally { if (pending == request) pending = null; }
                return McpTools.result(main(EditorMcp::state));
            }
            if (name.equals("arcmenu_select")) {
                byte tab = tab(args); if (main(() -> ArcMenuEditorClient.state().activeTab()) != tab) sceneEdit(new EditorProtocol.SwitchTabPacket(tab), EditorProtocol.OP_TAB);
                main(() -> { var state = ArcMenuEditorClient.state(); List<String> ids = strings(args, "ids"); for (String id : ids) if (state.find(id) == null) throw new IllegalArgumentException("Element is missing: " + id); state.selectAll(ids); refresh(); return null; });
            } else if (name.equals("arcmenu_edit")) editScene(args);
            else editEffect(name, args);
            return McpTools.result(main(EditorMcp::state));
        }
    }
    private static JsonObject workspaceProjects(JsonObject args)throws Exception{
        String action=required(args,"action");
        if(action.equals("state"))return McpTools.result(main(EditorMcp::state));
        if(action.equals("show")){main(()->{var client=Minecraft.getInstance();WorkspaceClient.open(client.screen,false);return null;});return McpTools.result(main(EditorMcp::state));}
        if(action.equals("select")){main(()->{var catalogue=WorkspaceClient.STATE;catalogue.selectAll(List.of());var rows=catalogue.rows("");for(String key:strings(args,"ids")){var p=catalogue.projects().stream().filter(project->project.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown project: "+key));catalogue.select(p,true,false,rows);}var client=Minecraft.getInstance();if(client.screen instanceof WorkspaceScreen manager)manager.selectionChanged();return null;});return McpTools.result(main(EditorMcp::state));}
        if(action.equals("select_pack")){var parameters=JsonParser.parseString(string(args,"parameters","{}")).getAsJsonObject();main(()->{var client=Minecraft.getInstance();if(!(client.screen instanceof WorkspaceScreen manager))throw new IllegalStateException("Open the native project manager first");manager.selectLocalPackage(required(parameters,"filename"));return null;});return McpTools.result(main(EditorMcp::state));}
        if(action.equals("input")){main(()->{var client=Minecraft.getInstance();if(!(client.screen instanceof WorkspaceScreen manager))throw new IllegalStateException("Open the native project manager first");if(!manager.input(required(args,"field"),required(args,"value")))throw new IllegalStateException("The workspace field is unavailable or an operation is already in progress");return null;});return McpTools.result(main(EditorMcp::state));}
        if(action.equals("click")||action.equals("keypress")){main(()->{var client=Minecraft.getInstance();if(action.equals("click")){if(!(client.screen instanceof WorkspaceScreen manager))throw new IllegalStateException("Open the native project manager first");var event=mouseEvent(number(args,"x",0),number(args,"y",0),integer(args,"button",0),0);manager.mouseMoved(event.x(),event.y());manager.mouseClicked(event,false);manager.mouseReleased(event);}else{var event=new KeyEvent(workspaceKey(required(args,"key")),0,0);if(client.screen instanceof WorkspaceScreen manager)manager.keyPressed(event);else if(client.screen instanceof WorkspacePreviewScreen preview)preview.keyPressed(event);else throw new IllegalStateException("Open the native project manager or a package preview capture first");}return null;});waitTicks(2);return McpTools.result(main(EditorMcp::state));}
        if(action.equals("command")){main(()->{var client=Minecraft.getInstance();if(!(client.screen instanceof WorkspaceScreen manager))throw new IllegalStateException("Open the native project manager first");if(!manager.command(required(args,"command")))throw new IllegalStateException("The workspace command is unavailable or an operation is already in progress");return null;});return McpTools.result(main(EditorMcp::state));}
        JsonObject parameters=JsonParser.parseString(string(args,"parameters","{}")).getAsJsonObject();
        CompletableFuture<JsonObject> future=main(()->{
            if(action.equals("list"))return WorkspaceClient.refresh();
            if(action.equals("capture"))return WorkspaceClient.capturePreviews(required(parameters,"filename"),parameters.getAsJsonArray("projects"));
            if(action.equals("prepare"))return WorkspaceClient.preparePackage(required(parameters,"filename"));
            if(action.equals("open")){String type=required(parameters,"type"),id=required(parameters,"id");var project=new WorkspaceState.Project(type,id,"",0,0,0,0,"");return WorkspaceClient.openProject(project,bool(parameters,"confirmDiscard",false));}
            return WorkspaceClient.request(action,parameters);
        });return McpTools.result(future.get(190,TimeUnit.SECONDS));
    }
    private static MouseButtonEvent mouseEvent(double x,double y,int button,int mods){return new MouseButtonEvent(x,y,new MouseButtonInfo(button==0?GLFW.GLFW_MOUSE_BUTTON_LEFT:button==1?GLFW.GLFW_MOUSE_BUTTON_RIGHT:GLFW.GLFW_MOUSE_BUTTON_MIDDLE,mods));}
    private static int workspaceKey(String key){return switch(key){case "escape"->GLFW.GLFW_KEY_ESCAPE;case "enter"->GLFW.GLFW_KEY_ENTER;case "tab"->GLFW.GLFW_KEY_TAB;case "backspace"->GLFW.GLFW_KEY_BACKSPACE;case "delete"->GLFW.GLFW_KEY_DELETE;case "left"->GLFW.GLFW_KEY_LEFT;case "right"->GLFW.GLFW_KEY_RIGHT;case "up"->GLFW.GLFW_KEY_UP;case "down"->GLFW.GLFW_KEY_DOWN;case "home"->GLFW.GLFW_KEY_HOME;case "end"->GLFW.GLFW_KEY_END;default->throw new IllegalArgumentException("Unknown workspace key: "+key);};}
    private static JsonObject workspaceMarket(JsonObject args)throws Exception{
        String action=required(args,"action");
        if(action.equals("state")){var reply=main(()->{var json=new JsonObject();json.addProperty("available",WorkspaceClient.MARKET.available());json.addProperty("authenticated",WorkspaceClient.MARKET.authenticated());json.addProperty("username",WorkspaceClient.MARKET.userName());json.addProperty("authStatus",WorkspaceClient.authStatus());json.addProperty("authStage",WorkspaceClient.authStage());json.addProperty("authError",WorkspaceClient.authError());return json;});return McpTools.result(reply);}
        if(action.equals("open")){main(()->{var client=Minecraft.getInstance();if(!(client.screen instanceof WorkspaceScreen manager)||!manager.selectMarketResource(required(args,"id")))throw new IllegalStateException("Open the marketplace and wait for its catalogue before opening this resource");return null;});return McpTools.result(main(EditorMcp::state));}
        if(action.equals("show")||action.equals("logout")){main(()->{var client=Minecraft.getInstance();if(action.equals("logout"))WorkspaceClient.MARKET.clearAuthentication();else WorkspaceClient.open(client.screen,true);return null;});return McpTools.result(main(EditorMcp::state));}
        CompletableFuture<JsonObject> future=main(()->WorkspaceClient.MARKET.available()?CompletableFuture.completedFuture(new JsonObject()):WorkspaceClient.marketConfig());
        future=future.thenCompose(ignored->{var scheduled=new CompletableFuture<JsonObject>();Minecraft.getInstance().execute(()->{
            try{
                var market=WorkspaceClient.MARKET;CompletableFuture<JsonObject> result;
                if(action.equals("login"))result=WorkspaceClient.login().thenApply(auth->{var safe=new JsonObject();safe.addProperty("username",auth.get("username").getAsString());safe.addProperty("uuid",auth.get("uuid").getAsString());safe.addProperty("expiresAt",auth.get("expiresAt").getAsLong());return safe;});
                else{if(!market.authenticated())throw new IllegalStateException("Verify a genuine Minecraft account before using the marketplace");String id=string(args,"id","");
                    if(action.equals("list"))result=market.request("GET","/v1/packs?q="+MarketClient.path(string(args,"q",""))+"&page="+integer(args,"page",1)+"&sort="+MarketClient.path(string(args,"sort","popular")),null,true);
                    else if(action.equals("detail"))result=market.request("GET","/v1/packs/"+MarketClient.path(required(args,"id")),null,true);
                    else if(action.equals("install")){var body=new JsonObject();body.addProperty("id",required(args,"id"));body.addProperty("version",required(args,"version"));body.addProperty("token",market.token());result=WorkspaceClient.request("install",body);}
                    else{var body=new JsonObject();if(action.equals("rate"))body.addProperty("stars",integer(args,"stars",0));else if(action.equals("like"))body.addProperty("liked",bool(args,"liked",true));else{body.addProperty("title",required(args,"title"));body.addProperty("description",string(args,"description",""));}result=market.request(action.equals("rate")||action.equals("like")?"POST":"PUT","/v1/packs/"+MarketClient.path(id)+(action.equals("rate")?"/ratings":action.equals("like")?"/likes":""),body,true);}
                }
                result.whenComplete((reply,error)->{if(error==null)scheduled.complete(reply);else scheduled.completeExceptionally(error);});
            }catch(Exception error){scheduled.completeExceptionally(error);}
        });return scheduled;});return McpTools.result(future.get(action.equals("install")?190:60,TimeUnit.SECONDS));
    }
    private static void identifier(String value) { if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Project/menu ID must use letters, digits, underscores or hyphens"); }
    private static void refresh() { Minecraft client = Minecraft.getInstance(); if (client.screen instanceof EffectEditorScreen screen) screen.serverStateChanged(); else if (client.screen instanceof EditorScreen screen) screen.serverStateChanged(); }
    private static void await(Pending request) throws Exception {
        try { request.result.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException error) { throw new IllegalStateException(error.getCause().getMessage()); }
        catch (TimeoutException timeout) { throw new IllegalStateException("No server acknowledgement within 10 seconds. Inspect state before retrying; the operation may have completed. Check server permission/chat errors."); }
    }
    private static void sceneEdit(EditorProtocol.Packet packet, int operation) throws Exception {
        Pending request = new Pending(); request.operation = operation; pending = request;
        try { main(() -> { if (!ClientPlayNetworking.canSend(EditorPayload.TYPE)) throw new IllegalStateException("The server does not support the ArcMenu editor channel"); if (packet instanceof EditorProtocol.SwitchTabPacket switched) ArcMenuEditorClient.state().activeTab(switched.tab()); ArcMenuEditorClient.send(packet); return null; }); await(request); }
        finally { if (pending == request) pending = null; }
    }
    private static void editScene(JsonObject args) throws Exception {
        byte tab = tab(args); if (main(() -> ArcMenuEditorClient.state().activeTab()) != tab) sceneEdit(new EditorProtocol.SwitchTabPacket(tab), EditorProtocol.OP_TAB);
        var snapshot = main(() -> ArcMenuEditorClient.state().snapshot()); if (snapshot == null) throw new IllegalStateException("Open a menu/Effect first"); checkRevision(args, snapshot.revision());
        long revision = snapshot.revision(); String action = required(args, "action"), target = string(args, "target", ""), parent = string(args, "parent", ""); List<String> ids = strings(args, "nodeIds");
        EditorProtocol.Packet packet; int operation;
        switch (action) {
            case "create" -> { packet = new EditorProtocol.CreatePacket(revision, tab, McpTools.nodeKind(required(args, "kind")), parent, string(args, "initialSource", "")); operation = EditorProtocol.OP_CREATE; }
            case "property" -> { packet = new EditorProtocol.SetPropertyPacket(revision, tab, required(args, "target"), required(args, "property"), required(args, "value")); operation = EditorProtocol.OP_PROPERTY; }
            case "delete" -> { packet = new EditorProtocol.DeletePacket(revision, tab, required(args, "target")); operation = EditorProtocol.OP_DELETE; }
            case "delete-many" -> { packet = new EditorProtocol.DeleteManyPacket(revision, tab, ids); operation = EditorProtocol.OP_DELETE_MANY; }
            case "duplicate" -> { packet = new EditorProtocol.DuplicatePacket(revision, tab, ids, parent, bool(args, "preserveParents", true)); operation = EditorProtocol.OP_DUPLICATE; }
            case "group" -> { packet = new EditorProtocol.GroupPacket(revision, ids); operation = EditorProtocol.OP_GROUP; }
            case "ungroup" -> { packet = new EditorProtocol.UngroupPacket(revision, required(args, "target")); operation = EditorProtocol.OP_UNGROUP; }
            case "reorder" -> { packet = new EditorProtocol.ReorderPacket(revision, tab, ids, string(args, "before", "")); operation = EditorProtocol.OP_REORDER; }
            case "reparent" -> { packet = new EditorProtocol.ReparentPacket(revision, ids, parent, string(args, "before", "")); operation = EditorProtocol.OP_REPARENT; }
            case "undo" -> { packet = new EditorProtocol.UndoPacket(revision); operation = EditorProtocol.OP_UNDO; }
            case "redo" -> { packet = new EditorProtocol.RedoPacket(revision); operation = EditorProtocol.OP_REDO; }
            case "save" -> { packet = new EditorProtocol.SavePacket(revision); operation = EditorProtocol.OP_SAVE; }
            case "apply" -> { packet = new EditorProtocol.ApplyPacket(revision); operation = EditorProtocol.OP_APPLY; }
            case "template-save" -> { packet = new EditorProtocol.SaveTemplatePacket(revision, required(args, "target"), required(args, "template")); operation = EditorProtocol.OP_TEMPLATE_SAVE; }
            case "template-instantiate" -> { packet = new EditorProtocol.InstantiateTemplatePacket(revision, required(args, "template")); operation = EditorProtocol.OP_TEMPLATE_INSTANTIATE; }
            case "template-delete" -> { packet = new EditorProtocol.DeleteTemplatePacket(revision, required(args, "template")); operation = EditorProtocol.OP_TEMPLATE_DELETE; }
            default -> throw new IllegalArgumentException("Unknown scene operation: " + action);
        }
        sceneEdit(packet, operation);
    }
    private static void editEffect(String tool, JsonObject args) throws Exception {
        String target = string(args, "target", ""), property = string(args, "property", ""), value = string(args, "value", ""), interpolation = string(args, "interpolation", "linear"), action;
        int frame = integer(args, "frame", 0), end = 0; double[] curve = {1d/3,0,2d/3,1};
        if (args.has("curve")) for (int i = 0; i < 4; i++) curve[i] = args.getAsJsonArray("curve").get(i).getAsDouble();
        switch (tool) {
            case "effect_keyframes" -> {
                var payload=new JsonObject();payload.add("keys",args.getAsJsonArray("keys"));String operation=required(args,"action");
                action=switch(operation){case "move"->"move-keys";case "copy"->"copy-keys";case "delete"->"delete-keys";case "interpolate"->"interpolation-keys";default->"set-keys";};
                if(operation.equals("interpolate")){payload.addProperty("interpolation",required(args,"interpolation"));payload.addProperty("x1",curve[0]);payload.addProperty("y1",curve[1]);payload.addProperty("x2",curve[2]);payload.addProperty("y2",curve[3]);}
                value=payload.toString();
            }
            case "effect_layer_operation" -> {
                String operation=required(args,"action");action=switch(operation){case "split"->"split-layer";case "move"->args.has("targets")?"move-layers":"move-layer";case "copy"->"copy-layers";case "cut"->"cut-layers";case "paste"->"paste-layers";default->"ungroup-layer";};
                if(operation.equals("split"))frame=integer(args,"frame",0);if(operation.equals("move"))frame=integer(args,"delta",0);
                if(args.has("targets")){var payload=new JsonObject();payload.add("targets",args.get("targets"));value=payload.toString();}
            }
            case "effect_keyframe" -> { action = switch (string(args, "action", "set")) { case "replace" -> "replace-key"; case "move" -> "move-key"; case "delete" -> "delete-key"; default -> "key"; }; if (action.equals("replace-key") || action.equals("move-key")) { required(args, "newFrame"); end = integer(args, "newFrame", 0); } if (action.equals("key") || action.equals("replace-key")) value = required(args, "value"); }
            case "effect_layer" -> { action = "layer"; frame = integer(args, "start", 0); end = integer(args, "end", 0); value = Boolean.toString(bool(args, "enabled", true)); property = Boolean.toString(bool(args, "solo", false)); }
            case "effect_track" -> { action = bool(args, "delete", false) ? "delete-track" : "track"; value = Boolean.toString(bool(args, "enabled", true)); }
            case "effect_cue" -> { target = required(args, "id"); action = bool(args, "delete", false) ? "delete-cue" : "cue"; if (action.equals("cue")) { required(args, "frame"); property = required(args, "kind"); value = required(args, "source"); } end = integer(args, "end", -1); interpolation = bool(args, "enabled", true) ? "linear" : "muted"; curve[0] = number(args, "volume", 1); curve[1] = number(args, "pitch", 1); }
            case "effect_marker" -> { action = bool(args, "delete", false) ? "delete-marker" : "marker"; value = string(args, "label", ""); }
            case "effect_composition" -> { action = "settings"; curve[0] = number(args, "fps", 20); frame = integer(args, "workStart", 0); end = integer(args, "duration", 0); value = Integer.toString(integer(args, "workEnd", 0)); property = Boolean.toString(bool(args, "loop", false)); }
            case "effect_control" -> {
                action = required(args, "action");
                if (action.equals("audio-preview")) {
                    required(args, "enabled");
                    main(() -> { if (!ArcMenuEditorClient.EFFECT.active()) throw new IllegalStateException("Open an Effect composition first"); EffectAudioPreview.INSTANCE.enabled(bool(args, "enabled", true)); return null; });
                    return;
                }
                if (action.equals("value-graph") || action.equals("speed-graph") || action.equals("timeline")) {
                    String mode = action;
                    main(() -> {
                        Minecraft client = Minecraft.getInstance();
                        if (!(client.screen instanceof EffectEditorScreen screen)) throw new IllegalStateException("Open the Effect workspace before changing its graph view");
                        screen.graphView(mode);
                        return null;
                    });
                    return;
                }
                if (action.equals("seek")) required(args, "frame");
                if (action.equals("auto-key")) { required(args, "enabled"); value = Boolean.toString(bool(args, "enabled", false)); }
            }
            default -> throw new IllegalArgumentException("Unknown Effect tool: " + tool);
        }
        effectEdit(action, target, property, frame, end, value, interpolation, curve, args);
    }
    private static void effectEdit(String action, String target, String property, int frame, int end, String value, String interpolation, double[] curve, JsonObject args) throws Exception {
        Pending request = new Pending(); pending = request;
        try {
            main(() -> { var state = ArcMenuEditorClient.EFFECT; if (!state.active()) throw new IllegalStateException("Open an Effect composition first"); if (state.busy()) throw new IllegalStateException("Wait for pending timeline edits before issuing another operation"); checkRevision(args, state.snapshot().revision()); state.submit(action, target, property, frame, end, value, interpolation, curve[0], curve[1], curve[2], curve[3]); request.effectRequest = state.inFlightRequest(); return null; });
            await(request);
        } finally { if (pending == request) pending = null; }
    }
    private static void waitTicks(int count)throws Exception{var future=new CompletableFuture<Void>();main(()->{TICKS.add(new TickWait(future,count));return null;});future.get(10,TimeUnit.SECONDS);}
    private static JsonObject capture(JsonObject args, int frame) throws Exception {
        String scope = string(args, "scope", "viewport");
        boolean cleanViewport = scope.equals("viewport");
        boolean previousClean = main(() -> {
            boolean previous = EditorScreen.captureClean;
            EditorScreen.captureClean = cleanViewport;
            return previous;
        });
        try {
            var ticks = new CompletableFuture<Void>(); main(() -> { TICKS.add(new TickWait(ticks, Math.max(cleanViewport ? 2 : 1, integer(args, "waitTicks", 4)))); return null; });
            try { ticks.get(10, TimeUnit.SECONDS); } catch (TimeoutException error) { ticks.cancel(false); throw new IllegalStateException("Minecraft is not rendering ticks"); }
            CompletableFuture<NativeImage> image = new CompletableFuture<>();
            var bounds = main(() -> {
                Minecraft client = Minecraft.getInstance(); var viewport = EditorViewport.physical(client.getWindow());
                Screenshot.takeScreenshot(client.getMainRenderTarget(), value -> { if (!image.complete(value)) value.close(); });
                return viewport;
            });
            NativeImage captured;
            try { captured = image.get(10, TimeUnit.SECONDS); } catch (TimeoutException error) { image.cancel(false); throw new IllegalStateException("Minecraft screenshot capture timed out"); }
            byte[] png; int width, height;
            try (captured) {
                int x = 0, y = 0, w = captured.getWidth(), h = captured.getHeight();
                if (scope.equals("viewport") && bounds != null) { x = bounds.x(); y = captured.getHeight() - bounds.y() - bounds.height(); w = bounds.width(); h = bounds.height(); }
                x = Math.max(0, Math.min(x, captured.getWidth() - 1)); y = Math.max(0, Math.min(y, captured.getHeight() - 1)); w = Math.max(1, Math.min(w, captured.getWidth() - x)); h = Math.max(1, Math.min(h, captured.getHeight() - y));
                width = Math.min(w, integer(args, "maxWidth", 1280)); height = Math.max(1, (int) Math.round(h * width / (double) w));
                try (NativeImage resized = new NativeImage(captured.format(), width, height, false)) {
                    captured.resizeSubRectTo(x, y, w, h, resized); Path temp = Files.createTempFile("arcmenu-mcp-frame-", ".png");
                    try { resized.writeToFile(temp); png = Files.readAllBytes(temp); } finally { Files.deleteIfExists(temp); }
                }
            }
            JsonObject metadata = main(EditorMcp::state); metadata.remove("scene"); metadata.remove("effect"); metadata.addProperty("width", width); metadata.addProperty("height", height); metadata.addProperty("scope", bounds == null ? "window" : scope); if (frame >= 0) metadata.addProperty("frame", frame);
            JsonObject result = McpTools.result(metadata), content = new JsonObject(); content.addProperty("type", "image"); content.addProperty("mimeType", "image/png"); content.addProperty("data", Base64.getEncoder().encodeToString(png)); result.getAsJsonArray("content").add(content); return result;
        } finally {
            main(() -> { EditorScreen.captureClean = previousClean; return null; });
        }
    }
    private static void checkRevision(JsonObject args, long revision) { if (args.has("expectedRevision") && args.get("expectedRevision").getAsLong() != revision) throw new IllegalArgumentException("Revision conflict; read current state before editing"); }
    private static byte tab(JsonObject args) { return (byte) (string(args, "tab", "frontend").equals("backend") ? 1 : 0); }
    private static String string(JsonObject args, String key, String fallback) { return args.has(key) ? args.get(key).getAsString() : fallback; }
    private static String required(JsonObject args, String key) { if (!args.has(key)) throw new IllegalArgumentException("Missing argument: " + key); return args.get(key).getAsString(); }
    private static int integer(JsonObject args, String key, int fallback) { if (!args.has(key)) return fallback; long value = args.get(key).getAsLong(); if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Integer is outside its range: " + key); return (int) value; }
    private static double number(JsonObject args, String key, double fallback) { return args.has(key) ? args.get(key).getAsDouble() : fallback; }
    private static boolean bool(JsonObject args, String key, boolean fallback) { return args.has(key) ? args.get(key).getAsBoolean() : fallback; }
    private static List<String> strings(JsonObject args, String key) { if (!args.has(key)) return List.of(); List<String> values = new ArrayList<>(); args.getAsJsonArray(key).forEach(value -> values.add(value.getAsString())); return values; }
}

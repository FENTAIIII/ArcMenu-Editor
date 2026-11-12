package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.mcp.EditorMcp;
import com.fentai.arcmenu.editor.mcp.McpScreen;
import com.fentai.arcmenu.editor.net.EditorPayload;
import com.fentai.arcmenu.editor.net.EffectPayload;
import com.fentai.arcmenu.editor.net.FrameAssembler;
import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.AckPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ApplyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ClosePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.CreatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DeleteManyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DeletePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DeleteTemplatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.DuplicatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ErrorPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.GroupPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.HelloPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.InstantiateTemplatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.MovePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.PacketLimitException;
import com.fentai.arcmenu.protocol.EditorProtocol.ProbePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.RedoPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ReorderPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ReparentPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.ResizePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SavePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SaveTemplatePacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SetPropertyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SnapshotPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.StringLimitException;
import com.fentai.arcmenu.protocol.EditorProtocol.SwitchTabPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.UndoPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.UngroupPacket;
import com.fentai.arcmenu.protocol.EffectProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol.SceneFrame;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

public final class ArcMenuEditorClient implements ClientModInitializer
{
    public static final String CLIENT_VERSION = EditorProtocol.EDITOR_VERSION;
    private static final EditorState STATE = new EditorState();
    private static final FrameAssembler ASSEMBLER = new FrameAssembler();
    private static final FrameAssembler EFFECT_ASSEMBLER = new FrameAssembler();
    private static List<String> effectSelection = List.of();
    private static long sceneRequestCount;
    private static int nextMessageId;
    private static int effectWireVersion = 2;
    private static final EffectPlaybackInput PLAYBACK_INPUT = new EffectPlaybackInput();
    private static final SceneReplies SCENE_REPLIES = new SceneReplies();

    public static long sceneRequestCount()
    {
        return sceneRequestCount;
    }

    private static void applyEffectSelection(Minecraft client)
    {
        if (effectSelection.isEmpty() || effectSelection.stream().anyMatch(id -> STATE.find(id) == null))
        {
            return;
        }
        STATE.selectAll(effectSelection);
        effectSelection = List.of();
        if (client.screen instanceof EffectEditorScreen screen)
        {
            screen.serverStateChanged();
        }
    }

    public static final EffectState EFFECT = new EffectState();

    @Override
    public void onInitializeClient()
    {
        EditorMcp.initialize();
        EditorWorldCompositor.initialize();
        WorkspaceClient.initialize();
        MenuInputClient.initialize();
        VisionCursorClient.initialize();
        PayloadTypeRegistry.serverboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(EditorPayload.TYPE,
                (payload, context) -> receive(context.client(), payload));
        PayloadTypeRegistry.serverboundPlay().register(EffectPayload.TYPE, EffectPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EffectPayload.TYPE, EffectPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(EffectPayload.TYPE,
                (payload, context) -> receiveEffect(context.client(), payload));
        EFFECT.transport(packet -> ClientPlayNetworking
                .send(new EffectPayload(EffectProtocol.encode(packet, effectWireVersion))));
        EFFECT.localControl(EffectAudioPreview.INSTANCE::control);
        ClientTickEvents.END_CLIENT_TICK.register(client -> EFFECT.tick());
        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            var exit = PLAYBACK_INPUT.poll(client.player != null && client.screen == null && client.isWindowActive(),
                    client.options.keyShift.isDown(), client.options.keySwapOffhand.isDown());
            if (exit != null && effectWireVersion >= 4 && ClientPlayNetworking.canSend(EffectPayload.TYPE))
            {
                ClientPlayNetworking.send(new EffectPayload(EffectProtocol.encode(exit, effectWireVersion)));
            }
        });
        ClientTickEvents.END_CLIENT_TICK
                .register(client -> EffectAudioPreview.INSTANCE.update(client.screen instanceof EffectEditorScreen
                        || client.screen instanceof EffectWorkspaceChild || client.screen instanceof McpScreen));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> EffectAudioPreview.INSTANCE.close());
        ClientPlayConnectionEvents.JOIN
                .register((listener, sender, client) -> send(new HelloPacket(CLIENT_VERSION)));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) ->
        {
            effectWireVersion = 2;
            if (ClientPlayNetworking.canSend(EffectPayload.TYPE))
            {
                ClientPlayNetworking.send(new EffectPayload(EffectProtocol.encode(new EffectProtocol.Hello(2), 2)));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) ->
        {
            ASSEMBLER.clear();
            WorkspaceClient.reset();
            EFFECT_ASSEMBLER.clear();
            EFFECT.clear();
            SCENE_REPLIES.clear();
            effectSelection = List.of();
            effectWireVersion = 2;
            PLAYBACK_INPUT.clear();
            EffectAudioPreview.INSTANCE.disconnected();
            STATE.clear();
            EditorMcp.disconnected();
            EditorViewport.clear();
        });
    }

    private static void receive(Minecraft client, EditorPayload payload)
    {
        try
        {
            byte[] packetBytes = ASSEMBLER.accept(payload.data());
            if (packetBytes == null)
            {
                return;
            }
            EditorProtocol.Packet packet = EditorProtocol.decode(packetBytes);
            if (packet instanceof SnapshotPacket snapshot)
            {
                STATE.update(snapshot);
                applyEffectSelection(client);
                releaseSceneEdits(SCENE_REPLIES.snapshot());
                flushSceneEdits();
                if (client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverStateChanged();
                }
                else if (client.screen instanceof EditorScreen screen)
                {
                    screen.serverStateChanged();
                }
                else if (client.screen instanceof EffectWorkspaceChild || client.screen instanceof McpScreen)
                { /* Keep forms open while server snapshots arrive. */ }
                else if (WorkspaceClient.keepingScreen(client.screen, false, snapshot.menuId()))
                { /* Retain the current draft while browsing project files. */ }
                else
                {
                    client.setScreen(new EditorScreen(STATE));
                }
                WorkspaceClient.opened(false, snapshot.menuId());
            }
            else if (packet instanceof SwitchTabPacket switched)
            {
                STATE.activeTab(switched.tab());
                if (client.screen instanceof EditorScreen screen)
                {
                    screen.serverStateChanged();
                }
            }
            else if (packet instanceof AckPacket ack)
            {
                long gestureId = STATE.apply(ack);
                releaseSceneEdits(SCENE_REPLIES.acknowledge(ack.operation()));
                flushSceneEdits();
                if (client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverOperationCompleted(ack.operation(), gestureId);
                }
                else if (client.screen instanceof EditorScreen screen)
                {
                    screen.serverOperationCompleted(ack.operation(), gestureId);
                }
            }
            else if (packet instanceof ErrorPacket error)
            {
                STATE.error(EditorI18n.text("arcmenu_editor.status.error", error.message()));
                EFFECT.reject();
                releaseSceneEdits(SCENE_REPLIES.reject(error.revision() >= 0));
                if (client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverOperationFailed();
                }
                else if (client.screen instanceof EditorScreen screen)
                {
                    screen.serverOperationFailed();
                }
            }
            EditorMcp.scenePacket(packet);
        }
        catch (RuntimeException error)
        {
            STATE.error(EditorI18n.text("arcmenu_editor.status.protocol_error", error.getMessage()));
        }
    }

    private static void receiveEffect(Minecraft client, EffectPayload payload)
    {
        try
        {
            byte[] bytes = EFFECT_ASSEMBLER.accept(payload.data());
            if (bytes == null)
            {
                return;
            }
            var packet = EffectProtocol.decode(bytes);
            if (packet instanceof EffectProtocol.Hello hello)
            {
                if (hello.version() >= 3 && effectWireVersion < 3 && ClientPlayNetworking.canSend(EffectPayload.TYPE))
                {
                    int negotiatedVersion = Math.min(EffectProtocol.VERSION, hello.version());
                    ClientPlayNetworking.send(
                            new EffectPayload(EffectProtocol.encode(new EffectProtocol.Hello(negotiatedVersion), 2)));
                    effectWireVersion = negotiatedVersion;
                }
            }
            else if (packet instanceof EffectProtocol.Playback playback)
            {
                PLAYBACK_INPUT.update(playback);
            }
            else if (packet instanceof EffectProtocol.Transport transport)
            {
                var previous = EFFECT.snapshot();
                if (EFFECT.update(transport))
                {
                    EffectAudioPreview.INSTANCE.received(EFFECT.snapshot());
                    if ((previous.dirty() != transport.dirty() || previous.playing() != transport.playing()
                            || previous.autoKey() != transport.autoKey())
                            && client.screen instanceof EffectEditorScreen screen)
                    {
                        screen.serverStateChanged();
                    }
                }
            }
            else if (packet instanceof SceneFrame frame)
            {
                if (EFFECT.active() && STATE.update(frame) && client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverStateChanged();
                }
            }
            else if (packet instanceof EffectProtocol.Snapshot snapshot)
            {
                boolean opening = !EFFECT.active();
                if (EFFECT.active() && !EFFECT.snapshot().id().equals(snapshot.id()))
                {
                    effectSelection = List.of();
                    SCENE_REPLIES.clear();
                }
                EFFECT.update(snapshot);
                EffectAudioPreview.INSTANCE.received(snapshot);
                if (opening)
                {
                    STATE.activeTab(EditorProtocol.TAB_FRONTEND);
                }
                if (client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverStateChanged();
                }
                else if (client.screen instanceof EffectWorkspaceChild || client.screen instanceof McpScreen)
                { /* Keep forms open while timeline snapshots arrive. */ }
                else if (WorkspaceClient.keepingScreen(client.screen, true, snapshot.id()))
                { /* Retain the project manager until an explicit project open. */ }
                else
                {
                    client.setScreen(new EffectEditorScreen(STATE));
                }
                WorkspaceClient.opened(true, snapshot.id());
            }
            else if (packet instanceof EffectProtocol.Viewport viewport)
            {
                if (EFFECT.update(viewport) && client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverStateChanged();
                }
            }
            else if (packet instanceof EffectProtocol.Ack ack)
            {
                EFFECT.acknowledge(ack.requestId());
                flushSceneEdits();
                if (client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverStateChanged();
                }
            }
            else if (packet instanceof EffectProtocol.Selection selection)
            {
                effectSelection = selection.ids();
                applyEffectSelection(client);
            }
            else if (packet instanceof EffectProtocol.Error error)
            {
                EFFECT.reject();
                EffectAudioPreview.INSTANCE.fail();
                releaseSceneEdits(SCENE_REPLIES.clear());
                STATE.error(error.message());
                if (client.screen instanceof EffectEditorScreen screen)
                {
                    screen.serverOperationFailed();
                }
            }
            else if (packet instanceof EffectProtocol.Closed)
            {
                boolean active = EFFECT.active();
                EFFECT.clear();
                SCENE_REPLIES.clear();
                effectSelection = List.of();
                EffectAudioPreview.INSTANCE.stop();
                if (active && (EditorScreen.isEditor(client.screen) || client.screen instanceof EffectDialog))
                {
                    client.setScreen(null);
                }
            }
            EditorMcp.effectPacket(packet);
        }
        catch (RuntimeException error)
        {
            STATE.error(EditorI18n.text("arcmenu_editor.status.protocol_error", error.getMessage()));
        }
    }

    public static void send(EditorProtocol.Packet packet)
    {
        if (ClientPlayNetworking.canSend(EditorPayload.TYPE))
        {
            try
            {
                byte[] bytes = EditorProtocol.encode(packet);
                if (EFFECT.active() && !(packet instanceof HelloPacket)
                        && !(packet instanceof ClosePacket))
                {
                    SCENE_REPLIES.enqueue(packet);
                    sceneRequestCount++;
                    EFFECT.beginSceneEdit();
                    flushSceneEdits();
                    return;
                }
                sceneRequestCount++;
                STATE.expectReply(packet);
                sendEncoded(bytes);
            }
            catch (RuntimeException error)
            {
                localFailure(error);
            }
        }
        else
        {
            STATE.error(EditorI18n.text("arcmenu_editor.status.no_channel"));
        }
    }

    private static void releaseSceneEdits(int count)
    {
        for (int i = 0; i < count; i++)
        {
            EFFECT.endSceneEdit();
        }
    }

    private static void flushSceneEdits()
    {
        if (!EFFECT.active() || EFFECT.inFlightRequest() != 0 || EFFECT.beforeScenePending()
                || STATE.snapshot() == null)
        {
            return;
        }
        var packet = SCENE_REPLIES.dispatch(STATE.snapshot().revision());
        if (packet == null)
        {
            return;
        }
        try
        {
            byte[] bytes = EditorProtocol.encode(packet);
            STATE.expectReply(packet);
            sendEncoded(bytes);
        }
        catch (RuntimeException error)
        {
            localFailure(error);
        }
    }

    private static void sendEncoded(byte[] bytes)
    {
        if (bytes.length <= EditorProtocol.MAX_FRAME_DATA)
        {
            ClientPlayNetworking.send(new EditorPayload(bytes));
        }
        else
        {
            for (byte[] frame : EditorProtocol.frame(++nextMessageId, bytes))
            {
                ClientPlayNetworking.send(new EditorPayload(frame));
            }
        }
    }

    private static void localFailure(RuntimeException error)
    {
        String message = error instanceof StringLimitException limit
                ? EditorI18n.text("arcmenu_editor.status.string_too_long", limit.field(), limit.byteCount(),
                        EditorProtocol.MAX_STRING_BYTES)
                : error instanceof PacketLimitException limit
                        ? EditorI18n.text("arcmenu_editor.status.packet_too_large", limit.byteCount(),
                                EditorProtocol.MAX_PACKET_BYTES)
                        : EditorI18n.text("arcmenu_editor.status.protocol_error", error.getMessage());
        STATE.error(message);
        releaseSceneEdits(SCENE_REPLIES.clear());
        var client = Minecraft.getInstance();
        if (client.screen instanceof EffectEditorScreen screen)
        {
            screen.serverOperationFailed();
        }
        else if (client.screen instanceof EditorScreen screen)
        {
            screen.serverOperationFailed();
        }
        EditorMcp.scenePacket(new ErrorPacket(-1, message));
    }

    /** The Effect scene channel waits for the authoritative snapshot following a final ACK. */
    static final class SceneReplies
    {
        private final ArrayDeque<EditorProtocol.Packet> pending = new ArrayDeque<>();
        private boolean sent;
        private boolean waitingSnapshot;

        void enqueue(EditorProtocol.Packet packet)
        {
            if (pending.size() >= 1024)
            {
                throw new IllegalArgumentException("Too many pending Effect scene operations");
            }
            pending.addLast(packet);
        }

        EditorProtocol.Packet dispatch(long revision)
        {
            if (sent || pending.isEmpty())
            {
                return null;
            }
            sent = true;
            return withRevision(pending.getFirst(), revision);
        }

        int acknowledge(byte operation)
        {
            if (!sent || operation(pending.getFirst()) != operation)
            {
                return 0;
            }
            if (snapshotFollows(pending.getFirst()))
            {
                waitingSnapshot = true;
                return 0;
            }
            return complete();
        }

        int snapshot()
        {
            return waitingSnapshot ? complete() : 0;
        }

        int reject(boolean hasDraft)
        {
            if (pending.isEmpty())
            {
                return 0;
            }
            int discarded = pending.size() - 1;
            while (pending.size() > 1)
            {
                pending.removeLast();
            }
            if (hasDraft && sent)
            {
                waitingSnapshot = true;
                return discarded;
            }
            return discarded + clear();
        }

        int clear()
        {
            int count = pending.size();
            pending.clear();
            sent = waitingSnapshot = false;
            return count;
        }

        private int complete()
        {
            pending.removeFirst();
            sent = waitingSnapshot = false;
            return 1;
        }

        private static boolean snapshotFollows(EditorProtocol.Packet packet)
        {
            return switch (packet)
            {
            case ProbePacket ignored -> false;
            case MovePacket value -> value.finalUpdate();
            case ResizePacket value -> value.finalUpdate();
            case SetPropertyPacket value -> value.finalUpdate();
            default -> true;
            };
        }

        private static byte operation(EditorProtocol.Packet packet)
        {
            return switch (packet)
            {
            case MovePacket ignored -> EditorProtocol.OP_MOVE;
            case ResizePacket ignored -> EditorProtocol.OP_RESIZE;
            case SwitchTabPacket ignored -> EditorProtocol.OP_TAB;
            case UndoPacket ignored -> EditorProtocol.OP_UNDO;
            case RedoPacket ignored -> EditorProtocol.OP_REDO;
            case SavePacket ignored -> EditorProtocol.OP_SAVE;
            case ApplyPacket ignored -> EditorProtocol.OP_APPLY;
            case ProbePacket ignored -> EditorProtocol.OP_PROBE;
            case CreatePacket ignored -> EditorProtocol.OP_CREATE;
            case DeletePacket ignored -> EditorProtocol.OP_DELETE;
            case DeleteManyPacket ignored -> EditorProtocol.OP_DELETE_MANY;
            case DuplicatePacket ignored -> EditorProtocol.OP_DUPLICATE;
            case GroupPacket ignored -> EditorProtocol.OP_GROUP;
            case UngroupPacket ignored -> EditorProtocol.OP_UNGROUP;
            case ReorderPacket ignored -> EditorProtocol.OP_REORDER;
            case ReparentPacket ignored -> EditorProtocol.OP_REPARENT;
            case SetPropertyPacket ignored -> EditorProtocol.OP_PROPERTY;
            case SaveTemplatePacket ignored -> EditorProtocol.OP_TEMPLATE_SAVE;
            case InstantiateTemplatePacket ignored -> EditorProtocol.OP_TEMPLATE_INSTANTIATE;
            case DeleteTemplatePacket ignored -> EditorProtocol.OP_TEMPLATE_DELETE;
            default -> -1;
            };
        }

        private static EditorProtocol.Packet withRevision(EditorProtocol.Packet packet, long revision)
        {
            return switch (packet)
            {
            case MovePacket v -> new MovePacket(revision, v.tab(), v.nodeId(),
                    v.pointer(), v.grabOffsetX(), v.grabOffsetY(), v.gestureId(), v.finalUpdate());
            case ResizePacket v -> new ResizePacket(revision, v.tab(), v.nodeId(),
                    v.pointer(), v.gestureId(), v.finalUpdate());
            case UndoPacket ignored -> new UndoPacket(revision);
            case RedoPacket ignored -> new RedoPacket(revision);
            case SavePacket ignored -> new SavePacket(revision);
            case ApplyPacket ignored -> new ApplyPacket(revision);
            case ProbePacket v -> new ProbePacket(revision, v.tab(), v.pointer());
            case CreatePacket v ->
                new CreatePacket(revision, v.tab(), v.kind(), v.parentId(), v.initialSource());
            case DeletePacket v -> new DeletePacket(revision, v.tab(), v.nodeId());
            case DeleteManyPacket v ->
                new DeleteManyPacket(revision, v.tab(), v.nodeIds());
            case DuplicatePacket v -> new DuplicatePacket(revision, v.tab(), v.nodeIds(),
                    v.targetParentId(), v.preserveParents());
            case GroupPacket v -> new GroupPacket(revision, v.nodeIds());
            case UngroupPacket v -> new UngroupPacket(revision, v.nodeId());
            case ReorderPacket v ->
                new ReorderPacket(revision, v.tab(), v.nodeIds(), v.beforeId());
            case ReparentPacket v ->
                new ReparentPacket(revision, v.nodeIds(), v.targetParentId(), v.beforeId());
            case SetPropertyPacket v -> new SetPropertyPacket(revision, v.tab(),
                    v.nodeId(), v.key(), v.value(), v.gestureId(), v.finalUpdate());
            case SaveTemplatePacket v ->
                new SaveTemplatePacket(revision, v.nodeId(), v.templateId());
            case InstantiateTemplatePacket v ->
                new InstantiateTemplatePacket(revision, v.templateId());
            case DeleteTemplatePacket v ->
                new DeleteTemplatePacket(revision, v.templateId());
            default -> packet;
            };
        }
    }

    public static Path preferencesFile()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("arcmenu-editor.properties");
    }

    public static EditorState state()
    {
        return STATE;
    }

    public static Path selectionCalibrationFile()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("arcmenu-editor-selection-calibration.properties");
    }
}

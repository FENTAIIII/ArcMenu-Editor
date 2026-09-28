package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.net.EditorPayload;
import com.fentai.arcmenu.editor.net.FrameAssembler;
import com.fentai.arcmenu.protocol.EditorProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;

public final class ArcMenuEditorClient implements ClientModInitializer {
    public static final String CLIENT_VERSION = EditorProtocol.EDITOR_VERSION;
    private static final EditorState STATE = new EditorState();
    private static final FrameAssembler ASSEMBLER = new FrameAssembler();

    @Override
    public void onInitializeClient() {
        EditorWorldCompositor.initialize();
        PayloadTypeRegistry.serverboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(EditorPayload.TYPE, EditorPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(EditorPayload.TYPE, (payload, context) -> receive(context.client(), payload));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> send(new EditorProtocol.HelloPacket(CLIENT_VERSION)));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            ASSEMBLER.clear();
            EditorViewport.clear();
        });
    }

    private static void receive(Minecraft client, EditorPayload payload) {
        try {
            byte[] packetBytes = ASSEMBLER.accept(payload.data());
            if (packetBytes == null) return;
            EditorProtocol.Packet packet = EditorProtocol.decode(packetBytes);
            if (packet instanceof EditorProtocol.SnapshotPacket snapshot) {
                STATE.update(snapshot);
                if (client.screen instanceof EditorScreen screen) screen.serverStateChanged();
                else client.setScreen(new EditorScreen(STATE));
            } else if (packet instanceof EditorProtocol.AckPacket ack) {
                long gestureId = STATE.apply(ack);
                if (client.screen instanceof EditorScreen screen) screen.serverOperationCompleted(ack.operation(), gestureId);
            } else if (packet instanceof EditorProtocol.ErrorPacket error) {
                STATE.error(EditorI18n.text("arcmenu_editor.status.error", error.message()));
                if (client.screen instanceof EditorScreen screen) screen.serverOperationFailed();
            }
        } catch (RuntimeException error) {
            STATE.error(EditorI18n.text("arcmenu_editor.status.protocol_error", error.getMessage()));
        }
    }

    public static void send(EditorProtocol.Packet packet) {
        if (ClientPlayNetworking.canSend(EditorPayload.TYPE)) {
            STATE.expectReply(packet);
            ClientPlayNetworking.send(new EditorPayload(EditorProtocol.encode(packet)));
        } else {
            STATE.error(EditorI18n.text("arcmenu_editor.status.no_channel"));
        }
    }

    public static java.nio.file.Path preferencesFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("arcmenu-editor.properties");
    }

    public static java.nio.file.Path selectionCalibrationFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("arcmenu-editor-selection-calibration.properties");
    }
}

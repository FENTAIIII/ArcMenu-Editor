package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.net.VisionPayload;
import com.fentai.arcmenu.protocol.VisionProtocol;
import com.fentai.arcmenu.protocol.VisionVisuals;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;

/** Vision uses the OS pointer without capturing it inside the game window. */
public final class VisionCursorClient {
    private static VisionProtocol.State state;
    private static VisionCursorMath.Point cursor = new VisionCursorMath.Point(0, 0);
    private static VisionCursorMath.Aim lastAim;
    private static int viewportWidth;
    private static int viewportHeight;
    private static boolean inputPrepared;
    private static boolean pendingMove;
    private static long lastMoveNanos;
    private static boolean restoreMouseCapture;
    private static boolean pointerInside;
    private static Boolean sentPresence;
    private static final VisionInputKeys INPUT_KEYS = new VisionInputKeys();

    private VisionCursorClient() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(VisionPayload.TYPE, VisionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VisionPayload.TYPE, VisionPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(VisionPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try { receive(VisionProtocol.decode(payload.data())); }
                    catch (IllegalArgumentException ignored) { /* Ignore incompatible or malformed runtime packets. */ }
                }));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            clear(false);
            VisionSceneClient.clearPreview();
            INPUT_KEYS.reset();
            hello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> { clear(false); VisionSceneClient.clearPreview(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            sampleMouse();
            VisionTextClient.tick();
            send(VisionProtocol.MOVE, false);
        });
    }

    private static void hello() {
        if (VisionSceneClient.supported() && ClientPlayNetworking.canSend(VisionPayload.TYPE)) {
            ClientPlayNetworking.send(new VisionPayload(VisionProtocol.encode(new VisionProtocol.Hello(VisionProtocol.VERSION))));
        }
    }

    private static void receive(VisionProtocol.Packet packet) {
        if (packet instanceof VisionVisuals.Hover hover) { VisionVisualClient.receive(hover); return; }
        if (packet instanceof VisionVisuals.Follow follow) { VisionVisualClient.receive(follow); return; }
        if (packet instanceof VisionProtocol.Hello) hello();
        if (packet instanceof VisionProtocol.PreviewScene preview) {
            VisionSceneClient.receivePreview(preview);
            return;
        }
        if (packet instanceof VisionProtocol.TextState text) {
            if (state != null && state.sessionId() == text.sessionId()) VisionTextClient.receive(text);
            return;
        }
        if (packet instanceof VisionProtocol.Scene scene) {
            if (state != null && state.sessionId() == scene.sessionId()) VisionSceneClient.receive(scene);
            return;
        }
        if (!(packet instanceof VisionProtocol.State next)) return;
        if (!next.active()) { clear(true); return; }
        boolean opening = state == null || state.sessionId() != next.sessionId();
        state = next;
        if (opening) {
            VisionTextClient.clear();
            VisionSceneClient.clear();
            var client = Minecraft.getInstance();
            viewportWidth = Math.max(1, client.getWindow().getScreenWidth());
            viewportHeight = Math.max(1, client.getWindow().getScreenHeight());
            sentPresence = null;
            sampleMouse();
            pendingMove = true;
        }
    }

    public static boolean active() { return state != null && state.active(); }
    public static long sessionId() { return state == null ? 0 : state.sessionId(); }

    public static boolean controlling() {
        var client = Minecraft.getInstance();
        return active() && client.player != null && client.level != null
                && client.screen == null && client.isWindowActive();
    }

    /** Called before movement processing and button dispatch, including between game ticks. */
    public static void sampleMouse() {
        if (controlling()) prepare(Minecraft.getInstance());
        else { pointerInside = false; inputPrepared = false; }
    }

    public static boolean button(int button, int action, int modifiers) {
        if (!controlling()) return false;
        sampleMouse();
        byte operation = VisionMouseInput.action(button);
        if (pointerInside && action == InputConstants.PRESS && operation != VisionMouseInput.NO_ACTION) {
            VisionTextClient.finish();
            send(operation, VisionMouseInput.shift(modifiers));
        }
        return true;
    }

    public static boolean key(KeyEvent event, int action) {
        var client = Minecraft.getInstance();
        var decision = INPUT_KEYS.accept(event.key(), action, controlling(), VisionTextClient.editing(),
                event.key() == InputConstants.KEY_ESCAPE,
                client.options.keyJump.matches(event));
        if (decision == VisionInputKeys.Action.PASS) return false;
        KeyMapping.set(InputConstants.getKey(event), false);
        client.options.keyJump.setDown(false);
        if (decision == VisionInputKeys.Action.TEXT) VisionTextClient.key(event, action);
        if (decision == VisionInputKeys.Action.CLOSE) { send(VisionProtocol.CLOSE, false); clear(true); }
        if (decision == VisionInputKeys.Action.JUMP) send(VisionProtocol.JUMP, false);
        return true;
    }

    public static VisionCursorMath.Point normalizedCursor() {
        return new VisionCursorMath.Point(cursor.x() / Math.max(1, viewportWidth), cursor.y() / Math.max(1, viewportHeight));
    }

    public static void prepareTextInput() {
        if (controlling()) prepare(Minecraft.getInstance());
    }

    public static void sendText(long editId, byte action, String value) {
        if (active() && ClientPlayNetworking.canSend(VisionPayload.TYPE))
            ClientPlayNetworking.send(new VisionPayload(VisionProtocol.encode(new VisionProtocol.TextInput(sessionId(), editId, action, value))));
    }

    private static void prepare(Minecraft client) {
        int width = Math.max(1, client.getWindow().getScreenWidth());
        int height = Math.max(1, client.getWindow().getScreenHeight());
        if (width != viewportWidth || height != viewportHeight) pendingMove = true;
        viewportWidth = width;
        viewportHeight = height;
        if (!inputPrepared) {
            inputPrepared = true;
            client.options.keyAttack.setDown(false);
            client.options.keyUse.setDown(false);
            client.options.keyPickItem.setDown(false);
            client.options.keyJump.setDown(false);
            while (client.options.keyJump.consumeClick()) { /* Drop jump clicks queued before capture. */ }
        }
        if (client.mouseHandler.isMouseGrabbed()) {
            restoreMouseCapture = true;
            client.mouseHandler.releaseMouse();
        }
        var next = new VisionCursorMath.Point(client.mouseHandler.xpos(), client.mouseHandler.ypos());
        if (!next.equals(cursor)) pendingMove = true;
        cursor = next;
        boolean inside = VisionNativeCursor.insideWindow(client.getWindow()) && VisionCursorMath.inside(cursor, width, height);
        if (inside != pointerInside) pendingMove = true;
        pointerInside = inside;
    }

    private static void send(byte action, boolean shift) {
        var client = Minecraft.getInstance();
        if (!active() || !ClientPlayNetworking.canSend(VisionPayload.TYPE) || !VisionSceneClient.ready()) return;
        if (action == VisionProtocol.MOVE && !pointerInside) {
            if (!Boolean.FALSE.equals(sentPresence)) {
                ClientPlayNetworking.send(new VisionPayload(VisionProtocol.encode(new VisionProtocol.Input(
                        state.sessionId(), 0, 0, VisionProtocol.LEAVE, false))));
                sentPresence = false;
            }
            return;
        }
        if (!controlling()) return;
        long now = System.nanoTime();
        if (action == VisionProtocol.MOVE && Boolean.TRUE.equals(sentPresence) && now - lastMoveNanos < 16_666_667L) return;
        Matrix4f projection = VisionSceneClient.viewProjection(viewportWidth, viewportHeight);
        if (projection == null) return;
        var aim = VisionCursorMath.aim(cursor, viewportWidth, viewportHeight,
                projection);
        if (action == VisionProtocol.MOVE && !pendingMove && lastAim != null
                && Math.abs(aim.yaw() - lastAim.yaw()) < .0001f && Math.abs(aim.pitch() - lastAim.pitch()) < .0001f) return;
        lastAim = aim;
        ClientPlayNetworking.send(new VisionPayload(VisionProtocol.encode(new VisionProtocol.Input(
                state.sessionId(), lastAim.yaw(), lastAim.pitch(), action, shift))));
        pendingMove = false;
        lastMoveNanos = now;
        sentPresence = true;
    }

    /** Sample and flush before extracting the GUI scene, independently of the 20 Hz game tick. */
    public static VisionCursorMath.Aim frameAim() {
        sampleMouse();
        send(VisionProtocol.MOVE, false);
        if (!pointerInside) return null;
        var projection = VisionSceneClient.viewProjection(viewportWidth, viewportHeight);
        return projection == null ? null : VisionCursorMath.aim(cursor, viewportWidth, viewportHeight, projection);
    }

    public static void render(GuiGraphicsExtractor graphics) {
        if (!controlling()) return;
        sampleMouse();
        VisionTextClient.render(graphics);
        graphics.requestCursor(pointerInside && VisionVisualClient.choosing(state.choosing()) ? CursorTypes.POINTING_HAND : CursorTypes.ARROW);
    }

    private static void clear(boolean restoreCapture) {
        boolean restore = restoreCapture && restoreMouseCapture;
        restoreMouseCapture = false;
        inputPrepared = false;
        state = null;
        VisionTextClient.clear();
        VisionSceneClient.clear();
        lastAim = null;
        pendingMove = false;
        lastMoveNanos = 0;
        pointerInside = false;
        sentPresence = null;
        var client = Minecraft.getInstance();
        if (restore && client.player != null && client.level != null && client.screen == null && client.isWindowActive()
                && !client.mouseHandler.isMouseGrabbed()) {
            client.mouseHandler.handleAccumulatedMovement();
            client.mouseHandler.grabMouse();
        }
    }

    public static JsonObject metadata() {
        var result = new JsonObject();
        result.addProperty("active", active());
        result.addProperty("controlling", controlling());
        result.addProperty("nativePointer", true);
        result.addProperty("pointerInside", pointerInside);
        result.addProperty("sessionId", state == null ? 0 : state.sessionId());
        result.addProperty("isolatedScene", VisionSceneClient.ready());
        result.addProperty("isolatedEditorPreview", VisionSceneClient.previewReady());
        result.addProperty("editingText", VisionTextClient.editing());
        result.addProperty("x", cursor.x()); result.addProperty("y", cursor.y());
        result.addProperty("width", viewportWidth); result.addProperty("height", viewportHeight);
        if (lastAim != null) { result.addProperty("yaw", lastAim.yaw()); result.addProperty("pitch", lastAim.pitch()); }
        return result;
    }
}

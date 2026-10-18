package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.net.VisionPayload;
import com.fentai.arcmenu.protocol.VisionProtocol;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.io.IOException;

/** A local cursor for explicitly enabled Vision sessions; ordinary gameplay keeps vanilla input. */
public final class VisionCursorClient {
    private static final Identifier IDLE = Identifier.parse("arcmenu:textures/gui/vision_cursor.png");
    private static final Identifier CHOOSE = Identifier.parse("arcmenu:textures/gui/vision_cursor_choose.png");
    private static final Identifier FALLBACK_IDLE = Identifier.parse("arcmenu_editor:textures/gui/vision_cursor.png");
    private static final Identifier FALLBACK_CHOOSE = Identifier.parse("arcmenu_editor:textures/gui/vision_cursor_choose.png");
    private static VisionProtocol.State state;
    private static VisionCursorMath.Point cursor = new VisionCursorMath.Point(0, 0);
    private static VisionCursorMath.Aim lastAim;
    private static int viewportWidth;
    private static int viewportHeight;
    private static boolean hudSaved;
    private static boolean hudHiddenBefore;
    private static boolean pendingMove;
    private static int[] idleSize;
    private static int[] chooseSize;
    private static final VisionInputKeys INPUT_KEYS = new VisionInputKeys();

    private VisionCursorClient() {}

    public static void initialize() {
        VisionSceneClient.initialize();
        PayloadTypeRegistry.serverboundPlay().register(VisionPayload.TYPE, VisionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VisionPayload.TYPE, VisionPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(VisionPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    try { receive(VisionProtocol.decode(payload.data())); }
                    catch (IllegalArgumentException ignored) { /* Ignore incompatible or malformed runtime packets. */ }
                }));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
            clear();
            VisionSceneClient.clearPreview();
            INPUT_KEYS.reset();
            hello();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> { clear(); VisionSceneClient.clearPreview(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!controlling()) { VisionTextClient.tick(); restoreHud(client); return; }
            prepare(client);
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
        if (!next.active()) { clear(); return; }
        boolean opening = state == null || state.sessionId() != next.sessionId();
        state = next;
        if (opening) {
            VisionTextClient.clear();
            VisionSceneClient.clear();
            var client = Minecraft.getInstance();
            viewportWidth = Math.max(1, client.getWindow().getScreenWidth());
            viewportHeight = Math.max(1, client.getWindow().getScreenHeight());
            cursor = new VisionCursorMath.Point(viewportWidth / 2.0, viewportHeight / 2.0);
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

    /** Called from vanilla's accumulated raw movement, before it would rotate the player. */
    public static boolean move(double dx, double dy) {
        if (!controlling()) return false;
        var client = Minecraft.getInstance();
        prepare(client);
        if (dx == 0 && dy == 0) return true;
        cursor = VisionCursorMath.move(cursor, dx, dy, viewportWidth, viewportHeight,
                state.sensitivityX(), state.sensitivityY());
        pendingMove = true;
        return true;
    }

    public static boolean button(int button, int action, int modifiers) {
        if (!controlling()) return false;
        if (action == 1 && button >= 0 && button <= 2) {
            VisionTextClient.finish();
            byte operation = button == 0 ? VisionProtocol.LEFT : button == 1 ? VisionProtocol.RIGHT : VisionProtocol.MIDDLE;
            send(operation, (modifiers & 1) != 0);
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
        if (decision == VisionInputKeys.Action.CLOSE) { send(VisionProtocol.CLOSE, false); clear(); }
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
        if (width != viewportWidth || height != viewportHeight) {
            cursor = new VisionCursorMath.Point(cursor.x() * width / viewportWidth, cursor.y() * height / viewportHeight);
            viewportWidth = width;
            viewportHeight = height;
            pendingMove = true;
        }
        if (!hudSaved) {
            hudHiddenBefore = hudHidden(client);
            hudSaved = true;
            client.options.keyAttack.setDown(false);
            client.options.keyUse.setDown(false);
            client.options.keyPickItem.setDown(false);
            client.options.keyJump.setDown(false);
            while (client.options.keyJump.consumeClick()) { /* Drop jump clicks queued before capture. */ }
        }
        hudHidden(client, true);
        if (!client.mouseHandler.isMouseGrabbed()) client.mouseHandler.grabMouse();
    }

    private static void send(byte action, boolean shift) {
        var client = Minecraft.getInstance();
        if (!controlling() || !ClientPlayNetworking.canSend(VisionPayload.TYPE)) return;
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
    }

    public static void render(GuiGraphicsExtractor graphics) {
        VisionSceneClient.extract(graphics);
        if (!controlling()) return;
        var client = Minecraft.getInstance();
        prepare(client);
        VisionTextClient.render(graphics);
        Identifier texture = state.choosing() ? CHOOSE : IDLE;
        if (client.getResourceManager().getResource(texture).isEmpty()) texture = state.choosing() ? FALLBACK_CHOOSE : FALLBACK_IDLE;
        int[] dimensions = state.choosing() ? chooseSize : idleSize;
        if (dimensions == null) {
            dimensions = textureSize(client, texture);
            if (state.choosing()) chooseSize = dimensions; else idleSize = dimensions;
        }
        int x = Math.min(client.getWindow().getGuiScaledWidth() - 1,
                (int) Math.round(cursor.x() * client.getWindow().getGuiScaledWidth() / viewportWidth));
        int y = Math.min(client.getWindow().getGuiScaledHeight() - 1,
                (int) Math.round(cursor.y() * client.getWindow().getGuiScaledHeight() / viewportHeight));
        int height = Math.max(1, (int) Math.round(VisionCursorMath.spriteHeight(state.cursorSize(),
                state.canvasProjection(), client.getWindow().getGuiScaledHeight(), VisionSceneClient.verticalFov())));
        int width = Math.max(1, (int) Math.round(height * dimensions[0] / (double) dimensions[1]));
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f,
                width, height, dimensions[0], dimensions[1], dimensions[0], dimensions[1]);
    }

    private static int[] textureSize(Minecraft client, Identifier texture) {
        try {
            var resource = client.getResourceManager().getResource(texture);
            if (resource.isPresent()) try (var stream = resource.get().open(); var image = NativeImage.read(stream)) {
                return new int[]{image.getWidth(), image.getHeight()};
            }
        } catch (IOException ignored) { /* The texture manager displays its missing-texture fallback. */ }
        return new int[]{16, 16};
    }

    public static void resourcesReloaded() { idleSize = null; chooseSize = null; }

    private static void clear() {
        restoreHud(Minecraft.getInstance());
        state = null;
        VisionTextClient.clear();
        VisionSceneClient.clear();
        lastAim = null;
        pendingMove = false;
    }

    private static void restoreHud(Minecraft client) {
        if (hudSaved) { hudHidden(client, hudHiddenBefore); hudSaved = false; }
    }

    private static boolean hudHidden(Minecraft client) { return client.options.hideGui; }
    private static void hudHidden(Minecraft client, boolean value) { client.options.hideGui=value; }

    public static JsonObject metadata() {
        var result = new JsonObject();
        result.addProperty("active", active());
        result.addProperty("controlling", controlling());
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

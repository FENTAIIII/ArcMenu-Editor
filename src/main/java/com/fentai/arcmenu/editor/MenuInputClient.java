package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.net.MenuInputPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import java.util.Arrays;

/** Only forwards buttons when the connected server explicitly enables its menu input channel. */
public final class MenuInputClient {
    private static boolean active;
    private static final boolean[] held = new boolean[3];
    private MenuInputClient() {}
    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(MenuInputPayload.TYPE, MenuInputPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MenuInputPayload.TYPE, MenuInputPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(MenuInputPayload.TYPE, (payload, context) -> context.client().execute(() -> {
            byte[] data = payload.data();
            if (data.length == 2 && data[0] == 1) { active = data[1] == 1;Arrays.fill(held, false); }
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> { active=false;Arrays.fill(held,false); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || VisionCursorClient.active() || client.player == null || client.screen != null || !client.isWindowActive()) {
                Arrays.fill(held,false);return;
            }
            boolean[] down = {client.options.keyAttack.isDown(), client.options.keyUse.isDown(), client.options.keyPickItem.isDown()};
            var minecraft = client;
            boolean shift = InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RSHIFT);
            for (int button=0;button<down.length;button++) {
                if (down[button] && !held[button] && ClientPlayNetworking.canSend(MenuInputPayload.TYPE))
                    ClientPlayNetworking.send(new MenuInputPayload(new byte[]{1,(byte)button,(byte)(shift?1:0)}));
                held[button]=down[button];
            }
        });
    }
}

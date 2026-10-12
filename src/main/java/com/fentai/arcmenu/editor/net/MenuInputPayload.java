package com.fentai.arcmenu.editor.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MenuInputPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<MenuInputPayload> TYPE = new Type<>(Identifier.parse("arcmenu:menu_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuInputPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeBytes(payload.data),
        buffer -> {
            int size = buffer.readableBytes();
            if (size < 2 || size > 3) throw new IllegalArgumentException("Invalid menu input packet size");
            byte[] data = new byte[size];buffer.readBytes(data);return new MenuInputPayload(data);
        });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

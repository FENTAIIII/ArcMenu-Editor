package com.fentai.arcmenu.editor.net;

import com.fentai.arcmenu.protocol.VisionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VisionPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<VisionPayload> TYPE = new Type<>(Identifier.parse(VisionProtocol.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, VisionPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data),
            buffer -> {
                int size = buffer.readableBytes();
                if (size < 6 || size > VisionProtocol.MAX_PACKET_BYTES) {
                    throw new IllegalArgumentException("Invalid Vision payload size");
                }
                byte[] data = new byte[size];
                buffer.readBytes(data);
                return new VisionPayload(data);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

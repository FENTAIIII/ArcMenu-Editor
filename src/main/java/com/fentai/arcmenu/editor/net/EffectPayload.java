package com.fentai.arcmenu.editor.net;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

public record EffectPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<EffectPayload> TYPE = new Type<>(Identifier.parse(EffectProtocol.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, EffectPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data),
            buffer -> {
                int size = buffer.readableBytes();
                if (size < 1 || size > EditorProtocol.MAX_PACKET_BYTES) {
                    throw new IllegalArgumentException("invalid ArcMenu editor payload size: " + size);
                }
                byte[] data = new byte[size];
                buffer.readBytes(data);
                return new EffectPayload(data);
            }
    );

    public EffectPayload {
        data = Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

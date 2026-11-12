package com.fentai.arcmenu.editor.net;

import com.fentai.arcmenu.protocol.EditorProtocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

public record EditorPayload(byte[] data) implements CustomPacketPayload
{
    public static final Type<EditorPayload> TYPE = new Type<>(Identifier.parse(EditorProtocol.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditorPayload> CODEC = StreamCodec
            .of((buffer, payload) -> buffer.writeBytes(payload.data), buffer ->
            {
                int size = buffer.readableBytes();
                if (size < 1 || size > EditorProtocol.MAX_PACKET_BYTES)
                {
                    throw new IllegalArgumentException("invalid ArcMenu editor payload size: " + size);
                }
                byte[] data = new byte[size];
                buffer.readBytes(data);
                return new EditorPayload(data);
            });

    public EditorPayload
    {
        data = Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data()
    {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }
}

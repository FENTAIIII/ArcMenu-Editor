package com.fentai.arcmenu.editor.net;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.WorkspaceProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.Arrays;

public record WorkspacePayload(byte[] data) implements CustomPacketPayload {
    public static final Type<WorkspacePayload> TYPE = new Type<>(Identifier.parse(WorkspaceProtocol.CHANNEL));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkspacePayload> CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeBytes(payload.data), buffer -> {
                int size = buffer.readableBytes();
                if (size < 1 || size > EditorProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Invalid workspace payload size");
                byte[] bytes = new byte[size]; buffer.readBytes(bytes); return new WorkspacePayload(bytes);
            });
    public WorkspacePayload { data = Arrays.copyOf(data, data.length); }
    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

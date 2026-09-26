package com.houseofel.fabric.net;

import com.houseofel.common.net.HandshakePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record HandshakeCustomPayload(HandshakePayload inner) implements CustomPacketPayload {

    public static final Type<HandshakeCustomPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("houseofel", "handshake"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HandshakeCustomPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public HandshakeCustomPayload decode(RegistryFriendlyByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new HandshakeCustomPayload(HandshakePayload.fromBytes(bytes));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, HandshakeCustomPayload value) {
                    buf.writeBytes(value.inner().toBytes());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

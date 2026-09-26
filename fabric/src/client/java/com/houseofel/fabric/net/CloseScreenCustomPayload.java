package com.houseofel.fabric.net;

import com.houseofel.common.net.CloseScreenPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CloseScreenCustomPayload(CloseScreenPayload inner) implements CustomPacketPayload {

    public static final Type<CloseScreenCustomPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("houseofel", "close_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseScreenCustomPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public CloseScreenCustomPayload decode(RegistryFriendlyByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new CloseScreenCustomPayload(CloseScreenPayload.fromBytes(bytes));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, CloseScreenCustomPayload value) {
                    buf.writeBytes(value.inner().toBytes());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

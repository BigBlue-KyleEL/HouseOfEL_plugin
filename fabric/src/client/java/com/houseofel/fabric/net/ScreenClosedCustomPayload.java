package com.houseofel.fabric.net;

import com.houseofel.common.net.ScreenClosedPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ScreenClosedCustomPayload(ScreenClosedPayload inner) implements CustomPacketPayload {

    public static final Type<ScreenClosedCustomPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("houseofel", "screen_closed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenClosedCustomPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public ScreenClosedCustomPayload decode(RegistryFriendlyByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new ScreenClosedCustomPayload(ScreenClosedPayload.fromBytes(bytes));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScreenClosedCustomPayload value) {
                    buf.writeBytes(value.inner().toBytes());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

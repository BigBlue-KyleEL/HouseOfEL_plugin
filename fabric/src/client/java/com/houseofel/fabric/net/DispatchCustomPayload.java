package com.houseofel.fabric.net;

import com.houseofel.common.net.DispatchPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DispatchCustomPayload(DispatchPayload inner) implements CustomPacketPayload {

    public static final Type<DispatchCustomPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("houseofel", "dispatch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DispatchCustomPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public DispatchCustomPayload decode(RegistryFriendlyByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new DispatchCustomPayload(DispatchPayload.fromBytes(bytes));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, DispatchCustomPayload value) {
                    buf.writeBytes(value.inner().toBytes());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

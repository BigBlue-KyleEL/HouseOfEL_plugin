package com.houseofel.fabric.net;

import com.houseofel.common.net.OpenScreenPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record OpenScreenCustomPayload(OpenScreenPayload inner) implements CustomPacketPayload {

    public static final Type<OpenScreenCustomPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("houseofel", "open_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreenCustomPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public OpenScreenCustomPayload decode(RegistryFriendlyByteBuf buf) {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new OpenScreenCustomPayload(OpenScreenPayload.fromBytes(bytes));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OpenScreenCustomPayload value) {
                    buf.writeBytes(value.inner().toBytes());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

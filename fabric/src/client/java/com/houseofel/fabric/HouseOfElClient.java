package com.houseofel.fabric;

import com.houseofel.common.net.GuiConstants;
import com.houseofel.common.net.HandshakePayload;
import com.houseofel.fabric.net.CloseScreenCustomPayload;
import com.houseofel.fabric.net.DispatchCustomPayload;
import com.houseofel.fabric.net.HandshakeCustomPayload;
import com.houseofel.fabric.net.OpenScreenCustomPayload;
import com.houseofel.fabric.net.ScreenClosedCustomPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HouseOfElClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("HouseOfEL");

    @Override
    public void onInitializeClient() {
        String modVersion = FabricLoader.getInstance()
                .getModContainer("houseofel-gui")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        // --- Register payload types ---

        // C2S (client → server)
        PayloadTypeRegistry.serverboundPlay().register(
                HandshakeCustomPayload.TYPE, HandshakeCustomPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                DispatchCustomPayload.TYPE, DispatchCustomPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ScreenClosedCustomPayload.TYPE, ScreenClosedCustomPayload.CODEC);

        // S2C (server → client)
        PayloadTypeRegistry.clientboundPlay().register(
                OpenScreenCustomPayload.TYPE, OpenScreenCustomPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                CloseScreenCustomPayload.TYPE, CloseScreenCustomPayload.CODEC);

        // --- Receivers ---

        ClientPlayNetworking.registerGlobalReceiver(
                OpenScreenCustomPayload.TYPE,
                (payload, context) -> {
                    var data = payload.inner();
                    LOGGER.info("[HouseOfEL] open_screen: id={}", data.screenId());
                    Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(new HouseOfElScreen(
                                    data.screenId(), data.canvasMin(),
                                    data.canvasMax(), data.root())));
                });

        ClientPlayNetworking.registerGlobalReceiver(
                CloseScreenCustomPayload.TYPE,
                (payload, context) -> {
                    var screenId = payload.inner().screenId();
                    LOGGER.info("[HouseOfEL] close_screen: id={}", screenId);
                    Minecraft.getInstance().execute(() -> {
                        var current = Minecraft.getInstance().screen;
                        if (current instanceof HouseOfElScreen hScreen
                                && hScreen.screenId().equals(screenId)) {
                            hScreen.closeFromServer();
                        }
                    });
                });

        // --- Handshake on join ---

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            var handshake = new HandshakePayload(modVersion, GuiConstants.SCHEMA_VERSION);
            ClientPlayNetworking.send(new HandshakeCustomPayload(handshake));
            LOGGER.info("[HouseOfEL] Sent handshake (mod v{}, schema v{})",
                    modVersion, GuiConstants.SCHEMA_VERSION);
        });

        LOGGER.info("[HouseOfEL] Client mod initialized (v{})", modVersion);
    }
}

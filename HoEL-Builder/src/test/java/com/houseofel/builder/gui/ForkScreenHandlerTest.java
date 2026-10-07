package com.houseofel.builder.gui;

import com.houseofel.common.net.*;
import com.houseofel.core.gui.ScreenService;
import com.houseofel.core.gui.UiPath;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ForkScreenHandlerTest {
    private UiPath path = UiPath.MOD;
    private boolean online = true;
    private int picks;
    private OpenScreenPayload lastOpened;
    private int refusals;
    private boolean canSpawn;
    private Object lastMessage;
    private final UUID uuid = UUID.randomUUID();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Kyle";
                case "isOnline" -> online;
                case "sendPluginMessage" -> {
                    if (GuiConstants.OPEN_SCREEN_CHANNEL.equals(a[1])) lastOpened = OpenScreenPayload.fromBytes((byte[]) a[2]);
                    yield null;
                }
                case "hasPermission" -> canSpawn;
                case "sendMessage" -> { refusals++; lastMessage = a[0]; yield null; }
                default -> throw new UnsupportedOperationException(m.getName());
            });
    private final ScreenService screens = new ScreenService(null, Logger.getAnonymousLogger());
    private final ForkScreenHandler handler = new ForkScreenHandler(screens, p -> path, Runnable::run);

    private String open() {
        assertTrue(handler.open(player, "Choose a Path",
                List.of(new ForkScreenLayout.Option("pick:QUARRYMAN", "Quarryman", "Full description.")),
                Map.of("pick:QUARRYMAN", () -> picks++)));
        return screens.getOpenScreen(player);
    }
    private void click(String id, String action) {
        handler.onDispatch(player, id, action, Map.of("npcId", new DispatchValue.IntVal(999),
                "level", new DispatchValue.IntVal(20), "choice", new DispatchValue.StringVal("TERRAFORMER")));
    }

    @Test void oneUseOfferedActionIgnoresForgedValuesAndDuplicatePackets() {
        String id = open();
        click(id, "pick:TERRAFORMER");
        assertEquals(0, picks);
        assertEquals(id, screens.getOpenScreen(player));
        click(id, "pick:QUARRYMAN");
        click(id, "pick:QUARRYMAN");
        assertEquals(1, picks);
        assertNull(screens.getOpenScreen(player));
    }
    @Test void replacementRejectsOldScreenAndLateCloseCannotClearNewOne() {
        String old = open(), current = open();
        assertNotEquals(old, current);
        click(old, "pick:QUARRYMAN");
        screens.onPluginMessageReceived(GuiConstants.SCREEN_CLOSED_CHANNEL, player, new ScreenClosedPayload(old).toBytes());
        assertEquals(current, screens.getOpenScreen(player));
        click(current, "pick:QUARRYMAN");
        assertEquals(1, picks);
    }
    @Test void closeAndCancelDoNotApplyAChoice() {
        String id = open();
        screens.onPluginMessageReceived(GuiConstants.SCREEN_CLOSED_CHANNEL, player, new ScreenClosedPayload(id).toBytes());
        click(id, "pick:QUARRYMAN");
        assertEquals(0, picks);
        id = open();
        click(id, "cancel");
        click(id, "pick:QUARRYMAN");
        assertEquals(0, picks);
    }
    @Test void vanillaAndBedrockLeaveTheirExistingFallbackInControl() {
        for (var fallback : List.of(UiPath.VANILLA_JAVA, UiPath.BEDROCK)) {
            path = fallback;
            assertFalse(handler.open(player, "title", List.of(), Map.of()));
            assertNull(screens.getOpenScreen(player));
        }
    }
    @Test void offlineOrLostCapabilityCannotDispatchAndQuitClearsSession() {
        String id = open();
        online = false; click(id, "pick:QUARRYMAN"); online = true;
        path = UiPath.VANILLA_JAVA; click(id, "pick:QUARRYMAN"); path = UiPath.MOD;
        handler.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.text("bye")));
        click(id, "pick:QUARRYMAN");
        assertEquals(0, picks);
    }
    @Test void aDifferentActiveScreenCannotReuseTheForkSession() {
        String id = open();
        screens.openScreen(player, new OpenScreenPayload("main_menu", new int[]{426,250}, null, List.of()));
        click(id, "pick:QUARRYMAN");
        assertEquals(0, picks);
        assertEquals("main_menu", screens.getOpenScreen(player));
    }
    @Test void specializationDialogRoutesModdedJavaToCustomScreenAndRechecksSpawnPermission() {
        var dialog = new com.houseofel.builder.npc.SpecializationDialog(null, null, handler);
        dialog.open(player, new org.bukkit.Location(null, 1, 2, 3));
        assertTrue(ForkScreenHandler.handles(lastOpened.screenId()));
        var panel = (GuiElement.Panel) lastOpened.root().getFirst();
        assertTrue(panel.children().stream().anyMatch(e -> e instanceof GuiElement.Label l
                && l.text().equals("New Helper — Specialization")));
        assertEquals(List.of("pick:GROUNDWORKER", "pick:LUMBERJACK", "pick:FARMER", "cancel"),
                panel.children().stream().filter(GuiElement.Button.class::isInstance)
                        .map(GuiElement.Button.class::cast).map(GuiElement.Button::action).toList());
        click(lastOpened.screenId(), "pick:GROUNDWORKER");
        assertEquals(1, refusals, "Revoked spawn permission must refuse before reaching recruitment");
        click(lastOpened.screenId(), "pick:GROUNDWORKER");
        assertEquals(1, refusals, "A consumed recruitment screen cannot run twice");
    }

    @Test void forgedDisabledRecruitmentIsRefusedAndPickerReopensWithoutCharging() {
        canSpawn = true;
        // Null charging/creation dependencies ensure neither can be reached on refusal.
        var npcs = new com.houseofel.builder.npc.BuilderNpcService(null,null,null,null);
        var dialog = new com.houseofel.builder.npc.SpecializationDialog(null,npcs,handler);
        dialog.open(player,new org.bukkit.Location(null,1,2,3));
        for (String action : List.of("pick:LUMBERJACK","pick:FARMER")) {
            String old = lastOpened.screenId();
            click(old,action);
            assertEquals(com.houseofel.builder.npc.RecruitmentAvailability.refusal(),lastMessage);
            assertNotEquals(old,lastOpened.screenId());
            assertEquals(lastOpened.screenId(),screens.getOpenScreen(player));
            int beforeReplay = refusals;
            click(old,action);
            assertEquals(beforeReplay,refusals);
        }
        assertEquals(2,refusals);
    }

}

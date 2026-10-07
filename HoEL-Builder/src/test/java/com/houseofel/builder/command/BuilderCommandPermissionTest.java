package com.houseofel.builder.command;

import com.houseofel.core.gui.ScreenService;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCommandPermissionTest {
    private final List<String> messages = new ArrayList<>();
    private int packets;
    private final ScreenService screens = new ScreenService(null, Logger.getAnonymousLogger());
    private final BuilderCommand command = new BuilderCommand(null, null, null, null, screens, null, null);

    private <T extends CommandSender> T sender(Class<T> type, Set<String> permissions) {
        UUID id = UUID.randomUUID();
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> switch (m.getName()) {
            case "hasPermission" -> permissions.contains(a[0]);
            case "isOp" -> true; // OP alone must not bypass these explicit checks.
            case "sendMessage" -> { messages.add(a[0] instanceof String text ? text : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component)a[0])); yield null; }
            case "sendPluginMessage" -> { packets++; yield null; }
            case "getUniqueId" -> id;
            case "getName" -> "test-player";
            default -> throw new UnsupportedOperationException(m.getName());
        }));
    }

    @Test void deniedPlayerCannotReachLevelMutationOrPanelEvenWhenOp() {
        Player player = sender(Player.class, Set.of());
        assertTrue(command.onCommand(player, null, "builder", new String[]{"setlevel", "7", "20"}));
        assertTrue(messages.getLast().contains("permission"));
        assertTrue(command.onCommand(player, null, "builder", new String[]{"testpanel"}));
        assertTrue(messages.getLast().contains("permission"));
        assertEquals(0, packets);
        assertNull(screens.getOpenScreen(player));
        assertFalse(command.onTabComplete(player, null, "builder", new String[]{""}).contains("setlevel"));
        assertFalse(command.onTabComplete(player, null, "builder", new String[]{""}).contains("testpanel"));
    }

    @Test void explicitPanelPermissionSendsScreenButDoesNotGrantSetlevel() {
        Player player = sender(Player.class, Set.of("houseofel.builder.testpanel"));
        command.onCommand(player, null, "builder", new String[]{"testpanel"});
        assertEquals(1, packets);
        assertNotNull(screens.getOpenScreen(player));
        command.onCommand(player, null, "builder", new String[]{"setlevel", "7", "20"});
        assertTrue(messages.getLast().contains("permission"));
    }

    @Test void explicitLevelPermissionReachesValidationButDoesNotGrantPanel() {
        Player player = sender(Player.class, Set.of("houseofel.builder.setlevel"));
        command.onCommand(player, null, "builder", new String[]{"setlevel", "bad-id", "20"});
        assertEquals("Usage: /builder setlevel <npcId> <level>", messages.getLast());
        command.onCommand(player, null, "builder", new String[]{"testpanel"});
        assertTrue(messages.getLast().contains("permission"));
        assertEquals(0, packets);
    }

    @Test void consoleRetainsSetlevelAndMalformedArgumentsAreHandled() {
        var console = sender(ConsoleCommandSender.class, Set.of());
        for (String[] args : List.of(new String[]{"setlevel"}, new String[]{"setlevel", "7"},
                new String[]{"setlevel", "bad-id", "20"}, new String[]{"setlevel", "7", "20", "extra"})) {
            assertTrue(command.onCommand(console, null, "builder", args));
            assertEquals("Usage: /builder setlevel <npcId> <level>", messages.getLast());
        }
        assertTrue(command.onTabComplete(console, null, "builder", new String[]{""}).contains("setlevel"));
        assertFalse(command.onTabComplete(console, null, "builder", new String[]{""}).contains("testpanel"));
    }

    @Test void remoteConsoleRetainsSetlevel() {
        var rcon = sender(RemoteConsoleCommandSender.class, Set.of());
        command.onCommand(rcon, null, "builder", new String[]{"setlevel", "bad-id", "20"});
        assertEquals("Usage: /builder setlevel <npcId> <level>", messages.getLast());
        assertTrue(command.onTabComplete(rcon, null, "builder", new String[]{""}).contains("setlevel"));
    }

    @Test void nonConsoleSenderDoesNotInheritConsoleBypass() {
        var other = sender(CommandSender.class, Set.of());
        command.onCommand(other, null, "builder", new String[]{"setlevel", "7", "20"});
        assertTrue(messages.getLast().contains("permission"));
    }

    @Test void shippedPermissionDefaultsAreExplicitlyFalse() throws Exception {
        try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String node : List.of("setlevel", "testpanel")) {
                String key = "permissions.houseofel.builder." + node + ".default";
                assertEquals(false, config.get(key), key);
            }
        }
    }
    @Test void unavailableSpawnArgumentsUseSameRefusalWithoutCreatingAnything() {
        var player = sender(Player.class,Set.of("houseofel.builder.spawn"));
        for (String spec : List.of("lumberjack","FARMER")) {
            command.onCommand(player,null,"builder",new String[]{"spawn",spec});
            assertEquals(com.houseofel.builder.npc.RecruitmentAvailability.MESSAGE,messages.getLast());
        }
        var console = sender(ConsoleCommandSender.class,Set.of());
        command.onCommand(console,null,"builder",new String[]{"spawn","farmer"});
        assertEquals(com.houseofel.builder.npc.RecruitmentAvailability.MESSAGE,messages.getLast());
        assertEquals(0,packets);
    }

}

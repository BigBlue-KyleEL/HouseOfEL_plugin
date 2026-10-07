package com.houseofel.builder.job;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class JobDiagnosticsTest {
    private final YamlConfiguration config = new YamlConfiguration();
    private final Logger logger = Logger.getAnonymousLogger();
    private final List<LogRecord> records = new ArrayList<>();
    private Plugin plugin;

    @BeforeEach void setup() throws Exception {
        ClearingTargetTest.installServerTags();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() {}
            public void close() {}
        });
        plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getConfig" -> config;
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private Block block() {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getX", "getY", "getZ" -> 1;
                    case "getType" -> Material.AIR;
                    case "getBlockData" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test void spongeDiagnosticsDefaultOffCanBeEnabledAndDoNotSuppressWarnings() {
        var listener = new SpongeAbsorptionDiagnosticListener(plugin);
        var event = new SpongeAbsorbEvent(block(), new ArrayList<>());
        listener.onAbsorb(event);
        assertTrue(records.isEmpty(), "missing key must be off for existing server configs");
        config.set("helpers.diagnostics.enabled", false);
        listener.onAbsorb(event);
        assertTrue(records.isEmpty());
        config.set("helpers.diagnostics.enabled", true);
        listener.onAbsorb(event);
        assertEquals(1, records.size());
        assertTrue(records.getFirst().getMessage().contains("Sponge diagnostic"));
        config.set("helpers.diagnostics.enabled", false);
        listener.onAbsorb(event);
        logger.warning("ordinary warning");
        logger.severe("ordinary error");
        assertEquals(List.of(Level.INFO, Level.WARNING, Level.SEVERE),
                records.stream().map(LogRecord::getLevel).toList());
        assertFalse(event.isCancelled(), "diagnostics must not alter absorption");
    }

    @Test void cofferdamScansKeepResultsButOnlyLogWhenEnabled() throws Exception {
        Block block = block();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, a) -> {
                    if (m.getName().equals("getBlockAt")) return block;
                    throw new UnsupportedOperationException(m.getName());
                });
        TextDisplay label = (TextDisplay) Proxy.newProxyInstance(TextDisplay.class.getClassLoader(),
                new Class<?>[]{TextDisplay.class}, (p, m, a) -> {
                    if (m.getName().equals("setPersistent")) return null;
                    throw new UnsupportedOperationException(m.getName());
                });
        var task = new CofferdamJobTask(plugin, null, null, null, null, UUID.randomUUID(),
                null, null, null, label, world, 0, 2, 0, 2, 0, 2, null, null);
        var anchors = CofferdamJobTask.class.getDeclaredMethod("selectDrainAnchors");
        var drain = CofferdamJobTask.class.getDeclaredMethod("drainInterior");
        anchors.setAccessible(true);
        drain.setAccessible(true);
        assertEquals(List.of(), anchors.invoke(task));
        assertEquals(0, drain.invoke(task));
        assertTrue(records.isEmpty());
        config.set("helpers.diagnostics.enabled", true);
        assertEquals(List.of(), anchors.invoke(task));
        assertEquals(0, drain.invoke(task));
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(r -> r.getMessage().startsWith("[cofferdam-debug]")));
    }

    @Test void shippedDiagnosticsDefaultIsFalse() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var shipped = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals(false, shipped.get("helpers.diagnostics.enabled"));
        }
    }
}

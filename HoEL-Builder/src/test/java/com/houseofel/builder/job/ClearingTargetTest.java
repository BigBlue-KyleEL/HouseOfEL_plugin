package com.houseofel.builder.job;

import com.houseofel.builder.gui.ClearingTargetPool;
import com.houseofel.builder.gui.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Real target and YAML code, with a small server-supplied tag fixture (no running world). */
class ClearingTargetTest {
    private static final Map<String, Set<Material>> TAGS = new HashMap<>();
    private static final Map<String, Set<Material>> ORES = Map.of(
            "coal_ores", Set.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE),
            "copper_ores", Set.of(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE),
            "iron_ores", Set.of(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE),
            "gold_ores", Set.of(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE),
            "redstone_ores", Set.of(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE),
            "lapis_ores", Set.of(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE),
            "diamond_ores", Set.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE),
            "emerald_ores", Set.of(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE));

    @BeforeAll
    static void installServerTags() throws ReflectiveOperationException {
        TAGS.putAll(ORES);
        Set<Material> pickaxe = EnumSet.of(Material.STONE, Material.INFESTED_STONE,
                Material.DEEPSLATE, Material.COBBLESTONE, Material.FURNACE, Material.HOPPER,
                Material.DIAMOND_BLOCK, Material.IRON_BLOCK, Material.SPAWNER,
                Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS);
        ORES.values().forEach(pickaxe::addAll);
        TAGS.put("mineable/pickaxe", pickaxe);
        TAGS.put("mineable/shovel", EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.SAND, Material.GRAVEL));
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> Logger.getLogger("ClearingTargetTest");
                    case "getName", "getVersion", "getBukkitVersion" -> "ClearingTargetTest";
                    case "getTag" -> tag((NamespacedKey) args[1]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        // Bukkit.setServer logs Paper's build-info ServiceLoader, available only in a server jar.
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    private static Tag<Material> tag(NamespacedKey key) {
        return new Tag<>() {
            public boolean isTagged(Material material) { return getValues().contains(material); }
            public Set<Material> getValues() { return Set.copyOf(TAGS.getOrDefault(key.getKey(), Set.of())); }
            public NamespacedKey getKey() { return key; }
        };
    }

    @Test
    void poolExcludesEveryOreAndSpawnerButKeepsStorageAndUtilityBlocks() {
        Set<Material> pool = ClearingTargetPool.allowedMaterials();
        ORES.values().forEach(ores -> ores.forEach(ore -> assertFalse(pool.contains(ore), ore.name())));
        for (Material material : Set.of(Material.SPAWNER, Material.NETHER_QUARTZ_ORE,
                Material.ANCIENT_DEBRIS, Material.OAK_LOG, Material.TRIAL_SPAWNER, Material.VAULT)) {
            assertFalse(pool.contains(material), material.name());
            assertThrows(IllegalArgumentException.class, () -> Target.specificBlock(material));
        }
        assertTrue(pool.containsAll(Set.of(Material.DIAMOND_BLOCK, Material.IRON_BLOCK, Material.FURNACE,
                Material.HOPPER, Material.GRASS_BLOCK, Material.STONE)));
        assertThrows(UnsupportedOperationException.class, () -> pool.add(Material.OAK_LOG));
    }

    @Test
    void poolUsesRuntimeTagMembershipRatherThanAHardcodedMaterialList() {
        // A synthetic server tag change proves that both membership and ore exclusions are resolved at runtime.
        TAGS.get("mineable/pickaxe").add(Material.OAK_LOG);
        try {
            assertTrue(ClearingTargetPool.allowedMaterials().contains(Material.OAK_LOG));
            TAGS.put("coal_ores", Set.of(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.OAK_LOG));
            assertFalse(ClearingTargetPool.allowedMaterials().contains(Material.OAK_LOG));
        } finally {
            TAGS.get("mineable/pickaxe").remove(Material.OAK_LOG);
            TAGS.put("coal_ores", ORES.get("coal_ores"));
        }
    }

    @Test
    void exactSelectionsDoNotInheritLegacyPairings() {
        Target stone = Target.specificBlock(Material.STONE);
        Target dirt = Target.specificBlock(Material.DIRT);
        assertTrue(stone.matches(Material.STONE));
        for (Material material : Set.of(Material.INFESTED_STONE, Material.COBBLESTONE, Material.DEEPSLATE)) {
            assertFalse(stone.matches(material));
        }
        assertTrue(dirt.matches(Material.DIRT));
        assertFalse(dirt.matches(Material.GRASS_BLOCK));
        assertTrue(Target.specificBlock(Material.GRASS_BLOCK).matches(Material.GRASS_BLOCK));
        assertFalse(Target.specificBlock(Material.GRASS_BLOCK).matches(Material.DIRT));
        assertTrue(Target.STONE.matches(Material.INFESTED_STONE));
        assertTrue(Target.DIRT.matches(Material.GRASS_BLOCK));
    }

    @Test
    void everythingProtectsSpawnersButStillIncludesOresAndInfestedBlocks() {
        assertFalse(Target.ANY_EARTH.matches(Material.SPAWNER));
        ORES.values().forEach(ores -> ores.forEach(ore -> assertTrue(Target.ANY_EARTH.matches(ore))));
        for (Material material : Set.of(Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS,
                Material.INFESTED_STONE, Material.INFESTED_DEEPSLATE, Material.HOPPER, Material.GRASS_BLOCK)) {
            assertTrue(Target.ANY_EARTH.matches(material), material.name());
        }
    }

    @Test
    void newTargetsDoNotLeakIntoExistingMenus() {
        assertArrayEquals(new Target[]{Target.STONE, Target.DIRT, Target.OAK_LOG, Target.WHEAT, Target.ANY_EARTH},
                Target.values());
        Target[] menu = Target.values();
        menu[0] = Target.specificBlock(Material.GRAVEL);
        assertSame(Target.STONE, Target.values()[0]);
    }

    @Test
    void specificTargetsAreEligibleForGroundworkerProgress() {
        for (Material material : ClearingTargetPool.allowedMaterials()) {
            assertTrue(Target.specificBlock(material).earnsGroundworkerProgress(), material.name());
        }
        assertTrue(Target.STONE.earnsGroundworkerProgress());
        assertTrue(Target.DIRT.earnsGroundworkerProgress());
        assertTrue(Target.ANY_EARTH.earnsGroundworkerProgress());
        assertFalse(Target.OAK_LOG.earnsGroundworkerProgress());
        assertFalse(Target.WHEAT.earnsGroundworkerProgress());
    }

    @Test
    void fingerprintRetainsTheSamePickaxeAndShovelBucketsForEveryPooledBlock() {
        for (Material material : ClearingTargetPool.allowedMaterials()) {
            Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getType")) return material;
                        throw new UnsupportedOperationException(method.getName());
                    });
            assertSame(Tag.MINEABLE_PICKAXE.isTagged(material) ? Target.STONE : Target.DIRT,
                    ClearJobTask.canonicalMaterialClass(block), material.name());
        }
    }

    @TempDir Path directory;

    private JobStateStore store() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> directory.toFile();
                    case "getLogger" -> Logger.getLogger("ClearingTargetTest");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new JobStateStore(plugin);
    }

    @Test
    void oldYamlWithoutMaterialOrJobTypeStillLoadsWithOriginalTargetSemantics() throws Exception {
        for (Target target : Target.values()) {
            YamlConfiguration oldSave = new YamlConfiguration();
            oldSave.set("npcId", 7);
            oldSave.set("playerId", UUID.randomUUID().toString());
            oldSave.set("worldName", "world");
            oldSave.set("target", target.name());
            oldSave.set("processedCells", 42);
            oldSave.save(directory.resolve("jobs/7.yml").toFile());
            JobState restored = store().loadAll().getFirst();
            assertEquals(JobType.CLEAR, restored.jobType);
            assertNull(restored.targetMaterial);
            assertEquals(42, restored.processedCells);
            assertSame(target, Target.fromSaved(restored.target, restored.targetMaterial));
        }
    }

    @Test
    void newYamlRoundTripsExactMaterialAndProgressWithoutConfusingLegacyStone() {
        for (Material material : Set.of(Material.STONE, Material.DIRT, Material.GRAVEL, Material.HOPPER)) {
            Target target = Target.specificBlock(material);
            JobState state = new JobState();
            state.npcId = 8;
            state.playerId = UUID.randomUUID();
            state.worldName = "world";
            state.target = target.name();
            state.targetMaterial = target.specificMaterial().name();
            state.processedCells = 57;
            state.clearedCells = 31;
            state.carried.put("COBBLESTONE", 12);
            store().save(state);
            JobState restored = store().loadAll().getFirst();
            Target loaded = Target.fromSaved(restored.target, restored.targetMaterial);
            assertEquals(target, loaded);
            assertEquals(target.hashCode(), loaded.hashCode());
            assertEquals(material, loaded.specificMaterial());
            assertEquals(57, restored.processedCells);
            assertEquals(31, restored.clearedCells);
            assertEquals(state.carried, restored.carried);
            assertNotEquals(Target.STONE, loaded);
            assertNotEquals(Target.DIRT, loaded);
        }
    }

    @Test
    void invalidSavedTargetsFailClosed() {
        for (String material : new String[]{null, "", "NOT_A_MATERIAL", "DIAMOND_ORE", "SPAWNER", "OAK_LOG"}) {
            assertThrows(IllegalArgumentException.class, () -> Target.fromSaved("SPECIFIC_BLOCK", material));
        }
        assertThrows(IllegalArgumentException.class, () -> Target.fromSaved(null, null));
        assertThrows(IllegalArgumentException.class, () -> Target.fromSaved("UNKNOWN_TARGET", null));
        assertThrows(IllegalArgumentException.class, () -> Target.specificBlock(null));
        assertSame(Target.STONE, Target.fromSaved("STONE", null));
    }
}

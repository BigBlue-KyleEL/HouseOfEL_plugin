package com.houseofel.builder.job;

import com.houseofel.builder.gui.ClearingTargetPool;
import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.ClearingPicker;
import com.houseofel.builder.gui.JobMenuLayout;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.MetadataStore;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void blankSearchUsesConfirmedQuickPicksInOrder() {
        List<Material> expected = List.of(Material.DIRT, Material.STONE, Material.GRAVEL,
                Material.SAND, Material.DEEPSLATE, Material.GRASS_BLOCK);
        assertEquals(expected, ClearingPicker.search(""));
        assertEquals(expected, ClearingPicker.search("   "));
        assertEquals(expected, ClearingPicker.search(null));
    }

    @Test
    void searchSupportsCaseWhitespaceIdsAndMultipleWordsWithoutReturningOres() {
        assertEquals(List.of(Material.GRASS_BLOCK), ClearingPicker.search("  GrAsS   BlOcK "));
        assertEquals(List.of(Material.GRASS_BLOCK), ClearingPicker.search("minecraft:grass_block"));
        assertEquals(List.of(Material.DIAMOND_BLOCK), ClearingPicker.search("diamond"));
        assertTrue(ClearingPicker.search("diamond ore").isEmpty());
        assertTrue(ClearingPicker.search("spawner").isEmpty());
        assertTrue(ClearingPicker.search("no_such_block").isEmpty());
    }

    @Test
    void searchAndPagesKeepAllMatchesBeyondTenWithoutDuplicates() {
        Set<Material> original = EnumSet.copyOf(TAGS.get("mineable/pickaxe"));
        List<Material> copper = List.of(Material.COPPER_BLOCK, Material.CUT_COPPER, Material.EXPOSED_COPPER,
                Material.WEATHERED_COPPER, Material.OXIDIZED_COPPER, Material.WAXED_COPPER_BLOCK,
                Material.WAXED_CUT_COPPER, Material.EXPOSED_CUT_COPPER, Material.WEATHERED_CUT_COPPER,
                Material.OXIDIZED_CUT_COPPER, Material.CUT_COPPER_STAIRS, Material.CUT_COPPER_SLAB,
                Material.COPPER_GRATE);
        TAGS.get("mineable/pickaxe").addAll(copper);
        try {
            List<Material> results = ClearingPicker.search("copper");
            assertEquals(copper.size(), results.size());
            assertEquals(Set.copyOf(copper), Set.copyOf(results));
            List<Material> paged = new ArrayList<>();
            for (int page = 0; page < ClearingPicker.pageCount(results); page++) {
                paged.addAll(ClearingPicker.page(results, page));
            }
            assertEquals(results, paged);
            assertEquals(ClearingPicker.page(results, 0), ClearingPicker.page(results, -1));
            assertEquals(ClearingPicker.page(results, 2), ClearingPicker.page(results, Integer.MAX_VALUE));
            assertTrue(ClearingPicker.page(List.of(), 99).isEmpty());
        } finally {
            TAGS.put("mineable/pickaxe", original);
        }
    }

    @Test
    void everythingAccessAndSpecificAvailabilityFollowTheConfirmedClassLevelMatrix() {
        Target specific = Target.specificBlock(Material.STONE);
        for (Specialization specialization : Specialization.values()) {
            for (int level : new int[]{1, 2, 3, 8, 20}) {
                assertTrue(ClearingPicker.isAllowed(specific, specialization, level));
                var expected = specialization != Specialization.GROUNDWORKER ? ClearingPicker.EverythingState.HIDDEN
                        : level < 3 ? ClearingPicker.EverythingState.LOCKED : ClearingPicker.EverythingState.AVAILABLE;
                assertEquals(expected, ClearingPicker.everythingState(specialization, level));
                assertEquals(expected == ClearingPicker.EverythingState.AVAILABLE,
                        ClearingPicker.isAllowed(Target.ANY_EARTH, specialization, level));
            }
        }
        assertTrue(ClearingPicker.isAllowed(specific, null, 1));
        assertFalse(ClearingPicker.isAllowed(Target.ANY_EARTH, null, 20));
        assertFalse(ClearingPicker.isAllowed(Target.STONE, Specialization.GROUNDWORKER, 20));
        assertFalse(ClearingPicker.isAllowed(null, Specialization.GROUNDWORKER, 20));
        assertEquals("Everything", Target.ANY_EARTH.label());
    }

    @Test
    void submitValidationRejectsAPickRemovedFromServerTags() {
        Target target = Target.specificBlock(Material.STONE);
        TAGS.get("mineable/pickaxe").remove(Material.STONE);
        try {
            assertFalse(ClearingPicker.isAllowed(target, Specialization.GROUNDWORKER, 3));
        } finally {
            TAGS.get("mineable/pickaxe").add(Material.STONE);
        }
    }

    private static NPC helperNpc() {
        var data = (MetadataStore) Proxy.newProxyInstance(MetadataStore.class.getClassLoader(),
                new Class<?>[]{MetadataStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get")) return "Bartholomew";
                    throw new UnsupportedOperationException(method.getName());
                });
        return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "data" -> data;
                    case "getName" -> "Bartholomew";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<GuiElement> children(OpenScreenPayload screen) {
        return ((GuiElement.Panel) screen.root().getFirst()).children();
    }

    @Test
    void modTargetLayoutHidesOrDisablesEverythingAndAlwaysOffersSpecificBlock() {
        for (var state : ClearingPicker.EverythingState.values()) {
            var children = children(JobMenuLayout.clearingTargetScreen(helperNpc(), state));
            var specific = children.stream().filter(e -> e.id().equals("btn_specific"))
                    .map(GuiElement.Button.class::cast).findFirst().orElseThrow();
            assertTrue(specific.enabled());
            var everything = children.stream().filter(e -> e.id().equals("btn_everything"))
                    .map(GuiElement.Button.class::cast).findFirst();
            assertEquals(state != ClearingPicker.EverythingState.HIDDEN, everything.isPresent());
            everything.ifPresent(button -> {
                assertEquals(state == ClearingPicker.EverythingState.AVAILABLE, button.enabled());
                assertEquals("Everything", button.text());
                assertEquals("houseofel:gui/button_disabled", button.textureDisabled());
            });
        }
    }

    @Test
    void temporaryModPickerFitsItsPanelAndSerializesWithTheExistingClientSchema() {
        var results = new ArrayList<>(ClearingPicker.search(""));
        results.add(Material.HOPPER);
        for (int page = 0; page < ClearingPicker.pageCount(results); page++) {
            var screen = JobMenuLayout.clearingPickerScreen(helperNpc(), "block", results, page);
            var decoded = OpenScreenPayload.fromBytes(screen.toBytes());
            assertEquals(JobMenuLayout.CLEARING_PICKER, decoded.screenId());
            var panel = (GuiElement.Panel) decoded.root().getFirst();
            var children = panel.children();
            var buttons = children.stream().filter(GuiElement.Button.class::isInstance)
                    .map(GuiElement.Button.class::cast).toList();
            assertEquals(ClearingPicker.page(results, page).size(), buttons.stream()
                    .filter(button -> button.action().startsWith("pick:")).count());
            for (var button : buttons) {
                assertTrue(button.offset()[1] >= 0);
                assertTrue(button.offset()[1] + button.size()[1] <= panel.size()[1], button.id());
                assertTrue(Math.abs(button.offset()[0]) + button.size()[0] / 2 <= panel.size()[0] / 2, button.id());
            }
            assertEquals(page > 0, buttons.stream().filter(b -> b.action().equals("previous")).findFirst().orElseThrow().enabled());
            assertEquals(page == 0, buttons.stream().filter(b -> b.action().equals("next")).findFirst().orElseThrow().enabled());
        }
    }

    @Test
    void modConfirmationShowsTheSelectedBlockAndLegacyTargetMenuStillHasFourChoices() {
        var target = Target.specificBlock(Material.GRASS_BLOCK);
        var confirm = children(JobMenuLayout.confirmScreen(helperNpc(), TaskType.CLEAR, target));
        assertTrue(confirm.stream().anyMatch(element -> element instanceof GuiElement.Label label
                && label.id().equals("selected_block") && label.text().equals("Grass Block")));
        var legacy = children(JobMenuLayout.targetScreen(helperNpc(),
                List.of(Target.STONE, Target.DIRT, Target.OAK_LOG, Target.WHEAT)));
        assertEquals(List.of("Stone", "Dirt", "Oak Log", "Wheat"), legacy.stream()
                .filter(GuiElement.Button.class::isInstance).map(GuiElement.Button.class::cast)
                .map(GuiElement.Button::text).toList());
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

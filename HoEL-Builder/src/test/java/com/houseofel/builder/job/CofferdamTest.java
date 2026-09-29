package com.houseofel.builder.job;

import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.antigrind.TaskFingerprint;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.gui.Target;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class CofferdamTest {
    @TempDir Path dir;
    World world;
    Plugin plugin;
    @BeforeEach void setup() throws Exception {
        ClearingTargetTest.installServerTags();
        Server previous=Bukkit.getServer();
        world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class[]{World.class},(p,m,a)->switch(m.getName()) {
            case "getName" -> "test";
            case "getFullTime" -> 50000L;
            case "isChunkLoaded" -> false;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        Server server=(Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class[]{Server.class},(p,m,a)-> {
            if (m.getName().equals("getWorld")) return world;
            return m.invoke(previous,a);
        });
        var field=Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null,server);
        plugin=(Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class[]{Plugin.class},(p,m,a)->switch(m.getName()) {
            case "getDataFolder" -> dir.toFile();
            case "getLogger" -> Logger.getLogger("CofferdamTest");
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    private JobState state() {
        JobState s=new JobState(); s.jobType=JobType.COFFERDAM;
        s.npcId=7; s.playerId=UUID.randomUUID(); s.worldName="test";
        s.minX=0;s.maxX=2;s.minY=10;s.maxY=13;s.minZ=0;s.maxZ=2;
        s.cofferdamId=UUID.randomUUID().toString(); s.cofferdamPhase="DRAINING";
        s.cofferdamFacing="NORTH";s.cofferdamDoor="SPRUCE_DOOR";
        s.cofferdamHelperUuid=UUID.randomUUID().toString();
        s.chests.add("9,10,11");s.damBlockPositions.add("0,10,0");
        return s;
    }
    @Test void facingWorksOnAllSidesAndTiesAreStable() {
        assertEquals("NORTH",CofferdamGeometry.facing(5,-8,0,10,0,10));
        assertEquals("SOUTH",CofferdamGeometry.facing(5,18,0,10,0,10));
        assertEquals("EAST",CofferdamGeometry.facing(18,5,0,10,0,10));
        assertEquals("WEST",CofferdamGeometry.facing(-8,5,0,10,0,10));
        assertEquals("NORTH",CofferdamGeometry.facing(5,5,0,10,0,10));
        assertEquals("NORTH",CofferdamGeometry.facing(18,-8,0,10,0,10));
    }
    @Test void smallestInteriorHasFullShellAndTwoDoorCells() {
        JobState s=state();
        var shell=CofferdamJobTask.computeBuildOrder(s.minX,s.maxX,s.minY,s.maxY,s.minZ,s.maxZ);
        Set<String> cells=new HashSet<>();
        for (int[] p:shell) assertTrue(cells.add(Arrays.toString(p)),"duplicate shell cell");
        assertEquals(3*4*3-2,shell.size());
        assertTrue(CofferdamGeometry.interior(s,1,11,1));
        assertTrue(CofferdamGeometry.interior(s,1,12,1));
        assertFalse(CofferdamGeometry.interior(s,1,13,1));
        assertTrue(CofferdamGeometry.isDoor(s,1,11,0));
        assertTrue(CofferdamGeometry.isDoor(s,1,12,0));
        assertFalse(CofferdamGeometry.isDoor(s,1,13,0));
        assertTrue(cells.contains("[1, 13, 1]"),"ceiling missing");
    }
    @Test void entranceRemainsInTheWallForEveryFacing() {
        JobState s=state();
        for (String facing:List.of("NORTH","EAST","SOUTH","WEST")) {
            s.cofferdamFacing=facing; int[] p=CofferdamGeometry.door(s);
            assertFalse(CofferdamGeometry.interior(s,p[0],p[1],p[2]));
            assertTrue(CofferdamGeometry.isDoor(s,p[0],p[1]+1,p[2]));
        }
    }
    @Test void nativeWoodMappingCoversDistinctFamiliesAndTreelessBiomes() {
        assertEquals(Material.SPRUCE_DOOR,CofferdamGeometry.nativeDoor("snowy_taiga"));
        assertEquals(Material.BIRCH_DOOR,CofferdamGeometry.nativeDoor("old_growth_birch_forest"));
        assertEquals(Material.JUNGLE_DOOR,CofferdamGeometry.nativeDoor("sparse_jungle"));
        assertEquals(Material.BAMBOO_DOOR,CofferdamGeometry.nativeDoor("bamboo_jungle"));
        assertEquals(Material.PALE_OAK_DOOR,CofferdamGeometry.nativeDoor("pale_garden"));
        assertEquals(Material.CHERRY_DOOR,CofferdamGeometry.nativeDoor("cherry_grove"));
        assertEquals(Material.ACACIA_DOOR,CofferdamGeometry.nativeDoor("savanna"));
        assertEquals(Material.MANGROVE_DOOR,CofferdamGeometry.nativeDoor("mangrove_swamp"));
        assertNull(CofferdamGeometry.nativeDoor("ocean"));
        assertNull(CofferdamGeometry.nativeDoor("desert"));
    }
    @Test void newJobSaveRoundTripsEntranceIdentityAndStorage() throws Exception {
        JobState original=state();
        var yaml=new YamlConfiguration();yaml.loadFromString(JobStateStore.encode(original).saveToString());
        JobState result=JobStateStore.decode(yaml);
        assertEquals(original.cofferdamId,result.cofferdamId);
        assertEquals(original.cofferdamFacing,result.cofferdamFacing);
        assertEquals(original.cofferdamDoor,result.cofferdamDoor);
        assertEquals(original.cofferdamHelperUuid,result.cofferdamHelperUuid);
        assertEquals(original.chests,result.chests);
        assertEquals(original.damBlockPositions,result.damBlockPositions);
        assertEquals(13,result.maxY);
    }
    @Test void legacyMaintainingRemainsRecognizableWithoutInventedEntrance() {
        var yaml=JobStateStore.encode(state());
        for (String key:List.of("cofferdamId","cofferdamFacing","cofferdamDoor","cofferdamHelperUuid")) yaml.set(key,null);
        yaml.set("cofferdamPhase","MAINTAINING");
        JobState result=JobStateStore.decode(yaml);
        assertEquals("MAINTAINING",result.cofferdamPhase);
        assertFalse(CofferdamGeometry.hasEntrance(result));
        assertNull(result.cofferdamId);
    }
    @Test void watchIsDurableIdempotentAndDoesNotLoadChunks() throws Exception {
        var service=new CofferdamWatchService(plugin,null,null,null,null);
        JobState s=state();service.add(s);
        Path file=dir.resolve("cofferdam-watches").resolve(s.cofferdamId+".yml");
        String before=Files.readString(file);
        assertEquals(218000L,YamlConfiguration.loadConfiguration(file.toFile()).getLong("expires"));
        var restored=new CofferdamWatchService(plugin,null,null,null,null);
        restored.loadSaved();restored.add(s);restored.saveAll();
        assertEquals(before,Files.readString(file));
        assertTrue(restored.contains(s.cofferdamId));
    }
    @Test void legacyMigrationUsesTheSameDurableIdOnReplay() {
        var service=new CofferdamWatchService(plugin,null,null,null,null);
        JobState a=state();a.cofferdamId=null;service.add(a);
        JobState b=state();b.cofferdamId=null;service.add(b);
        assertEquals(a.cofferdamId,b.cofferdamId);
        assertEquals(1,Objects.requireNonNull(dir.resolve("cofferdam-watches").toFile().list()).length);
    }
    private Block block(AtomicReference<Material> type) {
        return (Block)Proxy.newProxyInstance(Block.class.getClassLoader(),new Class[]{Block.class},(p,m,a)->switch(m.getName()) {
            case "getType" -> type.get();
            case "setType" -> {type.set((Material)a[0]);yield null;}
            case "getWorld" -> world;
            case "getX","getY","getZ" -> 1;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    @Test void cofferdamFreshAndRedundantWorkCannotPayFullCreditAgain() {
        Block block=block(new AtomicReference<>(Material.WATER));
        UUID helper=UUID.randomUUID();var tracker=new RedundancyTracker(Logger.getAnonymousLogger());var fresh=new FreshLedger();
        assertEquals(4,CofferdamWork.credit(block,helper,tracker,fresh));
        assertEquals(0,CofferdamWork.credit(block,helper,tracker,fresh));
        fresh.recordPlacement(block,System.currentTimeMillis());
        assertEquals(0,CofferdamWork.credit(block,UUID.randomUUID(),tracker,fresh));
    }
    @Test void landscapingNoOpDoesNotConsumeRedundancyHistory() throws Exception {
        UUID helper=UUID.randomUUID();var tracker=new RedundancyTracker(Logger.getAnonymousLogger());var fresh=new FreshLedger();
        var npc=(net.citizensnpcs.api.npc.NPC)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{net.citizensnpcs.api.npc.NPC.class},(p,m,a)->helper);
        var player=(org.bukkit.entity.Player)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{org.bukkit.entity.Player.class},(p,m,a)->helper);
        var task=new LandscaperJobTask(plugin,null,null,tracker,fresh,player,npc,null,null,null,world,
                LandscapeMode.FILL,null,0,0,0,0,0,0,1,1,null,0,0);
        var method=LandscaperJobTask.class.getDeclaredMethod("changeBlock",Block.class,Material.class);method.setAccessible(true);
        var type=new AtomicReference<>(Material.AIR);Block block=block(type);
        method.invoke(task,block,Material.AIR);
        assertEquals(Material.AIR,type.get());
        assertEquals(RedundancyTracker.CreditTier.FULL,tracker.check(helper,
                new TaskFingerprint(TaskType.LANDSCAPE,"test",1,1,1,Target.DIRT),System.currentTimeMillis()));
        fresh.recordPlacement(block,System.currentTimeMillis());
        method.invoke(task,block,Material.DIRT);
        assertEquals(Material.DIRT,type.get(),"fresh work must still perform the edit even though it earns no Toil");
    }
}

package com.houseofel.builder.choice;

import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.toil.ToilDatabase;
import net.citizensnpcs.api.npc.MetadataStore;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneChoiceServiceTest {
    @TempDir Path directory;
    private final UUID npcId = UUID.randomUUID(), ownerId = UUID.randomUUID();
    private ToilDatabase database;
    private DeathRecordStore deaths;
    private MilestoneChoiceStore choices;
    private Plugin plugin;
    private NPC npc;
    private Player player;
    private final List<Object> messages = new ArrayList<>();
    private final List<MilestoneChoiceOption> l8 = MilestoneChoiceRegistry.optionsFor(Specialization.GROUNDWORKER, 8);
    private final List<MilestoneChoiceOption> l16 = MilestoneChoiceRegistry.optionsFor(Specialization.GROUNDWORKER, 16);

    @BeforeEach void setup() throws Exception {
        plugin = proxy(Plugin.class, (m, a) -> switch (m) {
            case "getDataFolder" -> directory.toFile();
            case "getName", "namespace" -> "test";
            case "getLogger" -> Logger.getAnonymousLogger();
            default -> throw new UnsupportedOperationException(m);
        });
        database = new ToilDatabase(plugin);
        deaths = new DeathRecordStore(database, plugin.getLogger());
        choices = new MilestoneChoiceStore(database, plugin.getLogger());
        MetadataStore data = proxy(MetadataStore.class, (m, a) -> {
            if (m.equals("get")) return "Montgomery";
            throw new UnsupportedOperationException(m);
        });
        npc = proxy(NPC.class, (m, a) -> switch (m) {
            case "getUniqueId" -> npcId;
            case "getName" -> "Montgomery";
            case "data" -> data;
            default -> throw new UnsupportedOperationException(m);
        });
        player = proxy(Player.class, (m, a) -> switch (m) {
            case "getUniqueId" -> ownerId;
            case "sendMessage" -> { messages.add(a[0]); yield null; }
            default -> throw new UnsupportedOperationException(m);
        });
        ledger(16, 1000);
    }
    @AfterEach void close() { if (database != null) database.close(); }

    private void ledger(int level, int toil) throws Exception {
        try (var s = database.connection().prepareStatement("INSERT OR REPLACE INTO helper_ledger (npc_uuid,specialization,level,banked_toil,owner_uuid) VALUES (?,?,?,?,?)")) {
            s.setString(1,npcId.toString()); s.setString(2,"GROUNDWORKER"); s.setInt(3,level);
            s.setInt(4,toil); s.setString(5,ownerId.toString()); s.executeUpdate();
        }
    }
    private MilestoneChoiceService service() {
        var levels = new HelperLevelService(plugin, database, null, deaths, choices, null, null, null);
        return new MilestoneChoiceService(deaths, choices, levels);
    }
    private void oldChoice(int level, MilestoneChoiceOption option) {
        choices.save(new MilestoneChoiceRecord(npcId,level,option.storedValue(),System.currentTimeMillis()-8L*24*60*60*1000));
    }
    @Test void firstPickIsFreeAndCurrentChoiceDoesNotCharge() {
        var service = service();
        service.attempt(player,npc,8,l8.getFirst());
        assertEquals("QUARRYMAN",choices.find(npcId,8).choice());
        service.attempt(player,npc,8,l8.getFirst());
        assertEquals(1000,choices.spendableToilOf(npcId));
    }
    @Test void cooldownRefusesSwitchAndMatureRespecCostsExactly200() {
        var service = service();
        service.attempt(player,npc,8,l8.getFirst());
        service.attempt(player,npc,8,l8.getLast());
        assertEquals("QUARRYMAN",choices.find(npcId,8).choice());
        assertEquals(1000,choices.spendableToilOf(npcId));
        oldChoice(8,l8.getFirst());
        service.attempt(player,npc,8,l8.getLast());
        assertEquals("LANDSCAPER",choices.find(npcId,8).choice());
        assertEquals(800,choices.spendableToilOf(npcId));
    }
    @Test void insufficientToilAndNonOwnerNeverChangeChoice() throws Exception {
        ledger(16,199); oldChoice(8,l8.getFirst());
        service().attempt(player,npc,8,l8.getLast());
        assertEquals("QUARRYMAN",choices.find(npcId,8).choice());
        ledger(16,1000); deaths.setOwner(npcId,UUID.randomUUID());
        service().attempt(player,npc,8,l8.getLast());
        assertEquals("QUARRYMAN",choices.find(npcId,8).choice());
        assertEquals(1000,choices.spendableToilOf(npcId));
    }
    @Test void lowerLevelMissingParentAndWrongBranchAreRefused() throws Exception {
        ledger(7,1000); service().attempt(player,npc,8,l8.getFirst());
        assertNull(choices.find(npcId,8));
        ledger(16,1000); service().attempt(player,npc,16,l16.getFirst());
        assertNull(choices.find(npcId,16));
        oldChoice(8,l8.getLast()); service().attempt(player,npc,16,l16.getFirst());
        assertNull(choices.find(npcId,16));
        service().attempt(player,npc,16,l16.get(2));
        assertEquals("PATHFINDER",choices.find(npcId,16).choice());
    }
    @Test void l16QuarrymanForkAndRespecRetainSameCostAndOptions() {
        oldChoice(8,l8.getFirst());
        service().attempt(player,npc,16,l16.getFirst());
        assertEquals("COFFERDAM",choices.find(npcId,16).choice());
        oldChoice(16,l16.getFirst()); service().attempt(player,npc,16,l16.get(1));
        assertEquals("SHAFT_MINER",choices.find(npcId,16).choice());
        assertEquals(800,choices.spendableToilOf(npcId));
    }
    @Test void fabricatedOptionAndWrongSpecializationAreNeverEligible() {
        assertFalse(MilestoneChoiceService.isEligible(Specialization.GROUNDWORKER,20,8,null,
                new MilestoneChoiceOption("Fake", "Fake", "QUARRYMAN")));
        assertFalse(MilestoneChoiceService.isEligible(Specialization.FARMER,20,8,null,l8.getFirst()));
        assertFalse(MilestoneChoiceService.isEligible(Specialization.GROUNDWORKER,20,12,null,l8.getFirst()));
    }
    private interface Call { Object invoke(String method,Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},
                (p,m,a) -> call.invoke(m.getName(),a)));
    }
}

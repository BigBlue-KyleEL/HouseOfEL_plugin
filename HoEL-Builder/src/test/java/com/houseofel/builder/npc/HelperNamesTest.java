package com.houseofel.builder.npc;

import net.citizensnpcs.api.npc.MetadataStore;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HelperNamesTest {
    @Test void chosenNamesNormalizeSpacingAndAllowOrdinaryUnicodeNames() {
        for (String name : List.of("Bob","Ann Marie","O'Brien","Jean-Luc","José","Helper 2")) {
            assertEquals(name,HelperNames.validate(name,List.of()).name());
            assertTrue(HelperNames.validate(name,List.of()).valid());
        }
        assertEquals("Ann Marie",HelperNames.validate("  Ann   Marie  ",List.of()).name());
        assertEquals("José",HelperNames.validate("Jose\u0301",List.of()).name());
    }
    @Test void rejectsEmptyLongFormattingAndControlCharacters() {
        assertFalse(HelperNames.validate(null,List.of()).valid());
        for (String name : List.of("", "   ","a".repeat(25),"§cBob","&cBob","<red>Bob","Bob\nSmith","Bob\tSmith","-Bob")) {
            assertFalse(HelperNames.validate(name,List.of()).valid(),name);
        }
        assertTrue(HelperNames.validate("a".repeat(24),List.of()).valid());
    }
    @Test void collisionsAreCaseInsensitiveAndUnicodeNormalized() {
        assertFalse(HelperNames.validate("bOB",List.of("Bob")).valid());
        assertFalse(HelperNames.validate("Jose\u0301",List.of("JOSÉ")).valid());
        assertTrue(HelperNames.validate("Bob 2",List.of("Bob")).valid());
    }
    @Test void invalidNameRefusesBeforeChargesOrNpcCreation() {
        var service = new BuilderNpcService(null,null,null,null);
        List<Component> messages = new ArrayList<>();
        Player owner = (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},
                (p,m,a) -> {
                    if (m.getName().equals("sendMessage")) { messages.add((Component)a[0]); return null; }
                    throw new AssertionError(m.getName());
                });
        assertNull(service.recruitHelper(owner,null,Specialization.GROUNDWORKER,""));
        assertEquals(1,messages.size());
        assertThrows(IllegalArgumentException.class,() -> service.spawnHelper(null,Specialization.GROUNDWORKER,"§cBob"));
        assertNull(service.recruitHelper(owner,null,Specialization.FARMER,"Bob"));
        assertEquals(RecruitmentAvailability.refusal(),messages.getLast());
    }
    @Test void multiwordChatCommandsSelectLongestMatchingBaseNameRegardlessOfRegistryOrder() {
        var service = new BuilderNpcService(null,null,null,null);
        NPC ann = npc("Ann"), annMarie = npc("Ann Marie");
        for (var order : List.of(List.of(ann,annMarie),List.of(annMarie,ann))) {
            assertSame(annMarie,service.matchHelper("ANN MARIE report",order));
            assertSame(ann,service.matchHelper("Ann respec",order));
            assertNull(service.matchHelper("Annabelle report",order));
        }
        assertEquals("Ann Marie",BuilderNpcService.baseNameOf(annMarie));
    }
    private NPC npc(String baseName) {
        var data = (MetadataStore)Proxy.newProxyInstance(MetadataStore.class.getClassLoader(),new Class<?>[]{MetadataStore.class},
                (p,m,a) -> {
                    if (m.getName().equals("get")) return a[0].equals("houseofel-role") ? "builder" : baseName;
                    throw new AssertionError(m.getName());
                });
        return (NPC)Proxy.newProxyInstance(NPC.class.getClassLoader(),new Class<?>[]{NPC.class},
                (p,m,a) -> {
                    if (m.getName().equals("data")) return data;
                    if (m.getName().equals("getName")) return baseName + " the Earth-Shaper";
                    throw new AssertionError(m.getName());
                });
    }
}

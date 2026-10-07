package com.houseofel.builder.npc;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecruitmentAvailabilityTest {
    @Test void bedrockAndDialogLabelsShareExactComingSoonPolicy() {
        assertEquals("Groundworker",SpecializationForm.optionLabel(Specialization.GROUNDWORKER));
        assertEquals("Lumberjack (Coming soon)",SpecializationForm.optionLabel(Specialization.LUMBERJACK));
        assertEquals("Farmer (Coming soon)",SpecializationForm.optionLabel(Specialization.FARMER));
        for (var spec : Specialization.values()) {
            assertEquals(RecruitmentAvailability.label(spec),SpecializationForm.optionLabel(spec));
            assertEquals(spec==Specialization.GROUNDWORKER,RecruitmentAvailability.available(spec));
        }
    }
    @Test void recruitmentRefusesBeforeChargeSpawnOrSavedDataAccess() {
        List<Component> messages = new ArrayList<>();
        Player owner = (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},
                (p,m,a) -> {
                    if (m.getName().equals("sendMessage")) { messages.add((Component)a[0]); return null; }
                    throw new AssertionError("Unavailable recruitment must not touch player data: "+m.getName());
                });
        var service = new BuilderNpcService(null,null,null,null);
        for (var spec : List.of(Specialization.LUMBERJACK,Specialization.FARMER)) {
            assertNull(service.recruitHelper(owner,null,spec));
            assertEquals(RecruitmentAvailability.refusal(),messages.getLast());
        }
        assertEquals(2,messages.size());
    }
    @Test void directSpawnCannotBypassRecruitmentGuard() {
        var service = new BuilderNpcService(null,null,null,null);
        for (var spec : List.of(Specialization.LUMBERJACK,Specialization.FARMER)) {
            var error = assertThrows(IllegalArgumentException.class,() -> service.spawnHelper(null,spec));
            assertEquals(RecruitmentAvailability.MESSAGE,error.getMessage());
        }
    }
}

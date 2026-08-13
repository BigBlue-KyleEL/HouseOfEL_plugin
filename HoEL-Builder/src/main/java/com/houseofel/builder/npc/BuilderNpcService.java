package com.houseofel.builder.npc;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Creates and tags Helper (builder/gatherer) Citizens NPCs. */
public final class BuilderNpcService {

    private static final String ROLE_KEY = "houseofel-role";
    private static final String ROLE_VALUE = "builder";
    /** Assigned in order as each new Helper spawns; cycles with a number suffix once exhausted. */
    private static final List<String> NAME_ROSTER = List.of(
            "Thaddeus", "Bartholomew", "Montgomery", "Horace", "Aldric");

    private final HelperLevelService levelService;

    public BuilderNpcService(HelperLevelService levelService) {
        this.levelService = levelService;
    }

    public NPC spawnHelper(Location location, Specialization specialization) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, nextName());
        npc.data().setPersistent(ROLE_KEY, ROLE_VALUE);
        npc.spawn(location);
        levelService.assign(npc.getId(), specialization);
        return npc;
    }

    public boolean isHelper(NPC npc) {
        return ROLE_VALUE.equals(npc.data().get(ROLE_KEY, ""));
    }

    /** Next unused roster name, or the roster cycled with a number suffix once every name is taken. */
    private String nextName() {
        Set<String> taken = new HashSet<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (isHelper(npc)) {
                taken.add(npc.getName());
            }
        }

        for (String name : NAME_ROSTER) {
            if (!taken.contains(name)) {
                return name;
            }
        }
        for (int cycle = 2; ; cycle++) {
            for (String name : NAME_ROSTER) {
                String candidate = name + " " + cycle;
                if (!taken.contains(candidate)) {
                    return candidate;
                }
            }
        }
    }
}

package com.houseofel.builder.npc;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

/** Creates and tags Helper (builder/gatherer) Citizens NPCs. */
public final class BuilderNpcService {

    private static final String ROLE_KEY = "houseofel-role";
    private static final String ROLE_VALUE = "builder";

    public NPC spawnHelper(Location location, String name) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, name);
        npc.data().setPersistent(ROLE_KEY, ROLE_VALUE);
        npc.spawn(location);
        return npc;
    }

    public boolean isHelper(NPC npc) {
        return ROLE_VALUE.equals(npc.data().get(ROLE_KEY, ""));
    }
}

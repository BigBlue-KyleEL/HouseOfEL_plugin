package com.houseofel.builder.npc;

import com.houseofel.builder.title.HelperTitleService;
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
    /**
     * The Helper's roster name on its own ("Bartholomew"), stored separately from
     * Citizens' own name because that one carries the earned title too ("Bartholomew the
     * Trench-Hand") and therefore changes as the Helper levels. Everything that needs a
     * stable handle — chat triggers, roster-collision checks, in-character dialogue —
     * reads this instead of {@link NPC#getName()}. See {@link #baseNameOf}.
     */
    private static final String BASE_NAME_KEY = "houseofel-base-name";
    /** Assigned in order as each new Helper spawns; cycles with a number suffix once exhausted. */
    private static final List<String> NAME_ROSTER = List.of(
            "Thaddeus", "Bartholomew", "Montgomery", "Horace", "Aldric");

    private final HelperLevelService levelService;
    private final HelperTitleService titleService;

    public BuilderNpcService(HelperLevelService levelService, HelperTitleService titleService) {
        this.levelService = levelService;
        this.titleService = titleService;
    }

    public NPC spawnHelper(Location location, Specialization specialization) {
        String baseName = nextName();
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, baseName);
        npc.data().setPersistent(ROLE_KEY, ROLE_VALUE);
        npc.data().setPersistent(BASE_NAME_KEY, baseName);
        npc.spawn(location);
        levelService.assign(npc, specialization);
        titleService.applyTitle(npc, specialization, 1);
        return npc;
    }

    public boolean isHelper(NPC npc) {
        return ROLE_VALUE.equals(npc.data().get(ROLE_KEY, ""));
    }

    /**
     * The Helper's roster name without its earned title — "Bartholomew", never
     * "Bartholomew the Trench-Hand". Use this anywhere a stable handle is needed:
     * chat-trigger matching, roster-collision checks, and in-character dialogue (where
     * repeating the full title on every line would read as noise).
     *
     * <p>Falls back to Citizens' own name for any Helper spawned before titles existed —
     * those have no title appended, so the two are identical anyway.
     */
    public static String baseNameOf(NPC npc) {
        String stored = npc.data().get(BASE_NAME_KEY, "");
        return stored.isEmpty() ? npc.getName() : stored;
    }

    /**
     * One-time migration for Helpers spawned before titles existed: captures their
     * current name as the base name. MUST run before any title is ever appended to them —
     * otherwise {@link #baseNameOf}'s fallback would read the already-titled name as the
     * base on the next boot and stack a second title onto it ("Bartholomew, Spoil-Hauler,
     * Spoil-Hauler"). No-ops once a base name is recorded, so it's safe to call on boot
     * forever.
     */
    public static void ensureBaseName(NPC npc) {
        if (npc.data().get(BASE_NAME_KEY, "").isEmpty()) {
            npc.data().setPersistent(BASE_NAME_KEY, npc.getName());
        }
    }

    /**
     * Matches a chat message starting with a spawned Helper's base name,
     * case-insensitively — shared by every chat-trigger listener
     * ({@link HelperCommandListener}'s pause/resume/cancel words,
     * {@link HelperStatusListener}'s "report") so the matching rule stays in one place.
     * Deliberately matches the BASE name, so a Helper earning a new title never changes
     * what the player has to type at it.
     */
    public NPC matchHelper(String message) {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!isHelper(npc)) {
                continue;
            }
            String name = baseNameOf(npc);
            if (message.length() > name.length()
                    && message.regionMatches(true, 0, name, 0, name.length())
                    && Character.isWhitespace(message.charAt(name.length()))) {
                return npc;
            }
        }
        return null;
    }

    /** Next unused roster name, or the roster cycled with a number suffix once every name is taken. */
    private String nextName() {
        Set<String> taken = new HashSet<>();
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (isHelper(npc)) {
                // Base name, not the titled one — otherwise "Bartholomew the Trench-Hand"
                // wouldn't match "Bartholomew" and the roster would hand it out twice.
                taken.add(baseNameOf(npc));
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

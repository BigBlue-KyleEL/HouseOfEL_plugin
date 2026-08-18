package com.houseofel.builder.choice;

import com.houseofel.builder.npc.Specialization;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place every specialization's Choice-slot options (level 8, level 16) get
 * listed — the picker GUI, the level-up offer, and the dossier preview all read from
 * here, so onboarding a new spec's choice is a new entry here, not a new branch anywhere
 * else in the chassis. Empty for any (specialization, level) with no content built yet.
 */
public final class MilestoneChoiceRegistry {

    private static final Map<Specialization, Map<Integer, List<MilestoneChoiceOption>>> OPTIONS =
            new EnumMap<>(Specialization.class);

    static {
        register(Specialization.GROUNDWORKER, 8, List.of(
                new MilestoneChoiceOption("Quarryman",
                        "I can go deep — no bottom, cutting down in steps so I never trap myself. "
                                + "But I'll only ever take stone and dirt out — I won't fill anything back in.",
                        GroundworkerL8Choice.QUARRYMAN.name()),
                new MilestoneChoiceOption("Landscaper",
                        "I'll stay closer to the surface, but everything I touch will look like it belongs "
                                + "there when I'm done — matched topsoil, blended edges, replanted after.",
                        GroundworkerL8Choice.LANDSCAPER.name())));
        // Groundworker L16, and every other spec's L8/L16, register here when built —
        // nothing else in the choice chassis needs to change for a new entry.
    }

    private static void register(Specialization specialization, int level, List<MilestoneChoiceOption> options) {
        OPTIONS.computeIfAbsent(specialization, s -> new HashMap<>()).put(level, options);
    }

    /** Empty if this (specialization, level) has no Choice content built yet — callers should no-op. */
    public static List<MilestoneChoiceOption> optionsFor(Specialization specialization, int level) {
        if (specialization == null) {
            return List.of();
        }
        return OPTIONS.getOrDefault(specialization, Map.of()).getOrDefault(level, List.of());
    }

    private MilestoneChoiceRegistry() {
    }
}

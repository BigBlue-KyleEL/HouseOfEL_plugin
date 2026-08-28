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

        register(Specialization.GROUNDWORKER, 16, List.of(
                new MilestoneChoiceOption("Cofferdam",
                        "Water, permanently. I'll build a sealed wall around the area, drain everything "
                                + "inside, and keep it dry while you work — rain, melt, creeper holes, all handled. "
                                + "When you're done I'll strike the dam and recover the blocks.",
                        GroundworkerL16Choice.COFFERDAM.name(),
                        GroundworkerL8Choice.QUARRYMAN.name()),
                new MilestoneChoiceOption("Shaft Miner",
                        "Straight down, safely. I'll sink a proper shaft — ladders on one wall, support "
                                + "pillars at intervals, reinforced walls, lit landings. You can walk down to "
                                + "wherever I'm working.",
                        GroundworkerL16Choice.SHAFT_MINER.name(),
                        GroundworkerL8Choice.QUARRYMAN.name()),
                new MilestoneChoiceOption("Pathfinder",
                        "I connect places. Roads, trails, boardwalks between two points — cobblestone in "
                                + "mountains, dirt path in forests, sandstone in desert. Bridges over gaps, steps "
                                + "up slopes, torches all the way.",
                        GroundworkerL16Choice.PATHFINDER.name(),
                        GroundworkerL8Choice.LANDSCAPER.name()),
                new MilestoneChoiceOption("Terraformer",
                        "Bold terrain. Mountains with ridgelines, valleys, cliff faces with overhangs, "
                                + "natural arches. I make the landscape feel like someone designed it — memorable "
                                + "geography, not just flat ground.",
                        GroundworkerL16Choice.TERRAFORMER.name(),
                        GroundworkerL8Choice.LANDSCAPER.name())));
    }

    private static void register(Specialization specialization, int level, List<MilestoneChoiceOption> options) {
        OPTIONS.computeIfAbsent(specialization, s -> new HashMap<>()).put(level, options);
    }

    /** All options at this (specialization, level), regardless of parent choice — used for existence checks and preview text. */
    public static List<MilestoneChoiceOption> optionsFor(Specialization specialization, int level) {
        if (specialization == null) {
            return List.of();
        }
        return OPTIONS.getOrDefault(specialization, Map.of()).getOrDefault(level, List.of());
    }

    /**
     * Options filtered by the NPC's prior choice at an earlier level. For L16, pass the
     * NPC's L8 choice value (e.g. {@code "QUARRYMAN"}) — only options whose
     * {@link MilestoneChoiceOption#parentChoice()} matches (or is null) are returned.
     * Pass null to get all options (same as the single-arg overload).
     */
    public static List<MilestoneChoiceOption> optionsFor(Specialization specialization, int level, String parentChoice) {
        List<MilestoneChoiceOption> all = optionsFor(specialization, level);
        if (parentChoice == null) {
            return all;
        }
        return all.stream()
                .filter(o -> o.parentChoice() == null || o.parentChoice().equals(parentChoice))
                .toList();
    }

    private MilestoneChoiceRegistry() {
    }
}

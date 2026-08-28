package com.houseofel.builder.choice;

/**
 * Groundworker's level-16 Choice slot — path-specific forks. A Quarryman picks between
 * Cofferdam and Shaft Miner; a Landscaper picks between Pathfinder and Terraformer.
 * Which pair is offered depends on the NPC's existing L8 choice — see
 * {@link MilestoneChoiceOption#parentChoice()} and
 * {@link MilestoneChoiceRegistry#optionsFor(com.houseofel.builder.npc.Specialization, int, String)}.
 */
public enum GroundworkerL16Choice {
    COFFERDAM,
    SHAFT_MINER,
    PATHFINDER,
    TERRAFORMER
}

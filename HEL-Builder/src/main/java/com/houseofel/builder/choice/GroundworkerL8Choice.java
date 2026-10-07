package com.houseofel.builder.choice;

/**
 * Groundworker's level-8 Choice slot — Quarryman (deep bulk excavation) or Landscaper
 * (surface restoration/appearance), per V1 Perk Ladders. This phase only needs the two
 * names to exist so the generic choice chassis has something concrete to offer, persist,
 * and respec — the real dig/restore behavior lands in a later phase.
 */
public enum GroundworkerL8Choice {
    QUARRYMAN,
    LANDSCAPER
}

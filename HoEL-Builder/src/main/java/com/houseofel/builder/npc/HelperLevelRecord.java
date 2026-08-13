package com.houseofel.builder.npc;

/** A Helper's mutable leveling state. {@code specialization} is null for NPCs spawned before 1-F. */
final class HelperLevelRecord {
    Specialization specialization;
    int xp;

    HelperLevelRecord(Specialization specialization, int xp) {
        this.specialization = specialization;
        this.xp = xp;
    }
}

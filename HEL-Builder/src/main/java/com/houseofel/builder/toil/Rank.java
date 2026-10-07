package com.houseofel.builder.toil;

/**
 * Chassis-wide rank names — the same four words for every one of the 27 specializations,
 * only at levels 5/10/15/20. Distinct from a Helper's per-specialization flavor Title
 * (see [[V1 Title Ladders (Draft Pool)]] in the vault), which changes at all 10 milestone
 * levels and is built in a later Phase 1-F item, not this one.
 */
public enum Rank {
    JOURNEYMAN(5, "Journeyman"),
    EXPERT(10, "Expert"),
    MASTER(15, "Master"),
    OLD_HAND(20, "Old Hand");

    private final int level;
    private final String label;

    Rank(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public int level() {
        return level;
    }

    public String label() {
        return label;
    }

    /** The rank reached at this level, or null if this level carries no rank change. */
    public static Rank at(int level) {
        for (Rank rank : values()) {
            if (rank.level == level) {
                return rank;
            }
        }
        return null;
    }

    /**
     * The highest rank threshold at or below the given level, or null if it's below
     * {@link #JOURNEYMAN}. Used by the Death Policy's rank-floor fallback (the one place a
     * level can be lost) — distinct from {@link #at}, which is an exact-milestone lookup
     * and returns null for anything between rank levels.
     */
    public static Rank floorFor(int level) {
        Rank floor = null;
        for (Rank rank : values()) {
            if (rank.level <= level && (floor == null || rank.level > floor.level)) {
                floor = rank;
            }
        }
        return floor;
    }
}

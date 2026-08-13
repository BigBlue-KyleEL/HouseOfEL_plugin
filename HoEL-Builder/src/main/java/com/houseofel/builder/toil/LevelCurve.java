package com.houseofel.builder.toil;

import java.util.EnumSet;
import java.util.Set;

/**
 * The framework's published Level table (cumulative Toil thresholds), hardcoded rather
 * than re-derived from {@code round5(8 + 4*L^1.35)} at runtime — this avoids any
 * floating-point/rounding drift from Kyle's exact published numbers, which the framework
 * explicitly wants players to be able to read and remember ("round numbers are not
 * cosmetic").
 */
public final class LevelCurve {

    public static final int MAX_LEVEL = 20;

    /** Index L = cumulative Toil required to REACH level L. Index 0 unused. */
    private static final int[] CUMULATIVE_TOIL = {
            0,    // unused
            0,    // L1 — recruit
            10, 30, 55, 90, 135, 190, 255, 330, 415,
            510, 620, 745, 880, 1030, 1195, 1370, 1560, 1765, 1985,
    };

    private LevelCurve() {
    }

    /** Current level for a given banked-Toil total, capped at {@link #MAX_LEVEL}. */
    public static int levelForToil(int bankedToil) {
        int level = 1;
        for (int candidate = 2; candidate <= MAX_LEVEL; candidate++) {
            if (bankedToil < CUMULATIVE_TOIL[candidate]) {
                break;
            }
            level = candidate;
        }
        return level;
    }

    /** Which milestone type(s), if any, a level carries — empty only for level 1 (recruit). */
    public static Set<MilestoneType> milestonesAt(int level) {
        Set<MilestoneType> milestones = EnumSet.noneOf(MilestoneType.class);
        if (isRank(level)) {
            milestones.add(MilestoneType.RANK);
        }
        if (isVerb(level)) {
            milestones.add(MilestoneType.VERB);
        }
        if (isChoice(level)) {
            milestones.add(MilestoneType.CHOICE);
        }
        if (level == MAX_LEVEL) {
            milestones.add(MilestoneType.CAPSTONE);
        }
        if (milestones.isEmpty() && level > 1) {
            milestones.add(MilestoneType.REFINEMENT);
        }
        return milestones;
    }

    private static boolean isRank(int level) {
        return level == 5 || level == 10 || level == 15 || level == 20;
    }

    private static boolean isVerb(int level) {
        return level == 3 || level == 6 || level == 10 || level == 14 || level == 18;
    }

    private static boolean isChoice(int level) {
        return level == 8 || level == 16;
    }
}

package com.houseofel.builder.title;

import com.houseofel.builder.npc.Specialization;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The "Classic Epithet, Reinvented" column Kyle finalized from [[V1 Title Ladders (Draft
 * Pool)]], for the V0 roster. Every entry in that column is either {@code "[Name], X"} or
 * {@code "[Name] the X"} — stored here as just the {@code X} portion (keeping "the" where
 * present, dropping the comma), since the title renders on its own line under the
 * Helper's name rather than as one long inline phrase.
 *
 * <p>Distinct from {@code Rank} (Journeyman/Expert/Master/Old Hand) — that's one
 * chassis-wide word at 4 levels; this is a per-specialization phrase at all 10 milestone
 * levels (3/5/6/8/10/14/15/16/18/20).
 */
public final class TitleLadder {

    private static final Map<Specialization, NavigableMap<Integer, String>> LADDERS = Map.of(
            Specialization.FARMER, ladder(Map.ofEntries(
                    Map.entry(3, "Clod-Hand"),
                    Map.entry(5, "the Seed-Sower"),
                    Map.entry(6, "Furrow-Walker"),
                    Map.entry(8, "the Green-Thumbed"),
                    Map.entry(10, "Steward of the Furrow"),
                    Map.entry(14, "the Harvest-Caller"),
                    Map.entry(15, "Keeper of the Granary"),
                    Map.entry(16, "the Bountiful"),
                    Map.entry(18, "Guardian of the First Field"),
                    Map.entry(20, "the Harvest-Sovereign"))),
            Specialization.LUMBERJACK, ladder(Map.ofEntries(
                    Map.entry(3, "Sap-Sleeve"),
                    Map.entry(5, "the Bark-Stripped"),
                    Map.entry(6, "Axe-Hand"),
                    Map.entry(8, "the Grove-Walker"),
                    Map.entry(10, "Warden of the Timberline"),
                    Map.entry(14, "the Deep-Grove Feller"),
                    Map.entry(15, "Keeper of the Old Stands"),
                    Map.entry(16, "the Timber-Sworn"),
                    Map.entry(18, "Master of the Last Grove"),
                    Map.entry(20, "the Grove-Sovereign"))),
            Specialization.GROUNDWORKER, ladder(Map.ofEntries(
                    Map.entry(3, "Spoil-Hauler"),
                    Map.entry(5, "the Trench-Hand"),
                    Map.entry(6, "Grade-Setter"),
                    Map.entry(8, "the Earth-Shaper"),
                    Map.entry(10, "Steward of the Cut"),
                    Map.entry(14, "the Land-Sworn"),
                    Map.entry(15, "Keeper of the Reshaped Earth"),
                    Map.entry(16, "the Mover of Mountains"),
                    Map.entry(18, "Warden of the Marked Ground"),
                    Map.entry(20, "the Sovereign of the Reshaped Land")))
    );

    private TitleLadder() {
    }

    /**
     * The title text for this specialization's current level. Between milestone levels,
     * keeps showing the last-earned title (same idea as Rank). Null below level 3 — the
     * table's first entry — since a fresh recruit hasn't earned a title yet.
     */
    public static String titleFor(Specialization specialization, int level) {
        Map.Entry<Integer, String> floorEntry = LADDERS.get(specialization).floorEntry(level);
        return floorEntry == null ? null : floorEntry.getValue();
    }

    private static NavigableMap<Integer, String> ladder(Map<Integer, String> entries) {
        return new TreeMap<>(entries);
    }
}

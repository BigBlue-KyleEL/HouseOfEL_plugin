package com.houseofel.builder.title;

import com.houseofel.builder.npc.Specialization;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The "Playful &amp; Experimental" column from [[V1 Title Ladders (Draft Pool)]] — the
 * first-pass set, not its "II" sibling — used as a bit of witty flavour text under a
 * Helper's name in the job menu ("Shovel, Blisters Included").
 *
 * <p>Purely cosmetic and entirely separate from {@link TitleLadder}, which supplies the
 * formal earned title shown on the nameplate. Same 10 milestone levels, same
 * last-earned-sticks lookup.
 */
public final class FlavorLadder {

    private static final Map<Specialization, NavigableMap<Integer, String>> LADDERS = Map.of(
            Specialization.FARMER, ladder(Map.ofEntries(
                    Map.entry(3, "Turnip-Fingers"),
                    Map.entry(5, "Knee-Deep, Learning Fast"),
                    Map.entry(6, "Second-Furrow Hands"),
                    Map.entry(8, "Nine Rows Weeded, Zero Complaints"),
                    Map.entry(10, "First Barn Filled"),
                    Map.entry(14, "Reads Soil Like Scripture"),
                    Map.entry(15, "Fifteen Seasons Sown"),
                    Map.entry(16, "Drought Never Once Won"),
                    Map.entry(18, "Every Field Remembers This Hoe"),
                    Map.entry(20, "Harvestfast — Named by the Grain"))),
            Specialization.LUMBERJACK, ladder(Map.ofEntries(
                    Map.entry(3, "Green-Bark Greenhorn"),
                    Map.entry(5, "Splinters & Good Intentions"),
                    Map.entry(6, "Second-Growth Steady"),
                    Map.entry(8, "Ten Trees, Ten Truths"),
                    Map.entry(10, "Knows the Grove by Name"),
                    Map.entry(14, "Axe Never Rings Twice"),
                    Map.entry(15, "Grove-Warden, Self-Appointed"),
                    Map.entry(16, "Old-Growth Nerve"),
                    Map.entry(18, "Timberfall Answers to This Axe"),
                    Map.entry(20, "Last Stump Bears the Mark"))),
            Specialization.GROUNDWORKER, ladder(Map.ofEntries(
                    Map.entry(3, "Shovel, Blisters Included"),
                    Map.entry(5, "Second Trench, Straighter Line"),
                    Map.entry(6, "Spoil-Hauler, Steady Back"),
                    Map.entry(8, "Nine Yards Reshaped"),
                    Map.entry(10, "Ground Bends to This Plan"),
                    Map.entry(14, "Reads Land Before Cutting"),
                    Map.entry(15, "Fifteen Sites, Not One Collapse"),
                    Map.entry(16, "The Land Trusts This Shovel"),
                    Map.entry(18, "Reshapes Earth Like Old Habit"),
                    Map.entry(20, "Earthshaper — Ground Remembers the Name")))
    );

    private FlavorLadder() {
    }

    /**
     * The flavour line for this specialization's current level, already wrapped in quotes
     * for display. Null below level 3 (the table's first entry) or for a Helper with no
     * specialization — callers show nothing at all in that case.
     */
    public static String flavorFor(Specialization specialization, int level) {
        if (specialization == null) {
            return null;
        }
        Map.Entry<Integer, String> floorEntry = LADDERS.get(specialization).floorEntry(level);
        return floorEntry == null ? null : "\"" + floorEntry.getValue() + "\"";
    }

    private static NavigableMap<Integer, String> ladder(Map<Integer, String> entries) {
        return new TreeMap<>(entries);
    }
}

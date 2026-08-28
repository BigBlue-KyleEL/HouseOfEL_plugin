package com.houseofel.builder.job;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * L12 "The Stake-Out" — a pre-dispatch region analysis that a level-12+ Groundworker
 * runs before accepting any job. Posts volume stats to the player, and refuses regions
 * with identifiable hazards.
 */
public final class RegionSurvey {

    static final int STAKE_OUT_LEVEL = 12;
    private static final int CHEST_CAPACITY = 27 * 64;

    public record SurveyResult(
            long solidBlocks,
            long airBlocks,
            long waterBlocks,
            long lavaBlocks,
            List<String> warnings,
            List<String> refusals
    ) {
        public boolean refused() {
            return !refusals.isEmpty();
        }
    }

    private RegionSurvey() {
    }

    /**
     * Scans the region and returns block counts plus any hazard warnings/refusals.
     * Runs synchronously on the main thread — acceptable because the region size is
     * already capped at 1.5M blocks by {@code RegionSelectionService.MAX_VOLUME} and
     * the scan is a simple material check per block, no chunk loading.
     */
    public static SurveyResult analyze(World world, int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ, JobType jobType) {
        long solid = 0;
        long air = 0;
        long water = 0;
        long lava = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Material mat = world.getBlockAt(x, y, z).getType();
                    if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) {
                        air++;
                    } else if (mat == Material.WATER) {
                        water++;
                    } else if (mat == Material.LAVA) {
                        lava++;
                    } else {
                        solid++;
                    }
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        List<String> refusals = new ArrayList<>();

        if (jobType == JobType.CLEAR || jobType == JobType.QUARRY) {
            if (minY <= world.getMinHeight() + 3) {
                refusals.add("That region bottoms out at Y=" + minY
                        + ", right at the world floor — did you mean to go that deep?");
            }

            if (lava > 0) {
                warnings.add("Lava detected: " + lava + " block(s) in the work area."
                        + " I'll plug what I can, but watch for surprises.");
            }

            if (water > 0 && water > solid / 4) {
                warnings.add("Significant water: " + water + " block(s) in the region."
                        + " Bulkhead protocol will handle it, but expect some drain time.");
            }

            long spoilBlocks = solid;
            if (spoilBlocks > 0) {
                int chestsNeeded = (int) Math.ceil((double) spoilBlocks / CHEST_CAPACITY);
                warnings.add("Spoil estimate: " + spoilBlocks + " block(s) to remove"
                        + " — that'll fill about " + chestsNeeded
                        + " chest" + (chestsNeeded == 1 ? "" : "s") + " of material.");
            }
        }

        if (jobType == JobType.LANDSCAPE) {
            int columns = (maxX - minX + 1) * (maxZ - minZ + 1);
            if (lava > 0) {
                warnings.add("Lava in the region: " + lava + " block(s). Landscaping over lava"
                        + " — I'll work around it, but it might get messy.");
            }
        }

        if (jobType == JobType.COFFERDAM) {
            if (water == 0) {
                refusals.add("No water in the region — a cofferdam wouldn't do anything.");
            }
            if (lava > 0) {
                warnings.add("Lava detected inside the dam area: " + lava
                        + " block(s). The dam walls won't hold lava back — just water.");
            }
        }

        return new SurveyResult(solid, air, water, lava, warnings, refusals);
    }

    /**
     * Formats the survey into chat lines for the player. Returns the NPC's message
     * lines — caller prefixes with the NPC's name.
     */
    public static List<String> formatSummary(SurveyResult result, JobType jobType) {
        List<String> lines = new ArrayList<>();
        long total = result.solidBlocks + result.airBlocks + result.waterBlocks + result.lavaBlocks;

        if (jobType == JobType.CLEAR || jobType == JobType.QUARRY) {
            lines.add("Survey: " + result.solidBlocks + " solid, "
                    + result.waterBlocks + " water, " + result.lavaBlocks + " lava, "
                    + result.airBlocks + " air — " + total + " total.");
        } else if (jobType == JobType.LANDSCAPE) {
            lines.add("Survey: " + (result.solidBlocks + result.waterBlocks + result.lavaBlocks)
                    + " blocks to reshape across "
                    + result.airBlocks + " open air blocks.");
        } else if (jobType == JobType.COFFERDAM) {
            lines.add("Survey: " + result.waterBlocks + " water to drain, "
                    + result.solidBlocks + " solid (natural sealing).");
        }

        return lines;
    }
}

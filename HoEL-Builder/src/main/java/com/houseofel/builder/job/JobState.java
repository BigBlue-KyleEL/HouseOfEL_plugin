package com.houseofel.builder.job;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything needed to resume one Helper's Clearing job — either later in the same
 * server session (a chat "go") or after a full restart. Locations are stored as plain
 * "x,y,z" strings rather than Bukkit types, since this is what actually round-trips
 * through YAML.
 */
final class JobState {

    /** Which concrete job this belongs to — defaults to CLEAR so every file written before this field existed still loads correctly. */
    JobType jobType = JobType.CLEAR;

    int npcId;
    UUID playerId;
    String worldName;
    int minX;
    int maxX;
    int minY;
    int maxY;
    int minZ;
    int maxZ;
    String target;
    boolean surfaceOnly;
    /** Landscaper (Groundworker L8 option B): this Clear job also restores topsoil when it finishes. */
    boolean restoresTopsoil;
    boolean storeInChest;

    // Quarryman-only dig-order parameters — meaningful only when jobType == QUARRY.
    // Combined with minX/maxX/minZ/maxZ above, these are the exact inputs
    // QuarrymanJobTask.buildDigOrder() needs to regenerate an identical dig order
    // deterministically, so processedCells (below) is all that's needed to fast-forward
    // it — the queue itself never needs to be serialized.
    int topY;
    int requestedDepth;
    boolean stepsAlongX;
    int stepDirection;

    long processedCells;
    long clearedCells;
    long deposited;
    int passNumber = 1;
    long clearedThisPass;
    long skippedThisPass;
    long skippedNoPath;
    long skippedTimeout;

    Map<String, Integer> carried = new LinkedHashMap<>();

    // Plugged fluid-breach sources awaiting the settle timer, or left over from a
    // restart/cancel mid-wait — see ClearJobTask.resume()'s stray-plug cleanup.
    List<String> bulkheadPlugs = new ArrayList<>();

    // Storage layout — only meaningful when storeInChest is true. See JobStorage's
    // persistence accessors for what each of these drives.
    List<String> chests = new ArrayList<>();
    List<String> occupiedColumns = new ArrayList<>();
    String anchor;
    String lastCubeAnchor;
    String rowFoot0;
    String rowFoot1;
    int cubeUnitIndex;
    int rowSign;
    int columnSign;
}

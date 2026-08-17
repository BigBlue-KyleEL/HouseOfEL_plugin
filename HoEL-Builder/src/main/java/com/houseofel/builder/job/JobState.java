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
    String tool;
    boolean surfaceOnly;
    boolean storeInChest;

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

package com.houseofel.builder.choice;

import java.util.UUID;

/**
 * One Choice slot's current pick — one row per (npcUuid, level). {@code chosenAtMillis}
 * doubles as "last respec time" too, since a respec is just an overwrite of this same row
 * with a fresh timestamp.
 */
public record MilestoneChoiceRecord(UUID npcUuid, int level, String choice, long chosenAtMillis) {
}

package com.houseofel.builder.toil;

import com.houseofel.builder.npc.Specialization;

import java.util.UUID;

/**
 * A Helper's row in the Toil ledger — immutable snapshot, replaced (not mutated) on every
 * progress update. Keyed by Citizens' own stable UUID ({@code NPC#getUniqueId()}), not the
 * int {@code NPC#getId()} or the entity's Minecraft UUID — verified against the real
 * Citizens jar that this is the identifier that survives entity replacement, which is
 * exactly what the framework's "SQLite is authoritative" rationale depends on.
 */
public record HelperLedgerRecord(UUID npcUuid, Specialization specialization, int level, int bankedToil) {
}

package com.houseofel.builder.death;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.UUID;

/**
 * One death event — the Chronicler-shaped generic record from the Death Policy plan
 * (cause/world/x/y/z/timestamp), deliberately not Helper-specific so a future Chronicler
 * can read {@code helper_scar} without a Helper-only shape in the way. {@code npcUuid} is
 * purely the join key back to {@code helper_ledger.owner_uuid} for recruitment-cost
 * escalation queries.
 */
public record ScarRecord(UUID npcUuid, DamageCause cause, String world, int x, int y, int z, long occurredAt) {
}

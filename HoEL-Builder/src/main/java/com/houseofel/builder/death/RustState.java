package com.houseofel.builder.death;

import java.util.UUID;

/**
 * A currently-active Rust penalty on one Helper — present only while it's rusted, deleted
 * from {@code helper_rust} the moment it clears. {@code deathWorld}/{@code deathX/Y/Z} is
 * the death site, used to refuse re-entry into the death block until Rust clears or 24h
 * passes.
 */
public record RustState(UUID npcUuid, int toilRemaining, String deathWorld, int deathX, int deathY, int deathZ,
                         long startedAt) {
}

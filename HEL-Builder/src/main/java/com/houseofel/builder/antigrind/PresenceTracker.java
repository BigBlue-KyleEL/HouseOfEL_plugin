package com.houseofel.builder.antigrind;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player last-activity timestamp for the Presence Gate. "Active" per the framework:
 * moved, broke or placed a block, opened an inventory, or chatted, in the last 10 real
 * minutes — merely being logged in doesn't count. In-memory only; a player who logs off
 * simply stops generating activity and ages out of the window on their own, no separate
 * online/offline bookkeeping needed.
 */
public final class PresenceTracker {

    private static final long ACTIVE_WINDOW_MILLIS = 10L * 60 * 1000;

    private final Map<UUID, Long> lastActivity = new HashMap<>();

    public void recordActivity(UUID playerUuid) {
        lastActivity.put(playerUuid, System.currentTimeMillis());
    }

    public boolean isActive(UUID playerUuid) {
        Long last = lastActivity.get(playerUuid);
        return last != null && System.currentTimeMillis() - last < ACTIVE_WINDOW_MILLIS;
    }

    /** True if any currently-online player other than {@code excludeUuid} has been active within the window. */
    public boolean anyOtherPlayerActive(UUID excludeUuid) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getUniqueId().equals(excludeUuid) && isActive(player.getUniqueId())) {
                return true;
            }
        }
        return false;
    }
}

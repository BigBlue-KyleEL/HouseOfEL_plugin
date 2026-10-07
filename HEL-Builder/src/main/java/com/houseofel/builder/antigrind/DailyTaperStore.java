package com.houseofel.builder.antigrind;

import com.houseofel.builder.toil.ToilDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The one anti-grind tracker that's genuinely SQLite-persisted — cumulative Toil banked
 * per Helper per server-local calendar day, backing the daily-taper pipeline stage. Unlike
 * this package's other short-timescale trackers, losing this on a force-kill would let the
 * 150-Toil cap be trivially bypassed by restarting, so it can't be in-memory. Follows
 * {@code ToilLedgerStore}/{@code DeathRecordStore}'s conventions: connection from
 * {@link ToilDatabase}, read-then-write in Java rather than SQL arithmetic, logs+swallows
 * {@link SQLException}.
 */
public final class DailyTaperStore {

    private final Connection connection;
    private final Logger logger;

    public DailyTaperStore(ToilDatabase database, Logger logger) {
        this.connection = database.connection();
        this.logger = logger;
    }

    /** Toil this Helper has already banked today (server-local calendar day), before this ticket. */
    public int cumulativeToilFor(UUID npcUuid, long timestampMillis) {
        String sql = "SELECT cumulative_toil FROM daily_toil_totals WHERE npc_uuid = ? AND day_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, dayKeyFor(timestampMillis));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("cumulative_toil") : 0;
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read daily Toil total for NPC " + npcUuid + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Adds this ticket's award to today's running total. Takes the caller's own
     * already-known {@code cumulativeBefore} rather than re-reading it — the pipeline's
     * daily-taper stage already queried this exact row moments earlier in the same ticket
     * award, via the same timestamp, so re-reading here would just be a duplicate
     * synchronous SQLite round-trip for a value guaranteed to come back identical.
     */
    public void recordAward(UUID npcUuid, long timestampMillis, int cumulativeBefore, int toilAwarded) {
        int updated = cumulativeBefore + toilAwarded;
        String sql = "INSERT OR REPLACE INTO daily_toil_totals (npc_uuid, day_key, cumulative_toil) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, dayKeyFor(timestampMillis));
            statement.setInt(3, updated);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't record daily Toil total for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    /** Server-local calendar day, not UTC — pinned per the framework, so a group doesn't get a cap reset mid-session. */
    private static String dayKeyFor(long timestampMillis) {
        return Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }
}

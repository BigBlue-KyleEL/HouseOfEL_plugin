package com.houseofel.builder.toil;

import com.houseofel.builder.npc.Specialization;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/** CRUD over the SQLite-backed Toil ledger — see {@link ToilDatabase} for schema/connection ownership. */
public final class ToilLedgerStore {

    private final Connection connection;
    private final Logger logger;

    public ToilLedgerStore(ToilDatabase database, Logger logger) {
        this.connection = database.connection();
        this.logger = logger;
    }

    /** Null if this NPC has no ledger row yet (never assigned a specialization). */
    public HelperLedgerRecord find(UUID npcUuid) {
        String sql = "SELECT specialization, level, banked_toil FROM helper_ledger WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Specialization specialization = Specialization.valueOf(rs.getString("specialization"));
                return new HelperLedgerRecord(npcUuid, specialization, rs.getInt("level"), rs.getInt("banked_toil"));
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read Toil ledger for NPC " + npcUuid + ": " + e.getMessage());
            return null;
        }
    }

    /** Upsert — used both for the initial spawn-time record and every later progress update. */
    public void save(HelperLedgerRecord record) {
        String sql = "INSERT OR REPLACE INTO helper_ledger (npc_uuid, specialization, level, banked_toil) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.npcUuid().toString());
            statement.setString(2, record.specialization().name());
            statement.setInt(3, record.level());
            statement.setInt(4, record.bankedToil());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't save Toil ledger for NPC " + record.npcUuid() + ": " + e.getMessage());
        }
    }

    /** Leftover progress toward this Helper's next ticket of this kind — 0 if none banked yet. */
    public int progressFor(UUID npcUuid, TicketKind kind) {
        String sql = "SELECT progress_units FROM ticket_progress WHERE npc_uuid = ? AND ticket_kind = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, kind.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("progress_units") : 0;
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read ticket progress for NPC " + npcUuid + ": " + e.getMessage());
            return 0;
        }
    }

    public void saveProgress(UUID npcUuid, TicketKind kind, int progressUnits) {
        String sql = "INSERT OR REPLACE INTO ticket_progress (npc_uuid, ticket_kind, progress_units) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, kind.name());
            statement.setInt(3, progressUnits);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't save ticket progress for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    /**
     * Logs one awarded ticket — the framework requires per-ticket idempotency by
     * order-id ("the difference between a ledger and a rumour"). {@code INSERT OR IGNORE}
     * on the unique order-id is defense-in-depth on top of the structural idempotency
     * {@code ClearJobTask} already gets from deriving ticket counts off its durable
     * {@code deposited} field. Returns false only on a genuine order-id collision (already
     * logged) — the caller must not bank Toil twice for the same ticket.
     */
    public boolean logTicket(String orderId, UUID npcUuid, TicketKind kind, int rawMinutes, int finalToil) {
        String sql = "INSERT OR IGNORE INTO toil_ticket "
                + "(order_id, npc_uuid, ticket_kind, raw_minutes, final_toil, awarded_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderId);
            statement.setString(2, npcUuid.toString());
            statement.setString(3, kind.name());
            statement.setInt(4, rawMinutes);
            statement.setInt(5, finalToil);
            statement.setLong(6, System.currentTimeMillis());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warning("Couldn't log Toil ticket " + orderId + ": " + e.getMessage());
            return false;
        }
    }
}

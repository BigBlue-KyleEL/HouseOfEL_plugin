package com.houseofel.builder.death;

import com.houseofel.builder.toil.ToilDatabase;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * CRUD over the three Death Policy tables ({@code helper_scar}, {@code helper_rust},
 * {@code helper_ledger_pending}) plus the {@code owner_uuid}/{@code scar_choice} columns
 * Death Policy added onto the existing {@code helper_ledger} table — see
 * {@link ToilDatabase} for schema/connection ownership. Follows {@code ToilLedgerStore}'s
 * conventions: find/save (INSERT OR REPLACE) for single-row state, logs+swallows
 * {@link SQLException} returning a safe default rather than throwing.
 */
public final class DeathRecordStore {

    private final Connection connection;
    private final Logger logger;

    public DeathRecordStore(ToilDatabase database, Logger logger) {
        this.connection = database.connection();
        this.logger = logger;
    }

    // ---- Scars ----

    public void addScar(ScarRecord scar) {
        String sql = "INSERT INTO helper_scar (npc_uuid, cause, world, x, y, z, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scar.npcUuid().toString());
            statement.setString(2, scar.cause().name());
            statement.setString(3, scar.world());
            statement.setInt(4, scar.x());
            statement.setInt(5, scar.y());
            statement.setInt(6, scar.z());
            statement.setLong(7, scar.occurredAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't record a scar for NPC " + scar.npcUuid() + ": " + e.getMessage());
        }
    }

    /** All of this Helper's scars, oldest first — used for the 3rd-scar check and the "report" line. */
    public List<ScarRecord> scarsFor(UUID npcUuid) {
        String sql = "SELECT cause, world, x, y, z, occurred_at FROM helper_scar "
                + "WHERE npc_uuid = ? ORDER BY occurred_at";
        List<ScarRecord> scars = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    scars.add(new ScarRecord(npcUuid, DamageCause.valueOf(rs.getString("cause")),
                            rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                            rs.getLong("occurred_at")));
                }
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read scars for NPC " + npcUuid + ": " + e.getMessage());
        }
        return scars;
    }

    /** How many deaths this player's Helpers have had since {@code sinceMillis} — the recruitment-cost escalation input. */
    public int recentDeathCount(UUID ownerUuid, long sinceMillis) {
        String sql = "SELECT COUNT(*) FROM helper_scar s JOIN helper_ledger l ON s.npc_uuid = l.npc_uuid "
                + "WHERE l.owner_uuid = ? AND s.occurred_at >= ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.warning("Couldn't count recent deaths for player " + ownerUuid + ": " + e.getMessage());
            return 0;
        }
    }

    // ---- owner_uuid / scar_choice (columns Death Policy added onto helper_ledger) ----

    public void setOwner(UUID npcUuid, UUID ownerUuid) {
        String sql = "UPDATE helper_ledger SET owner_uuid = ? WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, npcUuid.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't set owner for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    /** Null for a pre-Death-Policy Helper with no recorded owner — the safe default that never escalates. */
    public UUID ownerOf(UUID npcUuid) {
        String sql = "SELECT owner_uuid FROM helper_ledger WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String owner = rs.getString("owner_uuid");
                return owner == null ? null : UUID.fromString(owner);
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read owner for NPC " + npcUuid + ": " + e.getMessage());
            return null;
        }
    }

    public void setScarChoice(UUID npcUuid, ScarChoice choice) {
        String sql = "UPDATE helper_ledger SET scar_choice = ? WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, choice.name());
            statement.setString(2, npcUuid.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't set scar choice for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    /** Null until (and unless) this Helper has reached its 3rd scar and a choice was made. */
    public ScarChoice scarChoiceOf(UUID npcUuid) {
        String sql = "SELECT scar_choice FROM helper_ledger WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String choice = rs.getString("scar_choice");
                return choice == null ? null : ScarChoice.valueOf(choice);
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read scar choice for NPC " + npcUuid + ": " + e.getMessage());
            return null;
        }
    }

    /** True once any of this player's Helpers has ever chosen HARDENED — permanent, never resets. */
    public boolean hasHardenedHelper(UUID ownerUuid) {
        String sql = "SELECT COUNT(*) FROM helper_ledger WHERE owner_uuid = ? AND scar_choice = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, ScarChoice.HARDENED.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.warning("Couldn't check HARDENED status for player " + ownerUuid + ": " + e.getMessage());
            return false;
        }
    }

    // ---- Rust ----

    /** Null if this Helper isn't currently rusted. */
    public RustState rustFor(UUID npcUuid) {
        String sql = "SELECT toil_remaining, death_world, death_x, death_y, death_z, started_at "
                + "FROM helper_rust WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new RustState(npcUuid, rs.getInt("toil_remaining"), rs.getString("death_world"),
                        rs.getInt("death_x"), rs.getInt("death_y"), rs.getInt("death_z"), rs.getLong("started_at"));
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read Rust state for NPC " + npcUuid + ": " + e.getMessage());
            return null;
        }
    }

    public void saveRust(RustState state) {
        String sql = "INSERT OR REPLACE INTO helper_rust "
                + "(npc_uuid, toil_remaining, death_world, death_x, death_y, death_z, started_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.npcUuid().toString());
            statement.setInt(2, state.toilRemaining());
            statement.setString(3, state.deathWorld());
            statement.setInt(4, state.deathX());
            statement.setInt(5, state.deathY());
            statement.setInt(6, state.deathZ());
            statement.setLong(7, state.startedAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't save Rust state for NPC " + state.npcUuid() + ": " + e.getMessage());
        }
    }

    /** Every currently-rusted Helper — {@code RustBossBar}'s sweep input. */
    public List<RustState> allRusted() {
        String sql = "SELECT npc_uuid, toil_remaining, death_world, death_x, death_y, death_z, started_at "
                + "FROM helper_rust";
        List<RustState> rusted = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                rusted.add(new RustState(UUID.fromString(rs.getString("npc_uuid")), rs.getInt("toil_remaining"),
                        rs.getString("death_world"), rs.getInt("death_x"), rs.getInt("death_y"),
                        rs.getInt("death_z"), rs.getLong("started_at")));
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read rusted Helpers: " + e.getMessage());
        }
        return rusted;
    }

    public void clearRust(UUID npcUuid) {
        String sql = "DELETE FROM helper_rust WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't clear Rust state for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    // ---- Ledger pending (dropped, uncollected Work Ledger books) ----

    public void markPending(UUID npcUuid, long droppedAt) {
        String sql = "INSERT OR REPLACE INTO helper_ledger_pending (npc_uuid, dropped_at) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.setLong(2, droppedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't mark ledger pending for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    public void clearPending(UUID npcUuid) {
        String sql = "DELETE FROM helper_ledger_pending WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't clear pending ledger for NPC " + npcUuid + ": " + e.getMessage());
        }
    }

    /** Every Helper with a currently dropped-but-uncollected Work Ledger — {@code LedgerExpiryTask}'s sweep input. */
    public List<LedgerPending> allPending() {
        String sql = "SELECT npc_uuid, dropped_at FROM helper_ledger_pending";
        List<LedgerPending> pending = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                pending.add(new LedgerPending(UUID.fromString(rs.getString("npc_uuid")), rs.getLong("dropped_at")));
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read pending ledgers: " + e.getMessage());
        }
        return pending;
    }
}

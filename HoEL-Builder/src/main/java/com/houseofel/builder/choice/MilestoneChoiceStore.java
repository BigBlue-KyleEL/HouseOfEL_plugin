package com.houseofel.builder.choice;

import com.houseofel.builder.toil.ToilDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * CRUD over {@code helper_milestone_choice} (see {@link ToilDatabase} for schema/connection
 * ownership) plus the Toil-spend check a respec needs. Follows {@code DeathRecordStore}'s
 * conventions: find/save (INSERT OR REPLACE) for single-row state, logs+swallows
 * {@link SQLException} returning a safe default rather than throwing.
 */
public final class MilestoneChoiceStore {

    private final Connection connection;
    private final Logger logger;

    public MilestoneChoiceStore(ToilDatabase database, Logger logger) {
        this.connection = database.connection();
        this.logger = logger;
    }

    /** Null if this Helper hasn't made a pick at this level yet. */
    public MilestoneChoiceRecord find(UUID npcUuid, int level) {
        String sql = "SELECT choice, chosen_at FROM helper_milestone_choice WHERE npc_uuid = ? AND level = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            statement.setInt(2, level);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new MilestoneChoiceRecord(npcUuid, level, rs.getString("choice"), rs.getLong("chosen_at"));
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read milestone choice for NPC " + npcUuid + " level " + level + ": " + e.getMessage());
            return null;
        }
    }

    /** Upsert — used identically for the first pick and every later respec. */
    public void save(MilestoneChoiceRecord record) {
        String sql = "INSERT OR REPLACE INTO helper_milestone_choice (npc_uuid, level, choice, chosen_at) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.npcUuid().toString());
            statement.setInt(2, record.level());
            statement.setString(3, record.choice());
            statement.setLong(4, record.chosenAtMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Couldn't save milestone choice for NPC " + record.npcUuid() + ": " + e.getMessage());
        }
    }

    /**
     * Banked Toil minus Toil already spent on respecs — deliberately NOT the same number
     * as the Helper's real banked Toil, which {@link com.houseofel.builder.npc.HelperLevelService}
     * derives its level from on every ticket award. Read directly off {@code helper_ledger}
     * (same direct-read pattern {@code DeathRecordStore} already uses for owner_uuid/scar_choice)
     * rather than adding a new dependency on the level service.
     */
    public int spendableToilOf(UUID npcUuid) {
        String sql = "SELECT banked_toil, toil_spent_on_respec FROM helper_ledger WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getInt("banked_toil") - rs.getInt("toil_spent_on_respec");
            }
        } catch (SQLException e) {
            logger.warning("Couldn't read spendable Toil for NPC " + npcUuid + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Debits {@code toil_spent_on_respec} — never {@code banked_toil} itself, which stays
     * monotonically non-decreasing (except Death Policy's rank-floor, an intentional
     * punishment) since it's what {@code LevelCurve.levelForToil} derives a Helper's level
     * from on every subsequent ticket award. Spending straight from it here would risk a
     * real de-level bug the next time the Helper banked any Toil at all. Returns false
     * (taking nothing) if the Helper can't afford it.
     */
    public boolean trySpendToil(UUID npcUuid, int amount) {
        if (spendableToilOf(npcUuid) < amount) {
            return false;
        }
        String sql = "UPDATE helper_ledger SET toil_spent_on_respec = toil_spent_on_respec + ? WHERE npc_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, amount);
            statement.setString(2, npcUuid.toString());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warning("Couldn't debit respec Toil for NPC " + npcUuid + ": " + e.getMessage());
            return false;
        }
    }
}

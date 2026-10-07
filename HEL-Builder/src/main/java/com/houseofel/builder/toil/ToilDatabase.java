package com.houseofel.builder.toil;

import org.bukkit.plugin.Plugin;
import org.sqlite.JDBC;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens and owns the plugin's single SQLite connection — authoritative storage for the
 * Toil chassis per the framework ("SQLite is authoritative... PDC alone does not survive
 * entity replacement"). One connection for the plugin's lifetime, used only from the main
 * thread — the same synchronous-I/O-on-the-main-thread assumption the old (now deleted)
 * {@code HelperLevelStore} already made for its YAML files. Ticket awards happen minutes
 * apart per Helper at most, nowhere near often enough to need pooling or async access.
 */
public final class ToilDatabase {

    private final Connection connection;

    public ToilDatabase(Plugin plugin) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "toil.db");
        try {
            // Explicit registration rather than relying purely on JDBC 4's ServiceLoader
            // auto-discovery — cheap defensive practice inside a plugin-loaded classpath.
            // (org.sqlite is deliberately NOT relocated by Shadow — see build.gradle.kts:
            // its native JNI bridge hardcodes org/sqlite/core/NativeDB at compile time,
            // which relocation can't rewrite and which breaks the connection entirely.)
            Class.forName(JDBC.class.getName());
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS helper_ledger (
                            npc_uuid TEXT PRIMARY KEY,
                            specialization TEXT NOT NULL,
                            level INTEGER NOT NULL,
                            banked_toil INTEGER NOT NULL
                        )
                        """);
                // helper_ledger predates owner_uuid/scar_choice — CREATE TABLE IF NOT
                // EXISTS alone won't add columns to an already-existing on-disk table, so
                // existing installs (Bartholomew, Thaddeus, Montgomery) need an explicit,
                // idempotent ALTER TABLE guarded by a PRAGMA table_info check.
                ensureColumn(statement, "helper_ledger", "owner_uuid", "TEXT");
                ensureColumn(statement, "helper_ledger", "scar_choice", "TEXT");
                // Toil actually spent on Choice-slot respecs — deliberately NOT subtracted
                // from banked_toil itself, since banked_toil feeds LevelCurve.levelForToil
                // on every subsequent ticket award and must stay monotonically
                // non-decreasing (except Death Policy's rank-floor, an intentional
                // punishment). "Spendable Toil" for a respec = banked_toil - this column.
                ensureColumn(statement, "helper_ledger", "toil_spent_on_respec", "INTEGER NOT NULL DEFAULT 0");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS toil_ticket (
                            order_id TEXT PRIMARY KEY,
                            npc_uuid TEXT NOT NULL,
                            ticket_kind TEXT NOT NULL,
                            raw_minutes INTEGER NOT NULL,
                            final_toil INTEGER NOT NULL,
                            awarded_at INTEGER NOT NULL
                        )
                        """);
                // Progress toward a threshold-based ticket (e.g. Groundworker's "512
                // blocks deposited"), keyed by the HELPER rather than by job — so a
                // Helper sent out on many separate small jobs still accumulates toward
                // its next ticket instead of losing that progress every time one job ends.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS ticket_progress (
                            npc_uuid TEXT NOT NULL,
                            ticket_kind TEXT NOT NULL,
                            progress_units INTEGER NOT NULL,
                            PRIMARY KEY (npc_uuid, ticket_kind)
                        )
                        """);
                // Append-only Chronicler-shaped event log — one row per death, never
                // updated or replaced. Deliberately generic (cause/world/x/y/z/timestamp)
                // rather than Helper-specific, per Kyle's note that a future Chronicler
                // should be able to read this without a Helper-only shape in the way.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS helper_scar (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            npc_uuid TEXT NOT NULL,
                            cause TEXT NOT NULL,
                            world TEXT NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            occurred_at INTEGER NOT NULL
                        )
                        """);
                // One row per (Helper, Choice-slot level) once picked — absent row = not
                // yet chosen, matching scar_choice's null-until-set convention. Generic
                // across every specialization's level-8/16 Choice slots on purpose:
                // interpretation of `choice` depends on (helper_ledger.specialization,
                // level), resolved at the read site via the relevant per-spec enum (e.g.
                // GroundworkerL8Choice.valueOf(...)) — this table itself has no opinion.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS helper_milestone_choice (
                            npc_uuid TEXT NOT NULL,
                            level INTEGER NOT NULL,
                            choice TEXT NOT NULL,
                            chosen_at INTEGER NOT NULL,
                            PRIMARY KEY (npc_uuid, level)
                        )
                        """);
                // One row per currently-rusted Helper; deleted the moment Rust clears.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS helper_rust (
                            npc_uuid TEXT PRIMARY KEY,
                            toil_remaining INTEGER NOT NULL,
                            death_world TEXT NOT NULL,
                            death_x INTEGER NOT NULL,
                            death_y INTEGER NOT NULL,
                            death_z INTEGER NOT NULL,
                            started_at INTEGER NOT NULL
                        )
                        """);
                // One row per Helper with a currently dropped-but-uncollected Work Ledger
                // book; deleted on recovery or once LedgerExpiryTask applies the
                // rank-floor fallback.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS helper_ledger_pending (
                            npc_uuid TEXT PRIMARY KEY,
                            dropped_at INTEGER NOT NULL
                        )
                        """);
                // Cumulative Toil banked per Helper per server-local calendar day, backing
                // the daily-taper pipeline stage. Unlike every other anti-grind tracker,
                // this one is persisted on purpose — losing it on a force-kill would let
                // the 150-Toil cap be bypassed by just restarting.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS daily_toil_totals (
                            npc_uuid TEXT NOT NULL,
                            day_key TEXT NOT NULL,
                            cumulative_toil INTEGER NOT NULL,
                            PRIMARY KEY (npc_uuid, day_key)
                        )
                        """);
            }
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Couldn't open the Toil ledger database", e);
        }
    }

    /**
     * Idempotent single-column migration guard — {@code CREATE TABLE IF NOT EXISTS} alone
     * doesn't add columns to a table that already exists on disk from an earlier version.
     * No precedent for this in the codebase before now (only new tables had ever been
     * added); this is the first schema change to an existing table.
     */
    private void ensureColumn(Statement statement, String table, String column, String columnDefinition)
            throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDefinition);
    }

    /** Public since {@code death.DeathRecordStore} needs the same shared connection {@code ToilLedgerStore} does. */
    public Connection connection() {
        return connection;
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            // Best-effort — the plugin is already shutting down.
        }
    }
}

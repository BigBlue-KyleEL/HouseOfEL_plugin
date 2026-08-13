package com.houseofel.builder.toil;

import org.bukkit.plugin.Plugin;
import org.sqlite.JDBC;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
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
            }
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Couldn't open the Toil ledger database", e);
        }
    }

    Connection connection() {
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

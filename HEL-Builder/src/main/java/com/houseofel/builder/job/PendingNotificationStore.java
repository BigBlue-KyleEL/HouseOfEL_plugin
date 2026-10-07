package com.houseofel.builder.job;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Messages queued for a player who was offline when they were sent — a job finishing
 * while its requester is away, mainly. Persisted so a message survives a restart too,
 * not just staying offline within one server session.
 */
final class PendingNotificationStore {

    private final File file;
    private final Logger logger;

    PendingNotificationStore(Plugin plugin) {
        this.file = new File(plugin.getDataFolder(), "pending_notifications.yml");
        this.logger = plugin.getLogger();
    }

    void add(UUID playerId, String message) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> messages = new ArrayList<>(yaml.getStringList(playerId.toString()));
        messages.add(message);
        yaml.set(playerId.toString(), messages);
        save(yaml);
    }

    /** Returns and clears whatever was queued for this player. */
    List<String> takeAll(UUID playerId) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> messages = yaml.getStringList(playerId.toString());
        if (!messages.isEmpty()) {
            yaml.set(playerId.toString(), null);
            save(yaml);
        }
        return messages;
    }

    private void save(YamlConfiguration yaml) {
        file.getParentFile().mkdirs();
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("Couldn't save pending notifications: " + e.getMessage());
        }
    }
}

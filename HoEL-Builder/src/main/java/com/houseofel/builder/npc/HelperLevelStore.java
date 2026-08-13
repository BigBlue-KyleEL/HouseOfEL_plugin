package com.houseofel.builder.npc;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One YAML file per Helper NPC ID under the plugin's data folder, matching
 * {@code JobStateStore}'s per-NPC-file convention. Unlike that store, records are kept
 * resident in memory and mutated directly rather than written through on every change —
 * XP updates on every block cleared, so disk writes are batched to {@link #saveAll()}
 * (called on plugin disable, and right after a specialization is assigned) instead of
 * happening on every single dig. A force-kill can lose recent XP the same way it already
 * loses in-progress job state — an accepted tradeoff, not new risk.
 */
final class HelperLevelStore {

    private final File helpersFolder;
    private final Logger logger;
    private final Map<Integer, HelperLevelRecord> records = new HashMap<>();

    HelperLevelStore(Plugin plugin) {
        this.helpersFolder = new File(plugin.getDataFolder(), "helpers");
        this.logger = plugin.getLogger();
        loadAll();
    }

    HelperLevelRecord get(int npcId) {
        return records.get(npcId);
    }

    void put(int npcId, HelperLevelRecord record) {
        records.put(npcId, record);
    }

    void saveAll() {
        helpersFolder.mkdirs();
        for (Map.Entry<Integer, HelperLevelRecord> entry : records.entrySet()) {
            HelperLevelRecord record = entry.getValue();
            YamlConfiguration yaml = new YamlConfiguration();
            if (record.specialization != null) {
                yaml.set("specialization", record.specialization.name());
            }
            yaml.set("xp", record.xp);
            try {
                yaml.save(fileFor(entry.getKey()));
            } catch (IOException e) {
                logger.warning("Couldn't save Helper level data for NPC #" + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    private void loadAll() {
        File[] files = helpersFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                int npcId = Integer.parseInt(file.getName().replace(".yml", ""));
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                String specializationName = yaml.getString("specialization");
                Specialization specialization = specializationName == null
                        ? null : Specialization.valueOf(specializationName);
                records.put(npcId, new HelperLevelRecord(specialization, yaml.getInt("xp")));
            } catch (RuntimeException e) {
                logger.warning("Couldn't parse Helper level file " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private File fileFor(int npcId) {
        return new File(helpersFolder, npcId + ".yml");
    }
}

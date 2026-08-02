package com.houseofel.builder.job;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Which block positions a player has placed something at, so a Clearing job can tell
 * natural terrain from someone's build. Kept resident in memory (checked on every dig,
 * unlike the other stores here which are queried rarely) with disk as a durability
 * backstop, written through on every change rather than batched — consistent with how
 * every other store in this plugin favors surviving a hard stop over batching writes.
 */
final class PlayerPlacementStore {

    private final File file;
    private final Logger logger;
    private final Set<String> marked;

    PlayerPlacementStore(Plugin plugin) {
        this.file = new File(plugin.getDataFolder(), "player_placed_blocks.yml");
        this.logger = plugin.getLogger();
        this.marked = new HashSet<>(YamlConfiguration.loadConfiguration(file).getStringList("blocks"));
    }

    void markPlaced(World world, int x, int y, int z) {
        if (marked.add(key(world, x, y, z))) {
            save();
        }
    }

    void clear(World world, int x, int y, int z) {
        if (marked.remove(key(world, x, y, z))) {
            save();
        }
    }

    boolean isMarked(World world, int x, int y, int z) {
        return marked.contains(key(world, x, y, z));
    }

    private String key(World world, int x, int y, int z) {
        return world.getName() + "," + x + "," + y + "," + z;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blocks", new ArrayList<>(marked));
        file.getParentFile().mkdirs();
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("Couldn't save player-placed block tracking: " + e.getMessage());
        }
    }
}

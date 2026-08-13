package com.houseofel.builder.job;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Reads and writes one YAML file per Helper's paused/in-progress job, under the plugin's data folder. */
final class JobStateStore {

    private final File jobsFolder;
    private final Logger logger;

    JobStateStore(Plugin plugin) {
        this.jobsFolder = new File(plugin.getDataFolder(), "jobs");
        this.logger = plugin.getLogger();
    }

    void save(JobState state) {
        jobsFolder.mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcId", state.npcId);
        yaml.set("playerId", state.playerId.toString());
        yaml.set("worldName", state.worldName);
        yaml.set("minX", state.minX);
        yaml.set("maxX", state.maxX);
        yaml.set("minY", state.minY);
        yaml.set("maxY", state.maxY);
        yaml.set("minZ", state.minZ);
        yaml.set("maxZ", state.maxZ);
        yaml.set("target", state.target);
        yaml.set("tool", state.tool);
        yaml.set("surfaceOnly", state.surfaceOnly);
        yaml.set("storeInChest", state.storeInChest);
        yaml.set("processedCells", state.processedCells);
        yaml.set("clearedCells", state.clearedCells);
        yaml.set("deposited", state.deposited);
        yaml.set("passNumber", state.passNumber);
        yaml.set("clearedThisPass", state.clearedThisPass);
        yaml.set("skippedThisPass", state.skippedThisPass);
        yaml.set("skippedNoPath", state.skippedNoPath);
        yaml.set("skippedTimeout", state.skippedTimeout);
        for (var entry : state.carried.entrySet()) {
            yaml.set("carried." + entry.getKey(), entry.getValue());
        }
        yaml.set("chests", state.chests);
        yaml.set("occupiedColumns", state.occupiedColumns);
        yaml.set("anchor", state.anchor);
        yaml.set("lastCubeAnchor", state.lastCubeAnchor);
        yaml.set("rowFoot0", state.rowFoot0);
        yaml.set("rowFoot1", state.rowFoot1);
        yaml.set("cubeUnitIndex", state.cubeUnitIndex);
        yaml.set("rowSign", state.rowSign);
        yaml.set("columnSign", state.columnSign);

        try {
            yaml.save(fileFor(state.npcId));
        } catch (IOException e) {
            logger.warning("Couldn't save job state for NPC #" + state.npcId + ": " + e.getMessage());
        }
    }

    void delete(int npcId) {
        fileFor(npcId).delete();
    }

    List<JobState> loadAll() {
        File[] files = jobsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        List<JobState> states = new ArrayList<>();
        if (files == null) {
            return states;
        }
        for (File file : files) {
            JobState state = load(file);
            if (state != null) {
                states.add(state);
            }
        }
        return states;
    }

    private JobState load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try {
            JobState state = new JobState();
            state.npcId = yaml.getInt("npcId");
            state.playerId = UUID.fromString(yaml.getString("playerId"));
            state.worldName = yaml.getString("worldName");
            state.minX = yaml.getInt("minX");
            state.maxX = yaml.getInt("maxX");
            state.minY = yaml.getInt("minY");
            state.maxY = yaml.getInt("maxY");
            state.minZ = yaml.getInt("minZ");
            state.maxZ = yaml.getInt("maxZ");
            state.target = yaml.getString("target");
            state.tool = yaml.getString("tool");
            state.surfaceOnly = yaml.getBoolean("surfaceOnly");
            state.storeInChest = yaml.getBoolean("storeInChest");
            state.processedCells = yaml.getLong("processedCells");
            state.clearedCells = yaml.getLong("clearedCells");
            state.deposited = yaml.getLong("deposited");
            state.passNumber = yaml.getInt("passNumber", 1);
            state.clearedThisPass = yaml.getLong("clearedThisPass");
            state.skippedThisPass = yaml.getLong("skippedThisPass");
            state.skippedNoPath = yaml.getLong("skippedNoPath");
            state.skippedTimeout = yaml.getLong("skippedTimeout");
            if (yaml.isConfigurationSection("carried")) {
                for (String key : yaml.getConfigurationSection("carried").getKeys(false)) {
                    state.carried.put(key, yaml.getInt("carried." + key));
                }
            }
            state.chests = yaml.getStringList("chests");
            state.occupiedColumns = yaml.getStringList("occupiedColumns");
            state.anchor = yaml.getString("anchor");
            state.lastCubeAnchor = yaml.getString("lastCubeAnchor");
            state.rowFoot0 = yaml.getString("rowFoot0");
            state.rowFoot1 = yaml.getString("rowFoot1");
            state.cubeUnitIndex = yaml.getInt("cubeUnitIndex");
            state.rowSign = yaml.getInt("rowSign");
            state.columnSign = yaml.getInt("columnSign");
            return state;
        } catch (RuntimeException e) {
            logger.warning("Couldn't parse job state file " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private File fileFor(int npcId) {
        return new File(jobsFolder, npcId + ".yml");
    }
}

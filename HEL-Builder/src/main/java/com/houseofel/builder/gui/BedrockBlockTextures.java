package com.houseofel.builder.gui;

import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/** Offline lookup of verified vanilla Bedrock paths. Never guess a path for an unknown block. */
public final class BedrockBlockTextures {
    private static final Map<Material, String> PATHS = load();

    private BedrockBlockTextures() {}

    /** Null means the result should remain a normal text-only button. */
    public static String pathFor(Material material) {
        return PATHS.get(material);
    }

    private static Map<Material, String> load() {
        Properties properties = new Properties();
        try (InputStream stream = BedrockBlockTextures.class.getResourceAsStream("/bedrock-block-textures.properties")) {
            if (stream == null) throw new IllegalStateException("Missing Bedrock block texture lookup");
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Bedrock block textures", e);
        }
        Map<Material, String> paths = new EnumMap<>(Material.class);
        for (String name : properties.stringPropertyNames()) {
            paths.put(Material.valueOf(name), properties.getProperty(name));
        }
        return Map.copyOf(paths);
    }
}

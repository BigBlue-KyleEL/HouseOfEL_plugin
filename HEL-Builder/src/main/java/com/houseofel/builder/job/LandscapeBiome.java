package com.houseofel.builder.job;

public enum LandscapeBiome {
    PLAINS("Plains"),
    DESERT("Desert"),
    BADLANDS("Badlands"),
    SNOWY("Snowy Tundra"),
    TAIGA("Taiga"),
    JUNGLE("Jungle"),
    MUSHROOM("Mushroom Island");

    private final String label;
    LandscapeBiome(String label) { this.label = label; }
    public String label() { return label; }
}

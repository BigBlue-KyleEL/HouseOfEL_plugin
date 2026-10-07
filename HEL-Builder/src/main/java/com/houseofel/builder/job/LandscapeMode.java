package com.houseofel.builder.job;

/** Which terrain-building style the Landscaper uses — selected before the area is marked. */
public enum LandscapeMode {
    FILL("Fill to the Brim"),
    SLOPE("Sloping Terrain"),
    REDESIGN("Redesign");

    private final String label;

    LandscapeMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

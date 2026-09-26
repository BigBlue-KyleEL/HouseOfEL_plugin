package com.houseofel.common.net;

public enum Anchor {
    TOP_LEFT(0f, 0f),
    TOP_CENTER(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    CENTER_LEFT(0f, 0.5f),
    CENTER(0.5f, 0.5f),
    CENTER_RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM_CENTER(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f);

    private final float xFraction;
    private final float yFraction;

    Anchor(float xFraction, float yFraction) {
        this.xFraction = xFraction;
        this.yFraction = yFraction;
    }

    public float xFraction() { return xFraction; }
    public float yFraction() { return yFraction; }

    public static Anchor fromWire(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TOP_LEFT;
        }
    }
}

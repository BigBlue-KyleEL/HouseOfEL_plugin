package com.houseofel.core.border;

/**
 * Pure math for the per-player "rectangle" world border. Vanilla borders are squares around
 * one center, so each player gets a square whose side is the rectangle's short dimension:
 * the short axis is centred on the rectangle's midpoint (its edges sit exactly on the box),
 * and the long axis follows the player, clamped so the near edge lands on the box limit.
 */
public record RectBorderMath(double minX, double maxX, double minZ, double maxZ, int updateStep) {

    /** Vanilla's maximum world-border diameter (WorldBorder#getMaxSize). */
    public static final double VANILLA_MAX_SIZE = 5.9999968E7;

    public record Center(double x, double z) {}

    public RectBorderMath {
        updateStep = Math.max(1, updateStep);
    }

    /** @return null when the box is usable, otherwise a one-line reason it is not. */
    public static String validate(double minX, double maxX, double minZ, double maxZ) {
        if (!(minX < maxX)) return "min-x (" + minX + ") must be less than max-x (" + maxX + ")";
        if (!(minZ < maxZ)) return "min-z (" + minZ + ") must be less than max-z (" + maxZ + ")";
        double side = Math.min(maxX - minX, maxZ - minZ);
        if (side > VANILLA_MAX_SIZE) {
            return "box short side (" + side + ") exceeds the vanilla max border size (" + VANILLA_MAX_SIZE + ")";
        }
        return null;
    }

    public double width() { return maxX - minX; }

    public double depth() { return maxZ - minZ; }

    public double side() { return Math.min(width(), depth()); }

    /** The square's center for a player standing at (x, z). */
    public Center centerFor(double x, double z) {
        return new Center(axisCenter(x, minX, maxX), axisCenter(z, minZ, maxZ));
    }

    private double axisCenter(double coord, double min, double max) {
        double half = side() / 2.0;
        double lo = min + half;
        double hi = max - half;
        if (lo >= hi) return (min + max) / 2.0; // short axis (or square): fixed at the midpoint
        // At/past a limit the center sits exactly on it, so the near edge lands on the box.
        if (coord <= lo) return lo;
        if (coord >= hi) return hi;
        // Grid anchored on the low limit, so stepping never pulls a center off the box.
        double snapped = lo + Math.round((coord - lo) / updateStep) * (double) updateStep;
        return Math.clamp(snapped, lo, hi);
    }
}

package com.houseofel.builder.death;

import org.bukkit.Location;

/**
 * The one and only place Rust's pacing effect lives — mirrors {@code HelperTempo}'s role
 * for level, but for the temporary post-recovery penalty instead. Applied on top of
 * {@code HelperTempo}'s level-based baseline whenever a Helper is currently rusted (i.e.
 * has a {@link RustState} row in {@code helper_rust}).
 */
public final class HelperRust {

    /** Toil of work needed to clear Rust, per the framework. */
    public static final int TOTAL_RUST_TOIL = 200;
    /** How long the death block itself stays refused, absent enough work to clear Rust first. */
    private static final long REFUSAL_HOURS = 24;

    private static final double DUTY_FLOOR = 0.50;
    private static final double ERROR_MULTIPLIER = 2.0;
    private static final double TOIL_MULTIPLIER = 0.5;

    private HelperRust() {
    }

    /** Duty cycle capped at 50% while rusted — never raises an already-low duty cycle. */
    public static double dutyCycleFor(double baseDutyCycle) {
        return Math.min(baseDutyCycle, DUTY_FLOOR);
    }

    /** Error rate doubled while rusted. */
    public static double errorRateFor(double baseErrorRate) {
        return Math.min(1.0, baseErrorRate * ERROR_MULTIPLIER);
    }

    /** Toil award halved while rusted. */
    public static int toilFor(int baseToil) {
        return (int) Math.floor(baseToil * TOIL_MULTIPLIER);
    }

    /** True while this Helper still refuses to enter the exact block it died in. */
    public static boolean blocksReentry(RustState rust, Location candidate, long nowMillis) {
        if (!rust.deathWorld().equals(candidate.getWorld().getName())
                || rust.deathX() != candidate.getBlockX()
                || rust.deathY() != candidate.getBlockY()
                || rust.deathZ() != candidate.getBlockZ()) {
            return false;
        }
        return nowMillis - rust.startedAt() < REFUSAL_HOURS * 60 * 60 * 1000;
    }
}

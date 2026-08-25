package com.houseofel.builder.timing;

/**
 * The one and only place a Helper's LEVEL is allowed to affect how it works. Everything
 * level-dependent about pacing lives here so there is a single file to read — or revert —
 * rather than level checks scattered through the job engine.
 *
 * <p>Two of the three levers here are the framework's sanctioned chassis refinements
 * ({@link #dutyCycleFor}, {@link #errorRateFor}), granted automatically at every level
 * 2-20. The third ({@link #digTicksFor}) is a deliberate, documented deviation — see its
 * own note.
 */
public final class HelperTempo {

    /** Duty cycle runs 70% at L1 to 89% at L20 — the framework's DutyCycle(L) = 69% + L%. */
    private static final double DUTY_CYCLE_BASE = 0.69;

    /** Error rate decays linearly from 8% at L1 to 0.5% at L20. */
    private static final double ERROR_RATE_AT_L1 = 0.08;
    private static final double ERROR_RATE_AT_L20 = 0.005;

    /**
     * Nominal ticks of real work in one block cycle, used only to convert a duty-cycle
     * percentage into a concrete pause. Idle time is {@code work * (1 - duty) / duty}, so
     * this sets how large a difference the duty cycle makes in practice: at 20 it's about
     * a 9-tick pause at L1 against 2 at L20 — visible without being tedious.
     */
    private static final int NOMINAL_WORK_TICKS = 20;

    /**
     * DELIBERATE DEVIATION FROM THE FRAMEWORK, Kyle's explicit call 2026-08-13.
     *
     * <p>A Helper digs faster as it levels: vanilla break time below L7, then 1.5x faster
     * below L14, then 3x faster from L14 up. The framework's frozen list forbids this
     * outright ("TICKS PER ATOMIC ACTION — block break time" is untouchable, "a level 20
     * Miner with a stone pickaxe mines exactly like a stone pickaxe"), and a 3x span
     * across a career sits well outside its 1.45x lifetime throughput cap — a maxed
     * Helper here genuinely does out-dig a player holding the same tool. Kept because the
     * visible "he's gotten quicker" progression is wanted more than strict compliance,
     * with the trade-off understood.
     *
     * <p>Chosen so the numbers land exactly where the pre-service code already had them
     * for the current targets — dirt runs 3 ticks at L1 and 1 tick at L14, unchanged —
     * while still deriving from {@link VanillaTiming#durationFor} rather than ignoring it,
     * so tool and block finally matter: stone and oak start from 8 and 10 ticks rather
     * than pretending every block is dirt.
     *
     * <p>Reverting to full compliance is one edit: return a divisor of 1 at every level.
     */
    private static final double VETERAN_DIG_DIVISOR = 3.0;
    private static final double JOURNEYMAN_DIG_DIVISOR = 1.5;
    private static final int JOURNEYMAN_DIG_FROM_LEVEL = 7;
    private static final int VETERAN_DIG_FROM_LEVEL = 14;

    /**
     * SECOND DELIBERATE DEVIATION FROM THE FRAMEWORK, Kyle's explicit call 2026-08-25 —
     * same category as {@link #VETERAN_DIG_DIVISOR}, recorded with the same candour.
     *
     * <p>A Helper walks faster as it levels: 1.0 at L1 rising linearly to
     * {@link #WALK_SPEED_AT_L20} at L20. Movement speed is not a chassis refinement the
     * framework grants, and stacked on top of the existing 3x dig deviation it widens the
     * gap from the framework's 1.45x lifetime throughput cap further still.
     *
     * <p>Chosen because of a real measurement, not a preference: by L8 a Helper already
     * digs stone in ONE TICK — the hard floor, since no block can break faster than a
     * single game tick — so dig speed has no headroom left to express levels 9-20 at all.
     * Walking, meanwhile, was a flat 1.3 at every level and is what actually dominates the
     * time a player watches. Scaling it is the only lever left that makes leveling
     * perceptible past L8.
     *
     * <p>The curve is deliberately anchored so L8 lands on 1.3 — the value every Helper
     * used at every level before this — meaning a level-8 Quarryman (the level the perk
     * unlocks at) moves exactly as it did when Kyle signed off on the current feel.
     * Everything above L8 is new gain; everything below is new, intentional slowness.
     *
     * <p>Reverting is one edit, same as the dig deviation: return 1.3 at every level.
     */
    private static final double WALK_SPEED_AT_L1 = 1.0;
    private static final double WALK_SPEED_AT_L20 = 1.8;

    private HelperTempo() {
    }

    /**
     * Navigator speed modifier for a Helper of this level — see {@link #WALK_SPEED_AT_L1}
     * for why this scales at all. Read once when a job starts its navigator, so a Helper
     * that levels up mid-job keeps its old speed until the next dispatch or resume; not
     * worth re-reading per tick for a change this small.
     */
    public static float walkSpeedFor(int level) {
        int clamped = Math.max(1, Math.min(20, level));
        double progress = (clamped - 1) / 19.0;
        return (float) (WALK_SPEED_AT_L1 + progress * (WALK_SPEED_AT_L20 - WALK_SPEED_AT_L1));
    }

    /** Fraction of its time a Helper of this level spends actually working. */
    public static double dutyCycleFor(int level) {
        return DUTY_CYCLE_BASE + (level / 100.0);
    }

    /** Chance this Helper fumbles a given action — 8% at L1 down to 0.5% at L20. */
    public static double errorRateFor(int level) {
        double progress = (level - 1) / 19.0;
        return ERROR_RATE_AT_L1 + progress * (ERROR_RATE_AT_L20 - ERROR_RATE_AT_L1);
    }

    /**
     * How long a Helper of this level dithers between actions — re-checking itself,
     * hesitating before the next block. This is the duty cycle made concrete, and it is
     * the framework's sanctioned throughput lever: not faster hands, just less standing
     * around. "Every point of duty cycle is a specific nameable stupidity being removed."
     */
    public static int hesitationTicksFor(int level) {
        return hesitationTicksForDuty(dutyCycleFor(level));
    }

    /**
     * Same conversion as {@link #hesitationTicksFor}, but from an already-resolved duty
     * cycle rather than a raw level — the caller's entry point when Rust/WARY have
     * adjusted duty away from {@link #dutyCycleFor}'s level curve (see
     * {@code HelperLevelService.dutyCycleOf}).
     */
    public static int hesitationTicksForDuty(double duty) {
        return (int) Math.round(NOMINAL_WORK_TICKS * (1.0 - duty) / duty);
    }

    /** Ticks to break a block at this level — see {@link #VETERAN_DIG_DIVISOR} for the deviation note. */
    public static int digTicksFor(int level, int vanillaTicks) {
        return Math.max(1, (int) Math.round(vanillaTicks / digDivisorFor(level)));
    }

    private static double digDivisorFor(int level) {
        if (level >= VETERAN_DIG_FROM_LEVEL) {
            return VETERAN_DIG_DIVISOR;
        }
        if (level >= JOURNEYMAN_DIG_FROM_LEVEL) {
            return JOURNEYMAN_DIG_DIVISOR;
        }
        return 1.0;
    }

    /**
     * Runtime guard on the framework's 1.45x lifetime throughput cap, checked once at
     * startup. Deliberately scoped to the CHASSIS levers — duty cycle and error rate —
     * and excludes {@link #digTicksFor} and {@link #walkSpeedFor}, both known, accepted
     * deviations (see their notes). Narrowing the assertion rather than deleting it means it still catches
     * accidental drift in the parts that are meant to stay compliant.
     *
     * @return null when within cap, or a description of the breach for the caller to log
     */
    public static String checkChassisThroughputCap() {
        double dutyGain = dutyCycleFor(20) / dutyCycleFor(1);
        double errorGain = (1.0 - errorRateFor(20)) / (1.0 - errorRateFor(1));
        double lifetimeGain = dutyGain * errorGain;
        if (lifetimeGain > 1.45) {
            return String.format(
                    "Chassis throughput growth is %.3fx, above the framework's 1.45x cap "
                            + "(duty %.3fx x correctness %.3fx)", lifetimeGain, dutyGain, errorGain);
        }
        return null;
    }
}

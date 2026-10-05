package com.houseofel.builder.visual;

import java.util.function.BiConsumer;

/** Tick-driven visual state only. Never delays a job or touches Citizens navigation. */
public final class GroundworkerAnimation {
    public enum Clip {
        IDLE("idle", 0), WALK("walk", 0), RUSTED_IDLE("rusted_idle", 0),
        DRAW("shovel_draw", 70), DIG("dig", 0), STOW("shovel_stow", 70),
        PLACE("place", 24), GREET("greet", 42), LEVELUP("levelup", 90), RECOVER("levelup_recover", 110);
        public final String name;
        public final int ticks;
        Clip(String name, int ticks) { this.name = name; this.ticks = ticks; }
    }
    private final BiConsumer<Clip, Boolean> player;
    private final float animationSpeed;
    private final java.util.function.BooleanSupplier rendered;
    private Clip clip;
    private long until;
    private boolean drawn;
    private boolean levelPending;
    private int greetings;
    private boolean placementPending;
    private long idleUntil;
    public GroundworkerAnimation(BiConsumer<Clip, Boolean> player) { this(player, 1.0); }
    public GroundworkerAnimation(BiConsumer<Clip, Boolean> player, double animationSpeed) {
        this(player, animationSpeed, () -> true);
    }
    public GroundworkerAnimation(BiConsumer<Clip, Boolean> player, double animationSpeed,
                                 java.util.function.BooleanSupplier rendered) {
        this.player = player;
        this.rendered = rendered;
        this.animationSpeed = validateSpeed(animationSpeed);
    }
    public static float validateSpeed(double value) {
        float speed = (float) value;
        if (!Float.isFinite(speed) || speed <= 0) throw new IllegalArgumentException("Animation speed must be finite and positive");
        return speed;
    }
    /** Round up to a server tick so a one-shot is never cut short. Loop clips use zero. */
    public long scaledTicks(int baseTicks) {
        return baseTicks == 0 ? 0 : Math.max(1L, (long) Math.ceil(baseTicks / (double) animationSpeed));
    }
    /** Level-up is an authored celebration: the working-speed setting never accelerates it. */
    static float speedFor(String clipName, float configuredSpeed) {
        return clipName.equals(Clip.LEVELUP.name) || clipName.equals(Clip.RECOVER.name) ? 1f : configuredSpeed;
    }
    public long scaledTicks(Clip next) {
        return next.ticks == 0 ? 0 : Math.max(1L,
                (long) Math.ceil(next.ticks / (double) speedFor(next.name, animationSpeed)));
    }
    public Clip clip() { return clip; }
    public void levelUp() { levelPending = true; }
    public void greet() { greetings++; }
    public void place() {
        if (!placementPending && clip != Clip.PLACE) placementPending = true;
    }
    public void replay() { if (clip != null) player.accept(clip, clip == Clip.DRAW); }
    public void preview(Clip next, long now) {
        if (next == Clip.LEVELUP) { levelPending = true; return; }
        // Preview is visual only; session flags are still reconciled by tick.
        set(next, now);
    }
    public void tick(long now, boolean session, boolean moving, boolean rusted) {
        if (clip == Clip.LEVELUP) {
            if (finished(now)) set(Clip.RECOVER, now);
            return;
        }
        if (clip == Clip.RECOVER) {
            if (finished(now)) {
                idleUntil = now + scaledTicks(1);
                set(rusted ? Clip.RUSTED_IDLE : Clip.IDLE, now);
            }
            return;
        }
        if (levelPending) {
            levelPending = false;
            set(Clip.LEVELUP, now);
            return;
        }
        if (now < idleUntil) return;
        if (clip == Clip.DRAW) {
            if (!finished(now)) return;
            drawn = true;
            if (!session) { set(Clip.STOW, now); return; }
            set(moving ? Clip.WALK : Clip.DIG, now);
        }
        if (clip == Clip.STOW) {
            if (!finished(now)) return;
            drawn = false;
        }
        if ((clip == Clip.PLACE || clip == Clip.GREET) && !finished(now)) return;
        if (!session && drawn) { set(Clip.STOW, now); return; }
        if (session && !drawn) { set(Clip.DRAW, now); return; }
        if (greetings > 0) { greetings--; set(Clip.GREET, now); return; }
        if (placementPending) { set(Clip.PLACE, now); return; }
        set(moving ? Clip.WALK : session ? Clip.DIG : rusted ? Clip.RUSTED_IDLE : Clip.IDLE, now);
    }
    private boolean finished(long now) { return now >= until && rendered.getAsBoolean(); }
    private void set(Clip next, long now) {
        if (clip == next && next.ticks == 0) return;
        if (next == Clip.DRAW) drawn = true;
        if (next == Clip.STOW) drawn = false;
        if (next == Clip.PLACE) placementPending = false;
        clip = next;
        until = now + scaledTicks(next);
        player.accept(next, next == Clip.DRAW);
    }
}

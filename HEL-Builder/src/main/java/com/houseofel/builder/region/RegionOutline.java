package com.houseofel.builder.region;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Draws the particle box around a work region.
 *
 * <p>The outline is END_ROD, which has the bright glow we want but accepts no colour
 * data at all. To tint it, a DUST particle is layered on the same points and its
 * <em>size</em> is eased up and down — that reads as the glow warming to yellow and
 * back, without swapping the base particle for a flatter-looking one.
 *
 * <p>Cost is deliberately bounded: each edge emits at most a fixed number of points
 * regardless of how long it is, and only players near the region get sent anything.
 * A naive one-particle-per-block outline scales with region size and would be a real
 * packet load on a big mega-build region.
 */
public final class RegionOutline {

    private static final int MAX_POINTS_PER_EDGE = 24;
    private static final double VIEW_DISTANCE = 96.0;
    /** A full fade out and back, i.e. ~1.5s each way. */
    private static final int FADE_CYCLE_TICKS = 60;
    private static final float MAX_TINT_SIZE = 1.6f;
    /** Below this the dust is too faint to be worth the packet. */
    private static final float MIN_VISIBLE_TINT = 0.15f;

    public static final Color WORKING_TINT = Color.fromRGB(255, 225, 60);

    private final World world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public RegionOutline(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /** The plain white glow — the whole outline while a region awaits confirmation. */
    public void drawGlowFor(Player player) {
        forEachEdgePoint((x, y, z) -> player.spawnParticle(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0));
    }

    public void drawGlowNearby() {
        forEachNearbyPlayer(this::drawGlowFor);
    }

    /** Colour layered over the glow. Size drives how strongly the tint reads. */
    public void drawTintNearby(Color color, float size) {
        if (size < MIN_VISIBLE_TINT) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        forEachNearbyPlayer(player -> forEachEdgePoint((x, y, z) ->
                player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust)));
    }

    /** Eased 0 -> full -> 0, so the tint breathes instead of sawtoothing. */
    public static float tintSize(int elapsedTicks) {
        double phase = (2 * Math.PI * elapsedTicks) / FADE_CYCLE_TICKS;
        return (float) (MAX_TINT_SIZE * (0.5 - 0.5 * Math.cos(phase)));
    }

    private void forEachNearbyPlayer(PlayerAction action) {
        Location centre = new Location(world,
                (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        double range = VIEW_DISTANCE + spanRadius();

        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(centre) <= range) {
                action.accept(player);
            }
        }
    }

    private double spanRadius() {
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2.0;
    }

    private void forEachEdgePoint(EdgePoint consumer) {
        double lowX = minX;
        double lowY = minY;
        double lowZ = minZ;
        double highX = maxX + 1.0;
        double highY = maxY + 1.0;
        double highZ = maxZ + 1.0;

        double stepX = step(highX - lowX);
        double stepY = step(highY - lowY);
        double stepZ = step(highZ - lowZ);

        for (double x = lowX; x <= highX; x += stepX) {
            consumer.accept(x, lowY, lowZ);
            consumer.accept(x, lowY, highZ);
            consumer.accept(x, highY, lowZ);
            consumer.accept(x, highY, highZ);
        }
        for (double y = lowY; y <= highY; y += stepY) {
            consumer.accept(lowX, y, lowZ);
            consumer.accept(lowX, y, highZ);
            consumer.accept(highX, y, lowZ);
            consumer.accept(highX, y, highZ);
        }
        for (double z = lowZ; z <= highZ; z += stepZ) {
            consumer.accept(lowX, lowY, z);
            consumer.accept(lowX, highY, z);
            consumer.accept(highX, lowY, z);
            consumer.accept(highX, highY, z);
        }
    }

    /** Spaces points out on long edges so a huge region costs no more than a small one. */
    private double step(double edgeLength) {
        return Math.max(1.0, edgeLength / MAX_POINTS_PER_EDGE);
    }

    @FunctionalInterface
    private interface EdgePoint {
        void accept(double x, double y, double z);
    }

    @FunctionalInterface
    private interface PlayerAction {
        void accept(Player player);
    }
}

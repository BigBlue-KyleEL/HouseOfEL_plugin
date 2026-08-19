package com.houseofel.builder.job;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Replaces Citizens' own bundled {@code TeleportStuckAction} (used by every Clearing job
 * until 2026-08-19). Decompiled the real one from the shipped Citizens jar to confirm
 * exactly what it does when the navigator reports itself stuck: search up to 10 blocks
 * upward from the nav target's own column for a block {@link MinecraftBlockExaminer#canStandOn}
 * approves, and teleport onto it — but if none of those 10 checks pass, it gives up and
 * teleports the NPC there ANYWAY, with no standability check at all on that fallback spot.
 * Right next to an irregular, partially-hollow pit — exactly the terrain a Groundworker's
 * own Clearing job creates — that's a confirmed way to drop a Helper into open air on
 * repeat, reading to a player as "climbs, then suddenly falls." Distinct from (and in
 * addition to) the blind straight-line-fallback issue fixed the same day in
 * {@link ClearJobTask#walkToPendingBlock()} — this one is Citizens' own internal
 * stuck-recovery, triggered by its own shorter-fused stuck detector, so our
 * noProgressTicks/walkTicks tracking never sees it coming.
 *
 * <p>Same search, but never teleports onto a spot it hasn't actually confirmed is safe to
 * stand on. If the search comes up empty, this does nothing — leaving the NPC exactly
 * where it is and letting {@code ClearJobTask}'s own timeout handling (already safe: it
 * either force-digs from wherever the Helper stands or abandons the block) resolve the
 * situation instead of gambling on a blind relocation.
 */
public final class SafeStuckAction implements StuckAction {

    public static final SafeStuckAction INSTANCE = new SafeStuckAction();

    /** Matches the real TeleportStuckAction's own search depth — not a new tuning choice. */
    private static final int MAX_ITERATIONS = 10;

    private SafeStuckAction() {
    }

    @Override
    public boolean run(NPC npc, Navigator navigator) {
        if (!npc.isSpawned()) {
            return false;
        }
        Location target = navigator.getTargetAsLocation();
        if (target == null || target.getWorld() != npc.getEntity().getWorld()) {
            return true;
        }

        Block current = target.getBlock().getRelative(BlockFace.DOWN);
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (MinecraftBlockExaminer.canStandOn(current)) {
                npc.teleport(current.getRelative(BlockFace.UP).getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                return false;
            }
            current = current.getRelative(BlockFace.UP);
        }
        // No confirmed-solid spot found nearby — do nothing rather than teleport blind.
        return true;
    }
}

package com.houseofel.builder.timing;

import com.houseofel.builder.gui.TaskType;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * How long one atomic action takes, in ticks — the single place any Helper action timing
 * is allowed to come from. This is Perk Design Rule 1 from [[NPC Progression Framework]],
 * and the signature is the whole point of it:
 *
 * <pre>durationFor(TaskType, ToolStack, BlockState) → ticks</pre>
 *
 * <p>It takes no level, no rank, no perk and no Helper reference, and it never will. A
 * perk that wants to make an action faster as a Helper levels cannot be expressed against
 * this signature — it fails to compile rather than failing review. The framework's own
 * reasoning: "reviewer discipline decays across 27 ladders and six designers; a type
 * signature does not."
 *
 * <p>{@code taskType} is unused today — every wired task breaks blocks, so the block and
 * tool fully determine the answer. It stays in the signature deliberately: it's part of
 * Rule 1's published contract, and non-breaking tasks (a Smelter's furnace cycle, a
 * Postmaster's leg) will need it.
 */
public final class VanillaTiming {

    /**
     * Vanilla's block-breaking constants: accumulated damage per tick is
     * {@code speed / hardness / divisor}, where the divisor is 30 when the tool can
     * actually harvest the block and 100 when it can't.
     */
    private static final int CORRECT_TOOL_DIVISOR = 30;
    private static final int WRONG_TOOL_DIVISOR = 100;

    private VanillaTiming() {
    }

    /** Ticks to break this block with this tool, exactly as vanilla would. Never below 1. */
    public static int durationFor(TaskType taskType, ItemStack tool, BlockData blockState) {
        float hardness = blockState.getMaterial().getHardness();
        if (hardness <= 0.0f) {
            return 1;
        }

        // Paper's getDestroySpeed documents only "default value is 1.0" — it's vanilla's
        // tool-speed multiplier against this block, not a finished duration, so the
        // damage-per-tick formula below still has to be applied to it.
        float speed = blockState.getDestroySpeed(tool, true);
        boolean canHarvest = !blockState.requiresCorrectToolForDrops() || speed > 1.0f;
        float damagePerTick = speed / hardness / (canHarvest ? CORRECT_TOOL_DIVISOR : WRONG_TOOL_DIVISOR);

        if (damagePerTick >= 1.0f) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(1.0f / damagePerTick));
    }
}

package com.houseofel.builder.gui;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Maps one real world block to the tool that actually harvests it — the per-block
 * counterpart to {@link Target}'s per-job material families. Needed because a mixed job
 * ({@link Target#ANY_EARTH}) can hand the digger a Stone block one moment and a Dirt
 * block the next, and {@link TaskType}'s fixed tool-per-task-type can't express that.
 * Deliberately its own class rather than folded into Target, since ANY_EARTH spans many
 * real tool families at once and can't answer "what tool for THIS specific block" alone.
 *
 * <p>Backed by Mojang's own {@code minecraft:mineable/*} tags (2026-08-18) rather than a
 * hand-picked material list — the same source of truth vanilla itself uses to decide
 * whether a tool is "correct" for drops (see the zero-cobblestone bug this fixed earlier
 * tonight), so every stone variant, ore, and soft-ground material {@link Target#ANY_EARTH}
 * now matches automatically gets the right tool without needing a matching new line here.
 */
public final class BlockTool {

    /** Fallback for any material tagged under none of the below — matches TaskType.CLEAR's old default. */
    private static final Material DEFAULT_TOOL = Material.IRON_SHOVEL;

    private BlockTool() {
    }

    public static Material bestToolFor(Material blockType) {
        if (Tag.MINEABLE_PICKAXE.isTagged(blockType) || Target.isInfested(blockType)) {
            return Material.IRON_PICKAXE;
        }
        if (Tag.MINEABLE_SHOVEL.isTagged(blockType)) {
            return Material.IRON_SHOVEL;
        }
        if (Tag.MINEABLE_AXE.isTagged(blockType)) {
            return Material.IRON_AXE;
        }
        if (Tag.MINEABLE_HOE.isTagged(blockType)) {
            return Material.IRON_HOE;
        }
        return DEFAULT_TOOL;
    }
}

package com.houseofel.builder.job;

import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.antigrind.TaskFingerprint;
import com.houseofel.builder.gui.TaskType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import java.util.UUID;

final class CofferdamWork {
    private CofferdamWork() { }
    static boolean wet(Block block) {
        Material type=block.getType();
        return type==Material.WATER || type==Material.SEAGRASS || type==Material.TALL_SEAGRASS
                || type==Material.KELP || type==Material.KELP_PLANT
                || block.getBlockData() instanceof Waterlogged data && data.isWaterlogged();
    }
    static void drain(Block block) {
        if (block.getBlockData() instanceof Waterlogged data && data.isWaterlogged()) {
            data.setWaterlogged(false);
            block.setBlockData(data,false);
        } else if (wet(block)) block.setType(Material.AIR,false);
    }
    static int credit(Block block, UUID helper, RedundancyTracker tracker, FreshLedger fresh) {
        if (helper==null) return 0;
        long now=System.currentTimeMillis();
        var fingerprint=new TaskFingerprint(TaskType.COFFERDAM,block.getWorld().getName(),
                block.getX(),block.getY(),block.getZ(),ClearJobTask.canonicalMaterialClass(block));
        var tier=tracker.check(helper,fingerprint,now);
        if (fresh.isFresh(block,now)) return 0;
        return switch (tier) { case FULL -> 4; case REDUCED -> 1; case ZERO -> 0; };
    }
}

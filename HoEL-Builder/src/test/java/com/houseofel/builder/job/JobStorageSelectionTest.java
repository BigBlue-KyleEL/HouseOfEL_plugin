package com.houseofel.builder.job;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class JobStorageSelectionTest {
    private final World world=(World)Proxy.newProxyInstance(World.class.getClassLoader(),new Class[]{World.class},
            (p,m,a)-> {
                if (m.getName().equals("equals")) return p==a[0];
                throw new AssertionError("Selection must not edit or scan the world: "+m.getName());
            });
    private JobStorage storage() {
        Plugin plugin=(Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class[]{Plugin.class},
                (p,m,a)-> {
                    if (m.getName().equals("getName") || m.getName().equals("namespace")) return "test";
                    throw new AssertionError(m.getName());
                });
        return new JobStorage(plugin,world,0,10,0,10,0,10);
    }
    private Block chest(World inWorld, Material type, int x) {
        return (Block)Proxy.newProxyInstance(Block.class.getClassLoader(),new Class[]{Block.class},(p,m,a)->switch(m.getName()) {
            case "getWorld" -> inWorld;
            case "getType" -> type;
            case "getX" -> x;
            case "getY", "getZ" -> 5;
            default -> throw new AssertionError("Selection must not modify the chest: "+m.getName());
        });
    }
    @Test void existingChestIsAdoptedWithoutCreatingOrEditingBlocks() {
        JobStorage storage=storage();
        assertFalse(storage.hasStorage());
        Block selected=chest(world,Material.CHEST,11);
        assertTrue(storage.canAdoptChest(selected));
        storage.adoptChest(selected);
        assertTrue(storage.hasStorage());
        assertSame(selected,storage.chests().getFirst());
        assertEquals(1,storage.chests().size());
    }
    @Test void chestMustBeOutsideWorkAreaAndInSameWorld() {
        JobStorage storage=storage();
        assertFalse(storage.canAdoptChest(chest(world,Material.CHEST,5)));
        assertFalse(storage.canAdoptChest(chest(world,Material.CHEST,10)));
        assertFalse(storage.canAdoptChest(chest(null,Material.CHEST,11)));
        assertFalse(storage.canAdoptChest(chest(world,Material.STONE,11)));
        assertTrue(storage.canAdoptChest(chest(world,Material.TRAPPED_CHEST,-1)));
        assertFalse(storage.hasStorage(),"Validation must not adopt rejected or unconfirmed storage");
    }
}

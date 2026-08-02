package com.houseofel.builder.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The chest(s) a job deposits into. Places a tagged chest just outside the work
 * region on first use, and adds another alongside whenever they fill up, so a job
 * is never blocked for want of storage.
 */
public final class JobStorage {

    /** Keep the chest clear of the work area rather than hard against its edge. */
    private static final int MIN_MARGIN = 4;
    private static final int MAX_MARGIN = 14;
    private static final int VERTICAL_SEARCH = 8;
    /** How far around a candidate spot to check for hazards. */
    private static final int HAZARD_RADIUS = 2;
    /** Bound on how many times one deposit will build more storage before giving up. */
    private static final int MAX_EXPANSIONS_PER_DEPOSIT = 4;

    private final NamespacedKey key;
    private final World world;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final List<Block> chests = new ArrayList<>();

    public JobStorage(Plugin plugin, World world,
                       int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.key = new NamespacedKey(plugin, "job_storage");
        this.world = world;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /** Where the NPC should walk to unload. Places the first chest if there isn't one yet. */
    public Location depositPoint() {
        if (chests.isEmpty() && !addChest()) {
            return null;
        }
        return chests.get(0).getLocation().add(0.5, 0.5, 0.5);
    }

    public boolean hasStorage() {
        return !chests.isEmpty();
    }

    /**
     * Stores everything it can, building more storage as needed.
     * Returns whatever genuinely couldn't be stored (only if we ran out of room to build).
     */
    public Map<Material, Integer> deposit(Map<Material, Integer> carried) {
        Map<Material, Integer> remaining = new HashMap<>(carried);

        for (int expansion = 0; expansion <= MAX_EXPANSIONS_PER_DEPOSIT; expansion++) {
            for (Block chestBlock : new ArrayList<>(chests)) {
                if (remaining.isEmpty()) {
                    return remaining;
                }
                if (chestBlock.getState() instanceof Chest chest) {
                    remaining = addAll(chest.getInventory(), remaining);
                }
            }
            if (remaining.isEmpty() || !addChest()) {
                break;
            }
        }
        return remaining;
    }

    private Map<Material, Integer> addAll(Inventory inventory, Map<Material, Integer> items) {
        Map<Material, Integer> leftover = new HashMap<>();
        for (Map.Entry<Material, Integer> entry : items.entrySet()) {
            Material material = entry.getKey();
            int count = entry.getValue();

            while (count > 0) {
                int batch = Math.min(count, material.getMaxStackSize());
                Map<Integer, ItemStack> rejected = inventory.addItem(new ItemStack(material, batch));
                if (rejected.isEmpty()) {
                    count -= batch;
                    continue;
                }
                int stillHeld = rejected.values().stream().mapToInt(ItemStack::getAmount).sum();
                leftover.merge(material, stillHeld + (count - batch), Integer::sum);
                break;
            }
        }
        return leftover;
    }

    /** Finds open ground just outside the region and stands a tagged double chest on it. */
    private boolean addChest() {
        Block spot = findChestSpot();
        if (spot == null) {
            return false;
        }

        // Halves of a double chest sit on the axis perpendicular to their facing, so a
        // north-facing pair joins east/west.
        Block partner = null;
        for (BlockFace side : new BlockFace[] {BlockFace.EAST, BlockFace.WEST}) {
            Block candidate = spot.getRelative(side);
            if (isFreeStandingSpot(candidate)
                    && candidate.getRelative(BlockFace.DOWN).getType().isSolid()) {
                partner = candidate;
                break;
            }
        }

        placeChest(spot, org.bukkit.block.data.type.Chest.Type.SINGLE);
        if (partner != null && !pairUp(spot, partner)) {
            // Couldn't join them — leave the second as its own chest rather than a
            // half-formed double, and track it so its slots still get used.
            chests.add(partner);
        }

        chests.add(spot);
        return true;
    }

    /**
     * Joins two adjacent chests into one double chest. Which half is LEFT and which is
     * RIGHT depends on facing in a way that's easy to get backwards, so rather than
     * assume, this sets one arrangement and checks whether the inventory actually came
     * back doubled — flipping if not.
     */
    private boolean pairUp(Block primary, Block partner) {
        placeChest(partner, org.bukkit.block.data.type.Chest.Type.SINGLE);

        setChestType(primary, org.bukkit.block.data.type.Chest.Type.LEFT);
        setChestType(partner, org.bukkit.block.data.type.Chest.Type.RIGHT);
        if (isDoubleChest(primary)) {
            return true;
        }

        setChestType(primary, org.bukkit.block.data.type.Chest.Type.RIGHT);
        setChestType(partner, org.bukkit.block.data.type.Chest.Type.LEFT);
        if (isDoubleChest(primary)) {
            return true;
        }

        setChestType(primary, org.bukkit.block.data.type.Chest.Type.SINGLE);
        setChestType(partner, org.bukkit.block.data.type.Chest.Type.SINGLE);
        return false;
    }

    private void setChestType(Block block, org.bukkit.block.data.type.Chest.Type type) {
        if (block.getBlockData() instanceof org.bukkit.block.data.type.Chest data) {
            data.setType(type);
            block.setBlockData(data);
        }
    }

    private boolean isDoubleChest(Block block) {
        return block.getState() instanceof Chest chest && chest.getInventory().getSize() >= 54;
    }

    private void placeChest(Block block, org.bukkit.block.data.type.Chest.Type type) {
        block.setType(Material.CHEST);
        if (block.getBlockData() instanceof org.bukkit.block.data.type.Chest data) {
            data.setFacing(BlockFace.NORTH);
            data.setType(type);
            block.setBlockData(data);
        }
        if (block.getState() instanceof Chest chest) {
            chest.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
            chest.update();
        }
    }

    /**
     * Walks a ring around the region, widening until it finds solid, hazard-free ground.
     * The ring covers all four sides starting at a corner, so on a long thin region the
     * chest ends up out of the way rather than hugging the middle of a long edge.
     */
    private Block findChestSpot() {
        for (int margin = MIN_MARGIN; margin <= MAX_MARGIN; margin++) {
            int westX = minX - margin;
            int eastX = maxX + margin;
            int northZ = minZ - margin;
            int southZ = maxZ + margin;

            for (int x = westX; x <= eastX; x++) {
                Block north = groundSpotAt(x, northZ);
                if (north != null) {
                    return north;
                }
                Block south = groundSpotAt(x, southZ);
                if (south != null) {
                    return south;
                }
            }
            for (int z = northZ; z <= southZ; z++) {
                Block west = groundSpotAt(westX, z);
                if (west != null) {
                    return west;
                }
                Block east = groundSpotAt(eastX, z);
                if (east != null) {
                    return east;
                }
            }
        }
        return null;
    }

    private Block groundSpotAt(int x, int z) {
        int startY = Math.min(maxY + VERTICAL_SEARCH, world.getMaxHeight() - 2);
        int endY = Math.max(minY - VERTICAL_SEARCH, world.getMinHeight() + 1);

        for (int y = startY; y >= endY; y--) {
            Block candidate = world.getBlockAt(x, y, z);
            if (isFreeStandingSpot(candidate)
                    && candidate.getRelative(BlockFace.DOWN).getType().isSolid()
                    && isHazardFree(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isFreeStandingSpot(Block block) {
        return block.getType().isAir()
                && block.getRelative(BlockFace.UP).getType().isAir()
                && !isNearRegion(block);
    }

    /** Nothing that would burn, melt or wash away the chest sitting close by. */
    private boolean isHazardFree(Block block) {
        for (int dx = -HAZARD_RADIUS; dx <= HAZARD_RADIUS; dx++) {
            for (int dy = -1; dy <= HAZARD_RADIUS; dy++) {
                for (int dz = -HAZARD_RADIUS; dz <= HAZARD_RADIUS; dz++) {
                    if (isHazard(block.getRelative(dx, dy, dz).getType())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isHazard(Material material) {
        return switch (material) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE,
                 WATER, POWDER_SNOW, CACTUS, TNT -> true;
            default -> false;
        };
    }

    /**
     * Treats the region plus its margin as off-limits, so the chest is never crowding the
     * job. Bounds are exclusive: a spot exactly MIN_MARGIN out is the closest allowed.
     */
    private boolean isNearRegion(Block block) {
        return block.getX() > minX - MIN_MARGIN && block.getX() < maxX + MIN_MARGIN
                && block.getZ() > minZ - MIN_MARGIN && block.getZ() < maxZ + MIN_MARGIN;
    }
}

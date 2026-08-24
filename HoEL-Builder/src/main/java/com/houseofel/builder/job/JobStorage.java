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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chest(s) a job deposits into. Places a tagged chest just outside the work
 * region on first use, and adds more whenever they fill up — packed into a flush
 * 2-row-by-3-layer cube of double chests before starting a fresh cube elsewhere —
 * so a job is never blocked for want of storage.
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
    /** Two rows of double chests, packed flush front-to-back, per layer. */
    private static final int ROWS_PER_LAYER = 2;
    /** Layers stack flush on top of each other before a fresh cube starts elsewhere. */
    private static final int LAYERS_PER_CUBE = 3;
    private static final int UNITS_PER_CUBE = ROWS_PER_LAYER * LAYERS_PER_CUBE;
    /** Chests face north, so "behind" is one block south. */
    private static final int ROW_STRIDE = 1;
    /** A double chest is two blocks wide; the next cube starts flush against that edge. */
    private static final int CUBE_WIDTH = 2;
    /** Two rows deep; a cube attaching front/back starts flush against that edge instead. */
    private static final int CUBE_DEPTH = ROWS_PER_LAYER * ROW_STRIDE;
    /** How far to widen the search, closest-Y-first, when a row's base spot isn't level with the anchor. */
    private static final int GRID_VERTICAL_SEARCH = 3;

    private final NamespacedKey key;
    private final World world;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final List<Block> chests = new ArrayList<>();
    /**
     * (x, z) columns this job has already built on. A finished cube's rooftop is
     * technically open, hazard-free ground — without this, the ring search for a fresh
     * cube keeps re-finding the same column and stacks the next cube on the last one's
     * roof instead of moving to genuinely new ground.
     */
    private final Set<Long> occupiedColumns = new HashSet<>();
    /** Row-0, layer-0 spot of the current cube; every unit in the cube is laid out off this. */
    private Block anchor;
    /**
     * Row-0, layer-0 spot of the most recently completed (or current) cube. Unlike
     * {@link #anchor}, this never resets to null, so the next cube can site itself
     * flush beside it instead of via an unrelated search around the whole job region.
     */
    private Block lastCubeAnchor;
    /** Most recently placed chest for each row (index 0/1) — the next layer stacks flush on top of it. */
    private final Block[] rowFoot = new Block[ROWS_PER_LAYER];
    /** Which unit (0..UNITS_PER_CUBE-1) comes next within the current cube. */
    private int cubeUnitIndex;
    /** Which way rows grow and cubes extend — away from the region, set once from the first cube's side. */
    private int rowSign;
    private int columnSign;

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

    // --- Persistence accessors -------------------------------------------------------
    // The layout state below is exactly what addChest()/findGridSpot()/findAdjacentCubeSpot()
    // need to keep building the same structure seamlessly after a restart. Snapshotting the
    // real fields (rather than replaying placement history) keeps this exactly in sync with
    // whatever that logic does, instead of a second copy that could drift out of step with it.

    List<Block> chests() {
        return chests;
    }

    Set<Long> occupiedColumns() {
        return occupiedColumns;
    }

    Block anchor() {
        return anchor;
    }

    Block lastCubeAnchor() {
        return lastCubeAnchor;
    }

    Block[] rowFoot() {
        return rowFoot;
    }

    int cubeUnitIndex() {
        return cubeUnitIndex;
    }

    int rowSign() {
        return rowSign;
    }

    int columnSign() {
        return columnSign;
    }

    static String encodeBlock(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    static Block decodeBlock(World world, String encoded) {
        String[] parts = encoded.split(",");
        return world.getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    static String encodeColumn(long key) {
        return (key >> 32) + "," + (int) key;
    }

    static long decodeColumn(String encoded) {
        String[] parts = encoded.split(",");
        return columnKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    /**
     * Rebuilds this instance's bookkeeping from a saved snapshot, without re-placing any
     * blocks — they already exist in the world from before the restart. Must be called
     * before any deposit/addChest activity, on a freshly constructed, empty instance.
     */
    void restore(List<Block> savedChests, Set<Long> savedOccupiedColumns, Block savedAnchor,
                 Block savedLastCubeAnchor, Block[] savedRowFoot, int savedCubeUnitIndex,
                 int savedRowSign, int savedColumnSign) {
        chests.addAll(savedChests);
        occupiedColumns.addAll(savedOccupiedColumns);
        anchor = savedAnchor;
        lastCubeAnchor = savedLastCubeAnchor;
        rowFoot[0] = savedRowFoot[0];
        rowFoot[1] = savedRowFoot[1];
        cubeUnitIndex = savedCubeUnitIndex;
        rowSign = savedRowSign;
        columnSign = savedColumnSign;
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

    /**
     * Pulls up to {@code amount} of {@code material} out of whatever chests exist — never
     * builds new storage the way {@link #deposit} does, since a withdrawal with nothing to
     * withdraw isn't a capacity problem. Returns how many were actually available and
     * removed, which may be less than requested (or zero) — callers decide what "not
     * enough material" means for them (Rule 11: a real, chest-sourced cost for anything
     * placed permanently, unlike a self-clearing Bulkhead plug). First symmetric
     * counterpart to {@link #deposit} this class has ever needed.
     */
    public int withdraw(Material material, int amount) {
        int remaining = amount;
        for (Block chestBlock : chests) {
            if (remaining <= 0) {
                break;
            }
            if (chestBlock.getState() instanceof Chest chest) {
                remaining -= removeUpTo(chest.getInventory(), material, remaining);
            }
        }
        return amount - remaining;
    }

    private int removeUpTo(Inventory inventory, Material material, int amount) {
        int removed = 0;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && removed < amount; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(stack.getAmount(), amount - removed);
            stack.setAmount(stack.getAmount() - take);
            contents[i] = stack.getAmount() <= 0 ? null : stack;
            removed += take;
        }
        inventory.setStorageContents(contents);
        return removed;
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

    /**
     * Stands a tagged double chest. The very first unit ever is sited by searching
     * outside the region; every unit after that packs flush against its neighbours —
     * two rows deep, stacked three layers high, forming one 2-wide-by-3-tall block of
     * storage. Once a cube fills, the next one starts flush beside it, not off in some
     * unrelated spot the region-wide search happens to prefer. Falls back to that wider
     * search only if beside-the-last-cube (or the very first search) turns up nothing.
     */
    private boolean addChest() {
        boolean startingCube = anchor == null;
        Block spot;
        if (!startingCube) {
            spot = findGridSpot();
        } else if (lastCubeAnchor != null) {
            spot = findAdjacentCubeSpot();
            if (spot == null) {
                spot = findChestSpot();
            }
        } else {
            spot = findChestSpot();
        }
        if (spot == null) {
            return false;
        }

        // Halves of a double chest sit on the axis perpendicular to their facing, so a
        // north-facing pair joins east/west.
        Block partner = null;
        for (BlockFace side : new BlockFace[] {BlockFace.EAST, BlockFace.WEST}) {
            Block candidate = spot.getRelative(side);
            if (!isFreeStandingSpot(candidate)) {
                continue;
            }
            if (candidate.getRelative(BlockFace.DOWN).getType().isSolid()
                    || candidate.getRelative(BlockFace.DOWN, 2).getType().isSolid()) {
                // Either flush ground, or the ground steps down one block right at the
                // partner — pair it at the primary's Y anyway (floating over the step)
                // rather than lose the double chest, since both halves must share a Y.
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
        occupiedColumns.add(columnKey(spot.getX(), spot.getZ()));
        if (partner != null) {
            occupiedColumns.add(columnKey(partner.getX(), partner.getZ()));
        }
        if (startingCube) {
            boolean firstCubeEver = lastCubeAnchor == null;
            anchor = spot;
            lastCubeAnchor = spot;
            cubeUnitIndex = 0;
            Arrays.fill(rowFoot, null);
            if (firstCubeEver) {
                // Growth direction is set once, from the very first cube, and reused by
                // every cube after — so the whole site tiles in one consistent direction
                // instead of each cube re-deriving its own (possibly conflicting) side.
                double centreX = (minX + maxX) / 2.0;
                double centreZ = (minZ + maxZ) / 2.0;
                columnSign = anchor.getX() >= centreX ? 1 : -1;
                rowSign = anchor.getZ() >= centreZ ? 1 : -1;
            }
        }
        rowFoot[cubeUnitIndex % ROWS_PER_LAYER] = spot;
        cubeUnitIndex++;
        if (cubeUnitIndex >= UNITS_PER_CUBE) {
            // Cube's full — the next unit starts a fresh one flush beside this one.
            anchor = null;
        }
        return true;
    }

    /**
     * Site for the next cube, flush against one edge of the one that just filled up —
     * so storage grows as one tiled structure instead of scattering across the ring
     * search's next "nearest open ground," which could be nowhere near the last cube.
     * Tries all four sides (both column directions, then both row directions) before
     * giving up, so one obstructed side — a dig site, a tree, uneven ground — doesn't
     * bounce the next cube off to some unrelated spot; only genuinely surrounding it
     * falls back to the wider search.
     */
    private Block findAdjacentCubeSpot() {
        int[][] offsets = {
            {CUBE_WIDTH * columnSign, 0},
            {-CUBE_WIDTH * columnSign, 0},
            {0, CUBE_DEPTH * rowSign},
            {0, -CUBE_DEPTH * rowSign},
        };
        for (int[] offset : offsets) {
            Block candidate = findNearGround(
                    lastCubeAnchor.getX() + offset[0],
                    lastCubeAnchor.getY(),
                    lastCubeAnchor.getZ() + offset[1]);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Next slot in the current cube. The first appearance of a row searches near the
     * anchor, allowed to step the ground up or down a block if that's genuinely where it
     * sits; every layer after that stacks flush on top of the chest placed for that row
     * one level below, so the stack rises with zero gap.
     */
    private Block findGridSpot() {
        int row = cubeUnitIndex % ROWS_PER_LAYER;
        Block below = rowFoot[row];
        if (below != null) {
            Block above = below.getRelative(BlockFace.UP);
            return isFreeStandingSpot(above) && isHazardFree(above) ? above : null;
        }

        int x = anchor.getX();
        int z = anchor.getZ() + row * ROW_STRIDE * rowSign;
        return findNearGround(x, anchor.getY(), z);
    }

    /**
     * Searches outward from the expected height, closest first, so a row sits level
     * with the anchor wherever it can and only steps down (or up) a block when the
     * terrain genuinely does.
     */
    private Block findNearGround(int x, int expectedY, int z) {
        for (int dy = 0; dy <= GRID_VERTICAL_SEARCH; dy++) {
            for (int sign : dy == 0 ? new int[] {0} : new int[] {-1, 1}) {
                Block candidate = world.getBlockAt(x, expectedY + dy * sign, z);
                if (isFreeStandingSpot(candidate)
                        && candidate.getRelative(BlockFace.DOWN).getType().isSolid()
                        && isHazardFree(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
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
        if (occupiedColumns.contains(columnKey(x, z))) {
            return null;
        }
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

    /**
     * A spot counts as clear if it's air, or anything vanilla itself treats as
     * replaceable — grass, ferns, flowers, vines, snow layers. Placing a chest destroys
     * these the same way a player walking through or building over them would; nothing
     * a player would consider an actual obstruction gets touched. Water/lava/fire are
     * also replaceable but get excluded separately by the hazard check.
     */
    private boolean isFreeStandingSpot(Block block) {
        return isOpen(block) && isOpen(block.getRelative(BlockFace.UP)) && !isNearRegion(block);
    }

    private boolean isOpen(Block block) {
        return block.getType().isAir() || block.isReplaceable();
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

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
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

package com.houseofel.builder.job;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Biome;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.util.BiomeSearchResult;

/** Shell coordinates are inclusive; the selected usable interior lies strictly inside. */
final class CofferdamGeometry {
    private CofferdamGeometry() { }

    static String facing(double x, double z, int minX, int maxX, int minZ, int maxZ) {
        // Distance to each finite wall segment; stable N,E,S,W tie order.
        double cx = Math.max(minX, Math.min(maxX, x));
        double cz = Math.max(minZ, Math.min(maxZ, z));
        double[] distances = { sq(x-cx)+sq(z-minZ), sq(x-maxX)+sq(z-cz),
                sq(x-cx)+sq(z-maxZ), sq(x-minX)+sq(z-cz) };
        String[] names = { "NORTH", "EAST", "SOUTH", "WEST" };
        int best = 0;
        for (int i=1;i<4;i++) if (distances[i]<distances[best]) best=i;
        return names[best];
    }
    private static double sq(double value) { return value*value; }

    static int[] door(JobState s) {
        if (s.cofferdamDoorX!=null && s.cofferdamDoorY!=null && s.cofferdamDoorZ!=null)
            return new int[]{s.cofferdamDoorX,s.cofferdamDoorY,s.cofferdamDoorZ};
        int x = s.minX + (s.maxX-s.minX)/2, z = s.minZ + (s.maxZ-s.minZ)/2;
        switch (s.cofferdamFacing) {
            case "NORTH" -> z=s.minZ;
            case "SOUTH" -> z=s.maxZ;
            case "EAST" -> x=s.maxX;
            case "WEST" -> x=s.minX;
            default -> throw new IllegalArgumentException("Invalid entrance facing");
        }
        return new int[]{x,s.minY+1,z};
    }
    /** Pick an exterior-ground-supported threshold on the confirmed wall, center first.
     * The chosen coordinates are immutable for this job/watch; terrain edits after
     * confirmation must not move the doorway or its repair exclusions.
     */
    static int[] chooseEntrance(World world, JobState s) {
        BlockFace face=BlockFace.valueOf(s.cofferdamFacing);
        boolean alongX=face==BlockFace.NORTH || face==BlockFace.SOUTH;
        int low=(alongX?s.minX:s.minZ)+1, high=(alongX?s.maxX:s.maxZ)-1;
        int center=low+(high-low)/2;
        int highestDoorY=Math.min(s.maxY-(s.cofferdamHasCeiling?2:1),world.getMaxHeight()-4);
        for (int offset=0;offset<=high-low;offset++) {
            for (int sign:new int[]{-1,1}) {
                if (offset==0 && sign==1) continue;
                int along=center+offset*sign;
                if (along<low || along>high) continue;
                int x=alongX?along:(face==BlockFace.EAST?s.maxX:s.minX);
                int z=alongX?(face==BlockFace.SOUTH?s.maxZ:s.minZ):along;
                int outsideX=x+face.getModX(), outsideZ=z+face.getModZ();
                for (int y=highestDoorY;y>s.minY;y--) {
                    Block ground=world.getBlockAt(outsideX,y-1,outsideZ);
                    if (!ground.getBlockData().isFaceSturdy(BlockFace.UP, org.bukkit.block.BlockSupport.FULL) || org.bukkit.Tag.LEAVES.isTagged(ground.getType())) continue;
                    if (ground.getType()==Material.MAGMA_BLOCK || ground.getType()==Material.CACTUS
                            || ground.getType()==Material.CAMPFIRE || ground.getType()==Material.SOUL_CAMPFIRE) continue;
                    if (clearApproach(world.getBlockAt(outsideX,y,outsideZ))
                            && clearApproach(world.getBlockAt(outsideX,y+1,outsideZ))
                            && clearApproach(world.getBlockAt(x-face.getModX(),y,z-face.getModZ()))
                            && clearApproach(world.getBlockAt(x-face.getModX(),y+1,z-face.getModZ()))) {
                        return new int[]{x,y,z};
                    }
                }
            }
        }
        // A submerged box can be above the seabed: swimming access needs no exterior
        // footing. This fallback never accepts solid ground hiding a floor-level door.
        int[] floorDoor=door(s);
        Block outside=world.getBlockAt(floorDoor[0]+face.getModX(),floorDoor[1],floorDoor[2]+face.getModZ());
        if (floorDoor[1]<=highestDoorY && CofferdamWork.wet(outside) && clearApproach(outside)
                && clearApproach(world.getBlockAt(outside.getX(),floorDoor[1]+1,outside.getZ()))
                && clearApproach(world.getBlockAt(floorDoor[0]-face.getModX(),floorDoor[1],floorDoor[2]-face.getModZ()))
                && clearApproach(world.getBlockAt(floorDoor[0]-face.getModX(),floorDoor[1]+1,floorDoor[2]-face.getModZ()))) return floorDoor;
        return null;
    }
    private static boolean clearApproach(Block block) {
        // Water is valid for a submerged entrance; solid terrain is not a passage.
        return block.isPassable() && block.getType()!=Material.LAVA
                && block.getType()!=Material.FIRE && block.getType()!=Material.SOUL_FIRE;
    }

    static boolean hasEntrance(JobState s) {
        return s.cofferdamFacing != null && s.maxY-s.minY>=3 && s.maxX-s.minX>=2 && s.maxZ-s.minZ>=2;
    }
    static boolean isDoor(JobState s, int x, int y, int z) {
        if (!hasEntrance(s)) return false;
        int[] p=door(s);
        return x==p[0] && z==p[2] && (y==p[1] || y==p[1]+1);
    }
    static boolean interior(JobState s, int x,int y,int z) {
        return x>s.minX && x<s.maxX && y>s.minY && y<s.maxY && z>s.minZ && z<s.maxZ;
    }
    /** Retain a lid if water can reach the opening at confirmation. Use actual local
     * water, including waterlogged blocks, rather than a fixed sea-level constant.
     */
    static boolean requiresCeiling(World world, int minX, int maxX, int maxY, int minZ, int maxZ) {
        // Top interior layer, proposed lid and one cell above, with an exterior collar.
        for (int x=minX-1;x<=maxX+1;x++) for (int z=minZ-1;z<=maxZ+1;z++) {
            for (int y=maxY-1;y<=Math.min(maxY+1,world.getMaxHeight()-1);y++) {
                if (CofferdamWork.wet(world.getBlockAt(x,y,z))) return true;
            }
        }
        return false;
    }

    /** Three-wide lintel in the wall plane, with a connected marker arm above it. */
    static java.util.List<int[]> entranceFrame(JobState s) {
        if (!s.cofferdamFramedEntrance || !hasEntrance(s)) return java.util.List.of();
        int[] p=door(s);
        BlockFace face=BlockFace.valueOf(s.cofferdamFacing);
        java.util.List<int[]> frame=new java.util.ArrayList<>();
        for (int offset=-1;offset<=1;offset++)
            frame.add(new int[]{p[0]+offset*face.getModZ(),p[1]+2,p[2]+offset*face.getModX()});
        return frame;
    }

    static int repairMaxY(JobState s) {
        return s.cofferdamFramedEntrance && hasEntrance(s)?Math.max(s.maxY,door(s)[1]+2):s.maxY;
    }

    static boolean isShell(JobState s, int x,int y,int z) {
        for (int[] p:entranceFrame(s)) if (x==p[0] && y==p[1] && z==p[2]) return true;
        if (x<s.minX || x>s.maxX || y<s.minY || y>s.maxY || z<s.minZ || z>s.maxZ) return false;
        if (y==s.minY || x==s.minX || x==s.maxX || z==s.minZ || z==s.maxZ) return true;
        return y==s.maxY && s.cofferdamHasCeiling;
    }

    static boolean loaded(World w, int x,int z) { return w.isChunkLoaded(x>>4,z>>4); }

    static void entrance(World world, JobState s) {
        if (!hasEntrance(s)) return;
        int[] p=door(s);
        BlockFace face=BlockFace.valueOf(s.cofferdamFacing);
        int mx=p[0]+face.getModX(), mz=p[2]+face.getModZ();
        if (!loaded(world,p[0],p[2]) || !loaded(world,mx,mz)) return;
        Material material=Material.valueOf(s.cofferdamDoor);
        for (int dy=0;dy<2;dy++) {
            Block b=world.getBlockAt(p[0],p[1]+dy,p[2]);
            // Opening a working door is not damage and must not be reset by the watch.
            if (b.getType()==material && b.getBlockData() instanceof Door existing
                    && existing.getHalf()==(dy==0?Bisected.Half.BOTTOM:Bisected.Half.TOP)
                    && existing.getFacing()==face) continue;
            Door data=(Door)material.createBlockData();
            data.setFacing(face);
            data.setHalf(dy==0?Bisected.Half.BOTTOM:Bisected.Half.TOP);
            b.setBlockData(data,false);
        }
        // Above a low wall, join the projecting marker to a post on the lintel.
        // Taller walls already provide its backing.
        if (s.cofferdamFramedEntrance && p[1]+3>s.maxY) {
            Block backing=world.getBlockAt(p[0],p[1]+3,p[2]);
            if (backing.getType()!=Material.COBBLESTONE_WALL) backing.setType(Material.COBBLESTONE_WALL,false);
        }
        Block support=world.getBlockAt(mx,p[1]+3,mz);
        if (support.getType()!=Material.COBBLESTONE_WALL) support.setType(Material.COBBLESTONE_WALL,false);
        Block marker=world.getBlockAt(mx,p[1]+2,mz);
        if (!(marker.getBlockData() instanceof Lantern lantern) || !lantern.isHanging()) {
            Lantern data=(Lantern)Material.LANTERN.createBlockData();
            data.setHanging(true);
            data.setWaterlogged(marker.getType()==Material.WATER);
            marker.setBlockData(data,false);
        }
    }

    static Material nativeDoor(String biome) {
        if (biome.contains("pale_garden")) return Material.PALE_OAK_DOOR;
        if (biome.contains("cherry")) return Material.CHERRY_DOOR;
        if (biome.contains("mangrove")) return Material.MANGROVE_DOOR;
        if (biome.contains("bamboo")) return Material.BAMBOO_DOOR;
        if (biome.contains("jungle")) return Material.JUNGLE_DOOR;
        if (biome.contains("birch")) return Material.BIRCH_DOOR;
        if (biome.contains("dark_forest")) return Material.DARK_OAK_DOOR;
        if (biome.contains("taiga") || biome.equals("grove")) return Material.SPRUCE_DOOR;
        if (biome.contains("savanna")) return Material.ACACIA_DOOR;
        if (biome.equals("forest") || biome.equals("flower_forest") || biome.equals("swamp")
                || biome.equals("plains") || biome.equals("sunflower_plains")
                || biome.equals("wooded_badlands") || biome.equals("windswept_forest")
                || biome.equals("windswept_hills")) return Material.OAK_DOOR;
        return null;
    }

    static Material chooseDoor(World world, Location origin) {
        Material local=nativeDoor(world.getBiome(origin).getKey().getKey());
        if (local!=null) return local;
        Biome[] wooded={Biome.FOREST,Biome.BIRCH_FOREST,Biome.OLD_GROWTH_BIRCH_FOREST,
                Biome.DARK_FOREST,Biome.PALE_GARDEN,Biome.TAIGA,Biome.SNOWY_TAIGA,
                Biome.OLD_GROWTH_PINE_TAIGA,Biome.OLD_GROWTH_SPRUCE_TAIGA,
                Biome.JUNGLE,Biome.SPARSE_JUNGLE,Biome.BAMBOO_JUNGLE,Biome.SAVANNA,
                Biome.SAVANNA_PLATEAU,Biome.WINDSWEPT_SAVANNA,Biome.SWAMP,
                Biome.MANGROVE_SWAMP,Biome.CHERRY_GROVE,Biome.GROVE,Biome.FLOWER_FOREST,
                Biome.WOODED_BADLANDS,Biome.WINDSWEPT_FOREST};
        BiomeSearchResult result=world.locateNearestBiome(origin,8192,32,64,wooded);
        // Refuse explicitly instead of silently substituting oak in a treeless world.
        return result==null?null:nativeDoor(result.getBiome().getKey().getKey());
    }
}

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

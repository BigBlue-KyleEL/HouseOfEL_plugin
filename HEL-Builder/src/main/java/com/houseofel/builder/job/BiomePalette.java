package com.houseofel.builder.job;

import org.bukkit.Material;

/**
 * Depth-relative block palette for each {@link LandscapeBiome}. Replaces the old
 * surface/subsurface switches with proper layered terrain: badlands gets terracotta
 * bands keyed to absolute Y, desert gets sand-to-sandstone gradients, etc.
 */
final class BiomePalette {

    private BiomePalette() {}

    static Material materialAt(LandscapeBiome biome, int x, int y, int z, int surfaceY, long noiseSeed) {
        int depth = surfaceY - y;
        if (biome == null) return plainsLayer(x, y, z, depth, noiseSeed);
        return switch (biome) {
            case PLAINS -> plainsLayer(x, y, z, depth, noiseSeed);
            case DESERT -> desertLayer(x, y, z, depth, noiseSeed);
            case BADLANDS -> badlandsLayer(x, y, z, depth, noiseSeed);
            case SNOWY -> snowyLayer(x, y, z, depth, noiseSeed);
            case TAIGA -> taigaLayer(x, y, z, depth, noiseSeed);
            case JUNGLE -> jungleLayer(x, y, z, depth, noiseSeed);
            case MUSHROOM -> mushroomLayer(x, y, z, depth, noiseSeed);
        };
    }

    private static Material plainsLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth == 0) return Material.GRASS_BLOCK;
        if (depth <= 3) {
            if (variation(x, y, z, noiseSeed, 0.06)) return Material.COARSE_DIRT;
            return Material.DIRT;
        }
        return stoneLayer(x, y, z, noiseSeed);
    }

    private static Material desertLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth <= 3) return Material.SAND;
        if (depth <= 6) {
            if (variation(x, y, z, noiseSeed, 0.10)) return Material.CUT_SANDSTONE;
            return Material.SANDSTONE;
        }
        return stoneLayer(x, y, z, noiseSeed);
    }

    private static Material badlandsLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth <= 2) return Material.RED_SAND;
        return terracottaBand(y);
    }

    private static Material snowyLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth == 0) return Material.SNOW_BLOCK;
        if (depth <= 3) {
            if (variation(x, y, z, noiseSeed, 0.06)) return Material.COARSE_DIRT;
            return Material.DIRT;
        }
        return stoneLayer(x, y, z, noiseSeed);
    }

    private static Material taigaLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth == 0) return Material.PODZOL;
        if (depth <= 3) {
            if (variation(x, y, z, noiseSeed, 0.06)) return Material.COARSE_DIRT;
            return Material.DIRT;
        }
        return stoneLayer(x, y, z, noiseSeed);
    }

    private static Material jungleLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth == 0) return Material.GRASS_BLOCK;
        if (depth <= 3) {
            if (variation(x, y, z, noiseSeed, 0.08)) return Material.COARSE_DIRT;
            return Material.DIRT;
        }
        return stoneLayer(x, y, z, noiseSeed);
    }

    private static Material mushroomLayer(int x, int y, int z, int depth, long noiseSeed) {
        if (depth == 0) return Material.MYCELIUM;
        if (depth <= 3) {
            if (variation(x, y, z, noiseSeed, 0.06)) return Material.COARSE_DIRT;
            return Material.DIRT;
        }
        return stoneLayer(x, y, z, noiseSeed);
    }

    private static Material stoneLayer(int x, int y, int z, long noiseSeed) {
        if (variation(x, y, z, noiseSeed, 0.08)) return Material.GRAVEL;
        if (variation(x + 1000, y, z, noiseSeed, 0.04)) return Material.CLAY;
        return Material.STONE;
    }

    // Badlands terracotta bands — absolute Y determines the color, matching vanilla's
    // horizontal striping aesthetic.
    private static Material terracottaBand(int y) {
        int band = Math.floorMod(y, 8);
        return switch (band) {
            case 0 -> Material.ORANGE_TERRACOTTA;
            case 1 -> Material.TERRACOTTA;
            case 2 -> Material.YELLOW_TERRACOTTA;
            case 3 -> Material.BROWN_TERRACOTTA;
            case 4 -> Material.ORANGE_TERRACOTTA;
            case 5 -> Material.WHITE_TERRACOTTA;
            case 6 -> Material.RED_TERRACOTTA;
            case 7 -> Material.LIGHT_GRAY_TERRACOTTA;
            default -> Material.TERRACOTTA;
        };
    }

    private static boolean variation(int x, int y, int z, long noiseSeed, double chance) {
        long h = x * 374761393L + y * 668265263L + z * 1274126177L + noiseSeed;
        h = (h ^ (h >> 13)) * 1103515245L;
        return ((h & 0x7fffffffL) / (double) 0x7fffffffL) < chance;
    }
}

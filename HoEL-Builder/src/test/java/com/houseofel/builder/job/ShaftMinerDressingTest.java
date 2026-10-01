package com.houseofel.builder.job;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ShaftMinerDressingTest {
    @Test void ladderFacesIntoTheShaftFromTheNorthWall() {
        assertEquals(BlockFace.NORTH, ShaftMinerJobTask.LADDER_FACING.getOppositeFace(),
                "the ladder's support must be the north wall behind the NW corner");
    }

    @Test void shaftDepthSurvivesSaveAndResume() {
        JobState s = new JobState();
        s.jobType = JobType.SHAFT_MINER;
        s.npcId = 8; s.playerId = java.util.UUID.randomUUID(); s.worldName = "world";
        s.minX = 91; s.maxX = 95; s.minZ = -69; s.maxZ = -65;
        s.topY = 69; s.requestedDepth = 30; s.processedCells = 390;
        JobState back = JobStateStore.decode(JobStateStore.encode(s));
        assertEquals(69, back.topY);
        assertEquals(30, back.requestedDepth);
        assertEquals(390, back.processedCells);
    }

    @Test void persistentWaterEscalatesSpongesThenWallsThenAsksOwner() {
        for (int streak = 1; streak <= 3; streak++)
            assertEquals(ShaftMinerJobTask.WaterResponse.SPONGE_ONLY, ShaftMinerJobTask.waterResponseFor(streak));
        assertEquals(ShaftMinerJobTask.WaterResponse.WALL_ENTRIES, ShaftMinerJobTask.waterResponseFor(4));
        assertEquals(ShaftMinerJobTask.WaterResponse.RAISE_WALLS, ShaftMinerJobTask.waterResponseFor(5));
        assertEquals(ShaftMinerJobTask.WaterResponse.ASK_OWNER, ShaftMinerJobTask.waterResponseFor(6));
    }

    @Test void ladderTorchEveryFourLayersIncludingLandings() {
        assertFalse(ShaftMinerJobTask.isLadderLightLayer(0), "surface layer");
        for (int depth = 1; depth <= 24; depth++)
            assertEquals(depth % 4 == 0, ShaftMinerJobTask.isLadderLightLayer(depth), "depth " + depth);
    }

    @Test void twoByTwoKeepsTheOriginalSouthAndEastTorches() {
        var spots = ShaftMinerJobTask.landingTorchSpots(0, 1, 0, 1);
        assertEquals(List.of(new ShaftMinerJobTask.TorchSpot(0, 1, BlockFace.NORTH),
                new ShaftMinerJobTask.TorchSpot(1, 0, BlockFace.WEST)), spots);
    }

    @Test void narrowShaftsKeepTwoTorches() {
        assertEquals(2, ShaftMinerJobTask.landingTorchSpots(0, 1, 0, 4).size());
        assertEquals(2, ShaftMinerJobTask.landingTorchSpots(0, 4, 0, 1).size());
    }

    @Test void threeByThreeAndLargerLightEveryWallWithoutTouchingTheLadder() {
        for (int size : new int[]{3, 4, 6}) {
            int max = size - 1;
            var spots = ShaftMinerJobTask.landingTorchSpots(0, max, 0, max);
            assertEquals(4, spots.size());
            Set<BlockFace> walls = new HashSet<>();
            Set<String> cells = new HashSet<>();
            for (var spot : spots) {
                walls.add(spot.facing().getOppositeFace());
                assertTrue(cells.add(spot.x() + "," + spot.z()), "one torch per cell");
                assertFalse(spot.x() == 0 && spot.z() == 0, "ladder cell stays clear");
                // Each torch sits against the wall it is attached to.
                switch (spot.facing()) {
                    case SOUTH -> assertEquals(0, spot.z());
                    case NORTH -> assertEquals(max, spot.z());
                    case EAST -> assertEquals(0, spot.x());
                    case WEST -> assertEquals(max, spot.x());
                    default -> fail();
                }
            }
            assertEquals(Set.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST), walls);
        }
    }
}

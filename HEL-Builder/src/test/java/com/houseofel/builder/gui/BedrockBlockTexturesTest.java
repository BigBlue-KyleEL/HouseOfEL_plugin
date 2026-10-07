package com.houseofel.builder.gui;

import org.bukkit.Material;
import org.geysermc.cumulus.util.FormImage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockBlockTexturesTest {
    @Test
    void allSixCommonPicksUseVerifiedBedrockPaths() {
        var materials = List.of(Material.DIRT, Material.STONE, Material.GRAVEL,
                Material.SAND, Material.DEEPSLATE, Material.GRASS_BLOCK);
        assertEquals(List.of("textures/blocks/dirt", "textures/blocks/stone", "textures/blocks/gravel",
                "textures/blocks/sand", "textures/blocks/deepslate/deepslate", "textures/blocks/grass_side_carried"),
                materials.stream().map(BedrockBlockTextures::pathFor).toList());
    }

    @Test
    void namingAndShapeVariantsUseTheCorrectSharedFace() {
        assertEquals("textures/blocks/stonebrick", BedrockBlockTextures.pathFor(Material.STONE_BRICK_STAIRS));
        assertEquals("textures/blocks/concrete_silver", BedrockBlockTextures.pathFor(Material.LIGHT_GRAY_CONCRETE));
        assertEquals("textures/blocks/planks_oak", BedrockBlockTextures.pathFor(Material.OAK_PLANKS));
        assertEquals("textures/blocks/log_big_oak", BedrockBlockTextures.pathFor(Material.DARK_OAK_LOG));
        assertEquals(BedrockBlockTextures.pathFor(Material.CUT_COPPER),
                BedrockBlockTextures.pathFor(Material.WAXED_CUT_COPPER_SLAB));
        assertNull(BedrockBlockTextures.pathFor(Material.AIR));
        assertNull(BedrockBlockTextures.pathFor(Material.BELL));
    }

    @Test
    void mappedAndUnmappedButtonsPreserveLabelsOrderAndNavigation() {
        var form = BedrockJobForm.clearingResultsForm("Picker", "", List.of(Material.STONE, Material.BELL)).build();
        assertEquals(List.of("Search", "Stone", "Bell", "Back", "Cancel"),
                form.buttons().stream().map(b -> b.text()).toList());
        assertEquals(FormImage.Type.PATH, form.buttons().get(1).image().type());
        assertEquals("textures/blocks/stone", form.buttons().get(1).image().data());
        for (int index : List.of(0, 2, 3, 4)) assertNull(form.buttons().get(index).image());
        var empty = BedrockJobForm.clearingResultsForm("Picker", "no_match", List.of()).build();
        assertEquals(List.of("Search", "Back", "Cancel"), empty.buttons().stream().map(b -> b.text()).toList());
        assertTrue(empty.content().contains("No matching blocks"));
    }

    @Test
    void addingIconsDoesNotTruncateLargeResultLists() {
        var results = java.util.Collections.nCopies(498, Material.STONE);
        var form = BedrockJobForm.clearingResultsForm("Picker", "stone", results).build();
        assertEquals(501, form.buttons().size());
        assertEquals("Stone", form.buttons().get(498).text());
        assertEquals("Back", form.buttons().get(499).text());
        assertEquals("Cancel", form.buttons().get(500).text());
    }
}

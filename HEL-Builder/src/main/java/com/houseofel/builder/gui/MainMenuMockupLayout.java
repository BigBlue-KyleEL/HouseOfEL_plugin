package com.houseofel.builder.gui;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import net.citizensnpcs.api.npc.NPC;

import java.util.List;

/** Visual mockup: only the NPC name is live; stats and actions are placeholders. */
public final class MainMenuMockupLayout {

    private MainMenuMockupLayout() {}

    public static OpenScreenPayload create(NPC npc) {
        String title = BuilderNpcService.baseNameOf(npc) + " — Groundworker";

        List<GuiElement> children = List.of(
                new GuiElement.Image("medallion", Anchor.TOP_CENTER, new int[]{0, -15}, true,
                        new int[]{120, 48}, "houseofel:gui/medallion"),
                new GuiElement.Image("class_logo", Anchor.TOP_LEFT, new int[]{22, 24}, true,
                        new int[]{90, 90}, "houseofel:gui/class_groundworker_t1"),
                new GuiElement.Label("npc_name", Anchor.TOP_LEFT, new int[]{114, 37}, true,
                        title, "#FFFFFF", true, "left"),
                new GuiElement.Image("tag_bar", Anchor.TOP_LEFT, new int[]{114, 49}, true,
                        new int[]{280, 18}, "houseofel:gui/tag_bg"),
                new GuiElement.Label("tag_1", Anchor.TOP_LEFT, new int[]{129, 54}, true,
                        "[QUARRY]", "#FFD700", true, "left"),
                new GuiElement.Label("tag_2", Anchor.TOP_LEFT, new int[]{183, 54}, true,
                        "[RUSTED]", "#FF8C00", true, "left"),
                new GuiElement.Label("tag_3", Anchor.TOP_LEFT, new int[]{237, 54}, true,
                        "[QUARRYMAN]", "#FFD700", true, "left"),
                new GuiElement.Label("tag_4", Anchor.TOP_LEFT, new int[]{309, 54}, true,
                        "[SHAFT MINER]", "#FFD700", true, "left"),
                new GuiElement.Label("flavor", Anchor.TOP_LEFT, new int[]{114, 70}, true,
                        "\"Earthshaper — Ground Remembers", "#AAAAAA", true, "left"),
                new GuiElement.Label("flavor_line2", Anchor.TOP_LEFT, new int[]{114, 81}, true,
                        "the Name.\"", "#AAAAAA", true, "left"),
                new GuiElement.Label("hearts", Anchor.TOP_LEFT, new int[]{114, 95}, true,
                        "❤❤❤❤❤❤❤❤❤❤ 20/20 HP", "#FF4444", true, "left"),
                new GuiElement.Label("level_info", Anchor.TOP_LEFT, new int[]{114, 108}, true,
                        "Level 20 (max) — 1989 Toil banked.", "#55FF55", true, "left"),
                new GuiElement.Label("rust_info", Anchor.TOP_LEFT, new int[]{114, 121}, true,
                        "Rusted — 196/200 Toil to clear.", "#FF6644", true, "left"),
                new GuiElement.Image("divider_work", Anchor.CENTER, new int[]{0, -14}, true,
                        new int[]{380, 12}, "houseofel:gui/divider_work_wide"),
                new GuiElement.Button("btn_mining", Anchor.CENTER, new int[]{-65, 8}, true,
                        new int[]{120, 20}, "mining", "Mining",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true),
                new GuiElement.Button("btn_lumber", Anchor.CENTER, new int[]{65, 8}, true,
                        new int[]{120, 20}, "lumberjacking", "Lumberjacking",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true),
                new GuiElement.Button("btn_farming", Anchor.CENTER, new int[]{-65, 31}, true,
                        new int[]{120, 20}, "farming", "Farming",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true),
                new GuiElement.Button("btn_clearing", Anchor.CENTER, new int[]{65, 31}, true,
                        new int[]{120, 20}, "clearing", "Clearing",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true),
                new GuiElement.Image("divider_spec", Anchor.CENTER, new int[]{0, 54}, true,
                        new int[]{380, 12}, "houseofel:gui/divider_spec_wide"),
                new GuiElement.Button("btn_spec1", Anchor.BOTTOM_CENTER, new int[]{-65, -64}, true,
                        new int[]{120, 20}, "spec_quarryman", "LvL.8: Quarryman",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true),
                new GuiElement.Button("btn_spec2", Anchor.BOTTOM_CENTER, new int[]{65, -64}, true,
                        new int[]{120, 20}, "spec_shaft_miner", "LvL.16: Shaft Miner",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true),
                new GuiElement.Button("btn_spec3", Anchor.BOTTOM_CENTER, new int[]{0, -34}, true,
                        new int[]{120, 20}, "spec_level20_placeholder", "Lvl. 20 Placeholder",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true)
        );

        // Extra height keeps the center-anchored work section below the stats.
        GuiElement.Panel bg = new GuiElement.Panel("bg", Anchor.CENTER, new int[]{0, 0}, true,
                new int[]{426, 310}, "houseofel:gui/panel_main", null, children);

        return new OpenScreenPayload("main_menu_mockup", new int[]{426, 310}, null, List.of(bg));
    }
}

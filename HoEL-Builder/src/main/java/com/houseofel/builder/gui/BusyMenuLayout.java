package com.houseofel.builder.gui;

import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import net.citizensnpcs.api.npc.NPC;

import java.util.List;

public final class BusyMenuLayout {

    private BusyMenuLayout() {}

    public static OpenScreenPayload create(NPC npc, String eta) {
        String title = npc.getName() + " is busy";

        List<GuiElement> children = List.of(
                new GuiElement.Image("medallion", Anchor.TOP_CENTER, new int[]{0, -15}, true,
                        new int[]{120, 48}, "houseofel:gui/medallion"),
                new GuiElement.Label("npc_name", Anchor.TOP_CENTER, new int[]{0, 40}, true,
                        title, "#FFFFFF", true, "center"),
                new GuiElement.Label("msg1", Anchor.CENTER, new int[]{0, -10}, true,
                        "Can't help you right now, kiddo.", "#AAAAAA", true, "center"),
                new GuiElement.Label("msg2", Anchor.CENTER, new int[]{0, 8}, true,
                        "Check back in " + eta + ".", "#AAAAAA", true, "center"),
                new GuiElement.Button("btn_dismiss", Anchor.BOTTOM_CENTER, new int[]{0, -30}, true,
                        new int[]{120, 20}, "close", "Okay",
                        "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                        "houseofel:gui/button_disabled", true)
        );

        GuiElement.Panel bg = new GuiElement.Panel("bg", Anchor.CENTER, new int[]{0, 0}, true,
                new int[]{426, 250}, "houseofel:gui/panel_main", null, children);

        return new OpenScreenPayload("busy", new int[]{426, 250}, null, List.of(bg));
    }
}

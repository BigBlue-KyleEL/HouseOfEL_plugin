package com.houseofel.builder.gui;

import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;

import java.util.List;

public final class PanelTestLayout {

    private PanelTestLayout() {}

    public static OpenScreenPayload create() {
        List<GuiElement> children = List.of(
                new GuiElement.Image("bg_image", Anchor.TOP_LEFT, new int[]{0, 0}, true,
                        new int[]{426, 250}, "houseofel:gui/panel_main"),
                new GuiElement.Label("lbl_tl", Anchor.TOP_LEFT, new int[]{12, 10}, true,
                        "TOP LEFT", "#FFFFFF", true, "left"),
                new GuiElement.Label("lbl_tc", Anchor.TOP_CENTER, new int[]{0, 10}, true,
                        "TOP CENTER", "#FFFFFF", true, "center"),
                new GuiElement.Label("lbl_c", Anchor.CENTER, new int[]{0, 0}, true,
                        "CENTER", "#FFFFFF", true, "center"),
                new GuiElement.Label("lbl_br", Anchor.BOTTOM_RIGHT, new int[]{-12, -10}, true,
                        "BOTTOM RIGHT", "#FFFFFF", true, "right")
        );

        GuiElement.Panel mainPanel = new GuiElement.Panel("main_panel", Anchor.CENTER,
                new int[]{0, 0}, true, new int[]{426, 250}, "none", null, children);

        return new OpenScreenPayload("panel_test",
                new int[]{426, 250}, new int[]{426, 250}, List.of(mainPanel));
    }
}

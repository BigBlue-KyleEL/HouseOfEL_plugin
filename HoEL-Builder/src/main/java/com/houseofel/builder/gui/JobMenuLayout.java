package com.houseofel.builder.gui;

import com.houseofel.builder.job.LandscapeBiome;
import com.houseofel.builder.job.LandscapeMode;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import net.citizensnpcs.api.npc.NPC;

import java.util.ArrayList;
import java.util.List;

public final class JobMenuLayout {

    public static final String JOB_TYPE = "job_type";
    public static final String JOB_TARGET = "job_target";
    public static final String JOB_CONFIRM = "job_confirm";
    public static final String JOB_DEPTH = "job_depth";
    public static final String JOB_DEPTH_LEVEL = "job_depth_level";
    public static final String JOB_DEPTH_COORD = "job_depth_coord";
    public static final String LANDSCAPE_MODE = "landscape_mode";
    public static final String LANDSCAPE_BIOME = "landscape_biome";
    public static final String JOB_STATUS = "job_status";

    private static final int PANEL_W = 426;
    private static final int PANEL_H = 250;
    private static final int TALL_PANEL_H = 300;
    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;

    private JobMenuLayout() {}

    public static OpenScreenPayload taskTypeScreen(String title, List<String> statusLines,
                                                    List<ButtonOption> buttons) {
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, title, "#FFFFFF"));
        y += 20;
        for (int i = 0; i < statusLines.size(); i++) {
            children.add(label("status_" + i, y, statusLines.get(i), "#AAAAAA"));
            y += 12;
        }
        y += 8;
        for (ButtonOption btn : buttons) {
            children.add(button(btn.id(), y, btn.action(), btn.label()));
            y += BTN_H + BTN_GAP;
        }
        return wrapInPanel(JOB_TYPE, y, children);
    }

    public static OpenScreenPayload statusScreen(String title, List<String> statusLines) {
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, title, "#FFFFFF"));
        y += 20;
        for (int i = 0; i < statusLines.size(); i++) {
            children.add(label("status_" + i, y, statusLines.get(i), "#AAAAAA"));
            y += 12;
        }
        y += 8;
        children.add(button("btn_ok", y, "dismiss", "Okay"));
        y += BTN_H + BTN_GAP;
        return wrapInPanel(JOB_STATUS, y, children);
    }

    public static OpenScreenPayload targetScreen(NPC npc, List<Target> targets) {
        String header = BuilderNpcService.baseNameOf(npc) + " — Target";
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, header, "#FFFFFF"));
        y += 20;
        for (Target t : targets) {
            children.add(button("btn_" + t.name(), y, t.name().toLowerCase(), t.label()));
            y += BTN_H + BTN_GAP;
        }
        return wrapInPanel(JOB_TARGET, y, children);
    }

    public static OpenScreenPayload confirmScreen(NPC npc, TaskType taskType, Target target) {
        String header = BuilderNpcService.baseNameOf(npc) + " — "
                + taskType.label() + " " + target.label();
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, header, "#FFFFFF"));
        y += 20;
        children.add(new GuiElement.Toggle("surfaceOnly", Anchor.TOP_CENTER,
                new int[]{-40, y}, true, "Surface Only", true));
        y += 16;
        children.add(new GuiElement.Toggle("storeInChest", Anchor.TOP_CENTER,
                new int[]{-40, y}, true, "Store in Chest", true));
        y += 20;
        children.add(button("btn_send", y, "send_to_work", "Send to Work"));
        y += BTN_H + BTN_GAP;
        children.add(button("btn_cancel", y, "cancel", "Cancel"));
        y += BTN_H + BTN_GAP;
        return wrapInPanel(JOB_CONFIRM, y, children);
    }

    public static OpenScreenPayload depthScreen(NPC npc, TaskType taskType) {
        String jobLabel = taskType == TaskType.SHAFT_MINER ? "Shaft Mining" : "Quarrying";
        String header = BuilderNpcService.baseNameOf(npc) + " — " + jobLabel;
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, header, "#FFFFFF"));
        y += 20;
        children.add(label("depth_label", y, "— Depth —", "#FFD700"));
        y += 18;
        children.add(button("btn_level", y, "depth_level", "Level"));
        y += BTN_H + BTN_GAP;
        children.add(button("btn_coord", y, "depth_coordinates", "Coordinates"));
        y += BTN_H + BTN_GAP;
        return wrapInPanel(JOB_DEPTH, y, children);
    }

    public static OpenScreenPayload depthLevelScreen(NPC npc, TaskType taskType) {
        String jobLabel = taskType == TaskType.SHAFT_MINER ? "Shaft Mining" : "Quarrying";
        String header = BuilderNpcService.baseNameOf(npc) + " — " + jobLabel + " Depth (Level)";
        return depthInputScreen(JOB_DEPTH_LEVEL, header, "levels", "Blocks deep");
    }

    public static OpenScreenPayload depthCoordScreen(NPC npc, TaskType taskType) {
        String jobLabel = taskType == TaskType.SHAFT_MINER ? "Shaft Mining" : "Quarrying";
        String header = BuilderNpcService.baseNameOf(npc) + " — " + jobLabel + " Depth (Coordinates)";
        return depthInputScreen(JOB_DEPTH_COORD, header, "targetY", "Target Y coordinate");
    }

    public static OpenScreenPayload landscapeModeScreen(NPC npc) {
        String header = BuilderNpcService.baseNameOf(npc) + " — Landscaping";
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, header, "#FFFFFF"));
        y += 20;
        for (LandscapeMode mode : LandscapeMode.values()) {
            children.add(button("btn_" + mode.name(), y, mode.name().toLowerCase(), mode.label()));
            y += BTN_H + BTN_GAP;
        }
        return wrapInPanel(LANDSCAPE_MODE, y, children);
    }

    public static OpenScreenPayload landscapeBiomeScreen(NPC npc) {
        String header = BuilderNpcService.baseNameOf(npc) + " — Choose Biome";
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, header, "#FFFFFF"));
        y += 20;
        for (LandscapeBiome biome : LandscapeBiome.values()) {
            children.add(button("btn_" + biome.name(), y,
                    biome.name().toLowerCase(), biome.label()));
            y += BTN_H + BTN_GAP;
        }
        return wrapInPanel(LANDSCAPE_BIOME, y, children);
    }

    // --- Internal helpers ---

    private static OpenScreenPayload depthInputScreen(String screenId, String header,
                                                       String inputId, String placeholder) {
        List<GuiElement> children = new ArrayList<>();
        int y = 40;
        children.add(label("title", y, header, "#FFFFFF"));
        y += 20;
        children.add(new GuiElement.TextInput(inputId, Anchor.TOP_CENTER,
                new int[]{0, y}, true, new int[]{160, 18}, placeholder, null));
        y += 24;
        children.add(button("btn_next", y, "next", "Next"));
        y += BTN_H + BTN_GAP;
        children.add(button("btn_cancel", y, "cancel", "Cancel"));
        y += BTN_H + BTN_GAP;
        return wrapInPanel(screenId, y, children);
    }

    private static GuiElement.Label label(String id, int y, String text, String color) {
        return new GuiElement.Label(id, Anchor.TOP_CENTER, new int[]{0, y}, true,
                text, color, true, "center");
    }

    private static GuiElement.Button button(String id, int y, String action, String text) {
        return new GuiElement.Button(id, Anchor.TOP_CENTER, new int[]{0, y}, true,
                new int[]{BTN_W, BTN_H}, action, text,
                "houseofel:gui/button_normal", "houseofel:gui/button_hover",
                "houseofel:gui/button_disabled", true);
    }

    private static OpenScreenPayload wrapInPanel(String screenId, int contentBottom,
                                                  List<GuiElement> children) {
        // Keep the native panel size unless a long list needs the taller variant.
        int panelH = contentBottom + 18 <= PANEL_H ? PANEL_H : TALL_PANEL_H;
        List<GuiElement> themedChildren = new ArrayList<>();
        themedChildren.add(new GuiElement.Image("medallion", Anchor.TOP_CENTER, new int[]{0, -15}, true,
                new int[]{120, 48}, "houseofel:gui/medallion"));
        themedChildren.addAll(children);
        GuiElement.Panel bg = new GuiElement.Panel("bg", Anchor.CENTER, new int[]{0, 0}, true,
                new int[]{PANEL_W, panelH}, "houseofel:gui/panel_main", null, themedChildren);
        return new OpenScreenPayload(screenId, new int[]{PANEL_W, panelH}, null, List.of(bg));
    }

    public record ButtonOption(String id, String action, String label) {}
}

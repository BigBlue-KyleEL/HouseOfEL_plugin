package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceRegistry;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.death.ScarChoice;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.title.FlavorLadder;
import com.houseofel.builder.toil.LevelCurve;
import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Damageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live Helper menu. Job options come from the same rules as the dispatch wizard. */
public final class MainMenuLayout {

    public static final String SCREEN_ID = "main_menu";

    private MainMenuLayout() {}

    public static OpenScreenPayload create(NPC npc, Specialization specialization, int level,
                                            JobManager jobManager, HelperLevelService levelService,
                                            DeathRecordStore deathRecordStore, MilestoneChoiceStore choiceStore,
                                            List<JobMenuLayout.ButtonOption> jobs) {
        return create(npc, specialization, level, jobManager, levelService, deathRecordStore,
                choiceStore, jobs, null);
    }

    /** Display-only overrides; never used for job eligibility or persisted NPC state. */
    public record Preview(String logoTexture, String specializationLabel, Integer level, List<String> tags) {}

    public static OpenScreenPayload create(NPC npc, Specialization specialization, int level,
                                            JobManager jobManager, HelperLevelService levelService,
                                            DeathRecordStore deathRecordStore, MilestoneChoiceStore choiceStore,
                                            List<JobMenuLayout.ButtonOption> jobs, Preview preview) {
        String specializationLabel = preview != null && preview.specializationLabel() != null
                ? preview.specializationLabel() : specialization == null ? null : specialization.label();
        String title = npc.getName() + (specializationLabel == null ? "" : " — " + specializationLabel);
        int displayLevel = preview != null && preview.level() != null ? preview.level() : level;
        String logoTexture = preview != null ? preview.logoTexture() : specialization == null ? null
                : "houseofel:gui/class_" + specialization.name().toLowerCase(Locale.ROOT) + "_t1";
        // Wrap at a word boundary; retain the complete remainder on the second line.
        int titleBreak = title.length() > 46 ? title.lastIndexOf(' ', 46) : -1;
        int headerOffsetY = titleBreak > 0 ? 13 : 0;
        List<GuiElement> children = new ArrayList<>();
        children.add(new GuiElement.Image("medallion", Anchor.TOP_CENTER, new int[]{0, -15}, true,
                new int[]{120, 48}, "houseofel:gui/medallion"));
        if (logoTexture != null) {
            children.add(new GuiElement.Image("class_logo", Anchor.TOP_LEFT, new int[]{22, 24}, true,
                    new int[]{90, 90}, logoTexture));
        }
        children.add(label("npc_name", 114, 37,
                titleBreak > 0 ? title.substring(0, titleBreak) : title, "#FFFFFF"));
        if (titleBreak > 0) {
            children.add(label("npc_name_line2", 114, 50, title.substring(titleBreak + 1), "#FFFFFF"));
        }
        children.add(new GuiElement.Image("tag_bar", Anchor.TOP_LEFT, new int[]{114, 49 + headerOffsetY}, true,
                new int[]{280, 18}, "houseofel:gui/tag_bg"));

        List<String> tags = new ArrayList<>();
        ScarChoice scar = deathRecordStore.scarChoiceOf(npc.getUniqueId());
        if (scar != null) tags.add(scar.name());
        String rust = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);
        if (rust != null) tags.add("RUSTED");
        if (specialization != null) {
            for (int choiceLevel = 1; choiceLevel <= LevelCurve.MAX_LEVEL; choiceLevel++) {
                if (!LevelCurve.isChoiceLevel(choiceLevel)) continue;
                MilestoneChoiceRecord choice = choiceStore.find(npc.getUniqueId(), choiceLevel);
                if (choice != null) tags.add(choice.choice().replace('_', ' '));
            }
        }
        if (preview != null && preview.tags() != null) {
            tags = preview.tags();
        }
        int tagX = 124;
        for (int i = 0; i < Math.min(4, tags.size()); i++) {
            String tag = "[" + tags.get(i) + "]";
            children.add(label("tag_" + (i + 1), tagX, 54 + headerOffsetY, tag,
                    tags.get(i).equals("RUSTED") ? "#FF8C00" : "#FFD700"));
            tagX += tag.length() * 6 + 3;
        }

        String flavor = FlavorLadder.flavorFor(specialization, level);
        boolean hasFlavor = flavor != null && !flavor.isBlank();
        int statsOffsetY = hasFlavor ? 0 : -13;
        if (hasFlavor) {
            children.add(label("flavor", 114, 70 + headerOffsetY, flavor, "#AAAAAA"));
        }
        if (npc.getEntity() instanceof Damageable entity) {
            addHearts(children, entity.getHealth(), entity.getMaxHealth(), 95 + statsOffsetY);
        } else {
            children.add(label("hp_text", 114, 95 + statsOffsetY, "HP unavailable", "#FF4444"));
        }
        children.add(label("level_info", 114, 108 + statsOffsetY,
                "Level " + displayLevel + (displayLevel >= LevelCurve.MAX_LEVEL ? " (max)" : "")
                        + " — " + levelService.bankedToilOf(npc) + " Toil banked.", "#55FF55"));
        if (rust != null) children.add(label("rust_info", 114, 121 + statsOffsetY, rust, "#FF6644"));

        // The reference is now 310px tall. Fit its work area below the live stats
        // on the requested 250px canvas, retaining all texture and button sizes.
        children.add(new GuiElement.Image("divider_work", Anchor.CENTER, new int[]{0, 13}, true,
                new int[]{380, 12}, "houseofel:gui/divider_work_wide"));
        boolean enabled = jobManager.find(npc.getId()) == null && !jobManager.isAtCeiling();
        for (int i = 0; i < Math.min(4, jobs.size()); i++) {
            JobMenuLayout.ButtonOption job = jobs.get(i);
            children.add(button(job.id(), Anchor.CENTER, i % 2 == 0 ? -65 : 65,
                    31 + (i / 2) * 23, job.action(), job.label(), enabled));
        }
        if (jobs.size() > 4) {
            children.add(new GuiElement.Image("divider_spec", Anchor.CENTER, new int[]{0, 72}, true,
                    new int[]{380, 12}, "houseofel:gui/divider_spec_wide"));
            // Only earned, implemented specialization jobs are actionable. The registry
            // supplies their choice labels; unimplemented capstones have no fake button.
            for (int i = 4; i < jobs.size(); i++) {
                JobMenuLayout.ButtonOption job = jobs.get(i);
                String text = job.label();
                for (int choiceLevel : new int[]{8, 16}) {
                    MilestoneChoiceRecord choice = choiceStore.find(npc.getUniqueId(), choiceLevel);
                    if (choice == null || !text.startsWith("Lvl." + choiceLevel + ":")) continue;
                    for (var option : MilestoneChoiceRegistry.optionsFor(specialization, choiceLevel)) {
                        if (option.storedValue().equals(choice.choice())) {
                            text = "Lvl." + choiceLevel + ": " + option.label();
                        }
                    }
                }
                children.add(button("btn_spec" + (i - 3), Anchor.BOTTOM_CENTER,
                        (i - 4) % 2 == 0 ? -65 : 65, -24, job.action(), text, enabled));
            }
        }
        GuiElement.Panel bg = new GuiElement.Panel("bg", Anchor.CENTER, new int[]{0, 0}, true,
                new int[]{426, 250}, "houseofel:gui/panel_main", null, children);
        return new OpenScreenPayload(SCREEN_ID, new int[]{426, 250}, null, List.of(bg));
    }

    /** Ten hearts at most; higher maximum health is scaled into the same row. */
    static void addHearts(List<GuiElement> children, double health, double maxHealth, int heartY) {
        double maximum = Double.isFinite(maxHealth) && maxHealth > 0 ? maxHealth : 20;
        double current = Double.isFinite(health) ? Math.max(0, Math.min(maximum, health)) : 0;
        int count = Math.min(10, Math.max(1, (int) Math.ceil(maximum / 2)));
        double units = current / maximum * count * 2;
        for (int i = 0; i < count; i++) {
            String texture = units >= i * 2 + 2 ? "full" : units > i * 2 ? "half" : "empty";
            children.add(new GuiElement.Image("heart_" + i, Anchor.TOP_LEFT,
                    new int[]{114 + i * 10, heartY}, true, new int[]{9, 9}, "houseofel:gui/heart_" + texture));
        }
        children.add(label("hp_text", 114 + count * 10 + 4, heartY,
                hpText(current) + "/" + hpText(maximum) + " HP", "#FF4444"));
    }

    private static String hpText(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static GuiElement.Label label(String id, int x, int y, String text, String color) {
        return new GuiElement.Label(id, Anchor.TOP_LEFT, new int[]{x, y}, true, text, color, true, "left");
    }

    private static GuiElement.Button button(String id, Anchor anchor, int x, int y,
                                            String action, String text, boolean enabled) {
        return new GuiElement.Button(id, anchor, new int[]{x, y}, true, new int[]{120, 20}, action, text,
                "houseofel:gui/button_normal", "houseofel:gui/button_hover", "houseofel:gui/button_disabled", enabled && !JobAvailability.comingSoon(action),
                JobAvailability.comingSoon(action) ? "Coming soon" : null);
    }
}
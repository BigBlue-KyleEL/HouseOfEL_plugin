package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.death.HelperRust;
import com.houseofel.builder.death.RustState;
import com.houseofel.builder.death.ScarChoice;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.toil.LevelCurve;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the dispatch-menu title both {@link JavaJobDialog} and {@link BedrockJobForm}
 * show — "&lt;Name&gt; — &lt;Specialization&gt; [HARDENED] [RUSTED] [QUARRYMAN]" — one bracket
 * tag per known indicator, in a fixed order (scar choice, then Rust, then each
 * Choice-slot level low to high). Replaces what used to be identical logic copy-pasted in
 * both classes; a new bracket source registers here, not at either call site, so a third
 * copy never happens.
 */
public final class HelperTitleFormatter {

    private static final int XP_BAR_SEGMENTS = 10;

    private HelperTitleFormatter() {
    }

    public static String dispatchTitleOf(NPC npc, Specialization specialization,
                                          DeathRecordStore deathRecordStore, MilestoneChoiceStore choiceStore) {
        String name = BuilderNpcService.baseNameOf(npc);
        String title = specialization == null ? name : name + " — " + specialization.label();

        List<String> tags = new ArrayList<>();
        ScarChoice scarChoice = deathRecordStore.scarChoiceOf(npc.getUniqueId());
        if (scarChoice != null) {
            tags.add(scarChoice.name());
        }
        // Just the flag, not the remaining Toil — the number belongs in the body line
        // (see rustLineFor) and the report command, where there's room to explain it;
        // the title just needs a quick at-a-glance signal, same weight as the other tags.
        if (deathRecordStore.rustFor(npc.getUniqueId()) != null) {
            tags.add("RUSTED");
        }
        if (specialization != null) {
            for (int level = 1; level <= LevelCurve.MAX_LEVEL; level++) {
                if (!LevelCurve.isChoiceLevel(level)) {
                    continue;
                }
                MilestoneChoiceRecord record = choiceStore.find(npc.getUniqueId(), level);
                if (record != null) {
                    tags.add(record.choice());
                }
            }
        }

        StringBuilder result = new StringBuilder(title);
        for (String tag : tags) {
            result.append(" [").append(tag).append("]");
        }
        return result.toString();
    }

    /**
     * The Rust status line shown wherever it needs surfacing on demand (the dispatch
     * menu's body and {@code "<Name> report"}) — one shared string so the two can never
     * drift apart. Replaces the always-on world-space boss bar removed 2026-08-19 (Kyle's
     * call: move it into the menu instead of leaving it floating over the Helper's head
     * for anyone nearby to see). Null (and meant to be omitted entirely) when the Helper
     * isn't currently rusted, matching the flavor line's own "absent when not applicable"
     * convention.
     */
    /**
     * This Helper's current health as a row of hearts — e.g. "❤❤❤❤❤❤❤♡♡♡ 14/20 HP". Real,
     * live health, not cosmetic: Death Policy made Helpers genuinely damageable
     * (Citizens#setProtected(false)) — this reads {@link Damageable#getHealth()} directly
     * off the spawned entity. One heart per 2 HP, rounded to the nearest whole heart —
     * Minecraft's own half-heart granularity isn't worth a second glyph on a menu line.
     * Null if the Helper isn't currently spawned (nothing to read health off of).
     */
    public static String heartsFor(NPC npc) {
        Entity entity = npc.getEntity();
        if (!(entity instanceof Damageable damageable)) {
            return null;
        }
        double health = damageable.getHealth();
        double maxHealth = damageable.getMaxHealth();
        int maxHearts = Math.max(1, (int) Math.round(maxHealth / 2.0));
        int filledHearts = Math.max(0, Math.min(maxHearts, (int) Math.round(health / 2.0)));

        StringBuilder hearts = new StringBuilder();
        for (int i = 0; i < maxHearts; i++) {
            hearts.append(i < filledHearts ? '❤' : '♡');
        }
        return hearts + " " + Math.round(health) + "/" + Math.round(maxHealth) + " HP";
    }

    public static String rustLineFor(NPC npc, DeathRecordStore deathRecordStore) {
        RustState rust = deathRecordStore.rustFor(npc.getUniqueId());
        if (rust == null) {
            return null;
        }
        return "Rusted — " + rust.toilRemaining() + "/" + HelperRust.TOTAL_RUST_TOIL + " Toil to clear.";
    }

    /**
     * Toil progress toward this Helper's next level, as a text bar — e.g.
     * "██████░░░░ 62/90 Toil to Level 7". Ten segments, matching the level curve's own
     * round-number philosophy. Null for an unassigned Helper (no specialization yet,
     * matching the flavor/rust lines' own "absent when not applicable" convention); a flat
     * "max level" line once level 20 is reached, where there's no next level to bar toward.
     */
    public static String xpBarFor(NPC npc, Specialization specialization, HelperLevelService levelService) {
        if (specialization == null) {
            return null;
        }
        int level = levelService.levelOf(npc);
        int banked = levelService.bankedToilOf(npc);
        if (level >= LevelCurve.MAX_LEVEL) {
            return "Level " + LevelCurve.MAX_LEVEL + " (max) — " + banked + " Toil banked.";
        }
        int currentThreshold = LevelCurve.toilThresholdFor(level);
        int nextThreshold = LevelCurve.toilThresholdFor(level + 1);
        int intoLevel = banked - currentThreshold;
        int span = nextThreshold - currentThreshold;
        int filled = (int) Math.round(XP_BAR_SEGMENTS * (intoLevel / (double) span));

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < XP_BAR_SEGMENTS; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        return bar + " " + intoLevel + "/" + span + " Toil to Level " + (level + 1);
    }
}

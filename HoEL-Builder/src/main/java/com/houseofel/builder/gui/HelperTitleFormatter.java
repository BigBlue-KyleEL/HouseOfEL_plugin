package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.death.ScarChoice;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.toil.LevelCurve;
import net.citizensnpcs.api.npc.NPC;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the dispatch-menu title both {@link JavaJobDialog} and {@link BedrockJobForm}
 * show — "&lt;Name&gt; — &lt;Specialization&gt; [HARDENED] [QUARRYMAN]" — one bracket tag per
 * known indicator, in a fixed order (scar choice, then each Choice-slot level low to
 * high). Replaces what used to be identical logic copy-pasted in both classes; a new
 * bracket source registers here, not at either call site, so a third copy never happens.
 */
public final class HelperTitleFormatter {

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
}

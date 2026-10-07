package com.houseofel.builder.title;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.Specialization;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.HologramTrait;

/**
 * Writes a Helper's earned title into its Citizens nameplate, as one line:
 * "Bartholomew, Grade-Setter", "Bartholomew the Earth-Shaper".
 *
 * <p>Punctuation follows the source table in [[V1 Title Ladders (Draft Pool)]]: a comma
 * before titles that read as a role, a plain space before ones that read as an epithet
 * ("the ..."). Below the first title level a Helper is just its own name.
 *
 * <p>A two-line version was tried and abandoned — a Minecraft nameplate is strictly one
 * line, so the second line had to come from a separate hologram entity, and Citizens'
 * own name rendering could not be reliably suppressed underneath it (the trait caches
 * that decision at creation time), leaving the name drawn twice. One line it is.
 *
 * <p>Renaming is safe here only because nothing uses {@link NPC#getName()} as an
 * identifier: the roster name lives in the NPC's own persistent data via
 * {@link BuilderNpcService#baseNameOf}, which is what chat triggers, roster-collision
 * checks and in-character dialogue read. So "&lt;Name&gt; report" keeps working no matter
 * what title a Helper is carrying.
 */
public final class HelperTitleService {

    /** Re-applies the titled name for this Helper's current level. Safe to call repeatedly. */
    public void applyTitle(NPC npc, Specialization specialization, int level) {
        // Order matters: capture the roster name BEFORE any title is appended, or
        // baseNameOf's fallback would later read an already-titled name as the base and
        // compound titles onto each other on every level-up.
        BuilderNpcService.ensureBaseName(npc);
        clearLegacyHologram(npc);

        String display = titledName(BuilderNpcService.baseNameOf(npc), specialization, level);
        // setName() fires an NPCRenameEvent, and this also runs as a boot-time self-heal
        // pass — so only write when something actually changed.
        if (!display.equals(npc.getName())) {
            npc.setName(display);
        }
    }

    /**
     * Undoes the abandoned two-line experiment on any Helper still carrying it: drops the
     * hologram line and restores the nameplate that was switched off to make room for it.
     * Both were persisted onto the NPC, so without this they'd linger indefinitely.
     */
    private static void clearLegacyHologram(NPC npc) {
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE.getKey(), true);
        if (npc.hasTrait(HologramTrait.class)) {
            npc.getOrAddTrait(HologramTrait.class).clear();
            npc.removeTrait(HologramTrait.class);
        }
    }

    /**
     * Joins name and title the way the source table punctuates them: "[Name] the
     * Earth-Shaper" for epithet-style titles, "[Name], Grade-Setter" for role-style ones.
     */
    private static String titledName(String baseName, Specialization specialization, int level) {
        String title = TitleLadder.titleFor(specialization, level);
        if (title == null) {
            return baseName;
        }
        return title.startsWith("the ") ? baseName + " " + title : baseName + ", " + title;
    }
}

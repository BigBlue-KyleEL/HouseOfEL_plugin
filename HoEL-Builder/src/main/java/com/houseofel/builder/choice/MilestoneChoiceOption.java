package com.houseofel.builder.choice;

/**
 * One pickable option at a Choice slot: the display name, a first-person description of
 * what it means (shown in the picker GUI), the value actually persisted — which must
 * equal the spec-specific enum's {@code .name()} (e.g. {@link GroundworkerL8Choice},
 * {@link GroundworkerL16Choice}) — and an optional parent-choice gate.
 *
 * <p>{@code parentChoice} is null for L8 options (no prerequisite) and non-null for L16+
 * options that are only valid when a specific prior choice was made. For example, Cofferdam
 * has {@code parentChoice = "QUARRYMAN"} — it only appears for NPCs that chose Quarryman
 * at L8. See {@link MilestoneChoiceRegistry#optionsFor(
 * com.houseofel.builder.npc.Specialization, int, String)}.
 */
public record MilestoneChoiceOption(String label, String description, String storedValue, String parentChoice) {

    /** L8-style options with no parent-choice gate. */
    public MilestoneChoiceOption(String label, String description, String storedValue) {
        this(label, description, storedValue, null);
    }
}

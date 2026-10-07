package com.houseofel.builder.npc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** New recruitment only: never modifies existing Helpers or their saved specialization. */
public final class RecruitmentAvailability {
    public static final String MESSAGE = "Coming soon — only Groundworker Helpers can be recruited right now.";
    private RecruitmentAvailability() {}

    public static boolean available(Specialization specialization) {
        return specialization == Specialization.GROUNDWORKER;
    }
    public static String label(Specialization specialization) {
        return specialization.label() + (available(specialization) ? "" : " (Coming soon)");
    }
    public static Component refusal() {
        return Component.text(MESSAGE, NamedTextColor.RED);
    }
}

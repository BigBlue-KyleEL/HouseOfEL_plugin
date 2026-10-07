package com.houseofel.builder.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.function.Consumer;

/** Menu availability only; retained TaskType values remain valid for saved jobs. */
public final class JobAvailability {
    public static final String MESSAGE = "Coming soon — only Clearing and specialization jobs work right now.";
    private JobAvailability() {}

    public static boolean comingSoon(TaskType type) {
        return type == TaskType.MINE || type == TaskType.LUMBERJACK || type == TaskType.FARM;
    }
    public static boolean comingSoon(String action) {
        return "mine".equals(action) || "lumberjack".equals(action) || "farm".equals(action);
    }
    public static String label(TaskType type) {
        return type.label() + (comingSoon(type) ? " (Coming soon)" : "");
    }
    public static Component refusal() { return Component.text(MESSAGE, NamedTextColor.RED); }
    public static boolean refuseAction(String action, Consumer<Component> send) {
        if (!comingSoon(action)) return false;
        send.accept(refusal());
        return true;
    }
}

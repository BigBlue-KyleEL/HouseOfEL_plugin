package com.houseofel.builder.gui;

import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.npc.RecruitmentAvailability;
import com.houseofel.common.net.Anchor;
import com.houseofel.common.net.GuiElement;
import com.houseofel.common.net.OpenScreenPayload;
import java.util.ArrayList;
import java.util.List;

/** Described choice buttons using only existing schema-v3 primitives and pack assets. */
public final class ForkScreenLayout {
    static final int BUTTON_WIDTH = 240;
    public record Option(String action, String label, String description, boolean enabled, String tooltip) {
        public Option(String action, String label, String description) {
            this(action, label, description, true, null);
        }
    }
    public static Option specializationOption(Specialization specialization) {
        boolean available = RecruitmentAvailability.available(specialization);
        return new Option("pick:" + specialization.name(), RecruitmentAvailability.label(specialization),
                description(specialization), available, available ? null : "Coming soon");
    }
    private ForkScreenLayout() {}

    public static OpenScreenPayload create(String screenId, String title, List<Option> options) {
        List<GuiElement> children = new ArrayList<>();
        children.add(new GuiElement.Image("medallion", Anchor.TOP_CENTER, new int[]{0, -15}, true,
                new int[]{120, 48}, "houseofel:gui/medallion"));
        int y = 40;
        int line = 0;
        for (String text : wrap(title, 346)) {
            children.add(new GuiElement.Label("title_" + line++, Anchor.TOP_CENTER, new int[]{0, y}, true,
                    text, "#FFFFFF", true, "center"));
            y += 12;
        }
        y += 10;
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            children.add(button("option_" + i, y, option.action(), option.label(), option.enabled(), option.tooltip()));
            y += 25;
            line = 0;
            for (String text : wrap(option.description(), BUTTON_WIDTH - 4)) {
                children.add(new GuiElement.Label("description_" + i + "_" + line++, Anchor.TOP_CENTER,
                        new int[]{0, y}, true, text, "#CCCCCC", true, "center"));
                y += 12;
            }
            y += 12;
        }
        children.add(button("cancel", y, "cancel", "Cancel", true, null));
        int height = Math.max(250, y + 42);
        var panel = new GuiElement.Panel("bg", Anchor.CENTER, new int[]{0, 0}, true,
                new int[]{426, height}, "houseofel:gui/panel_main", null, children);
        return new OpenScreenPayload(screenId, new int[]{426, height}, null, List.of(panel));
    }

    public static String description(Specialization specialization) {
        return switch (specialization) {
            case GROUNDWORKER -> "Specializes in Clearing and earthworks. This Helper's specialization is fixed at recruitment.";
            case LUMBERJACK -> "Specializes in tree cutting. Lumberjack jobs are coming soon. This Helper's specialization is fixed at recruitment.";
            case FARMER -> "Specializes in farming. Farming jobs are coming soon. This Helper's specialization is fixed at recruitment.";
        };
    }

    // V3 labels cannot wrap client-side. Use a conservative default-font advance bound,
    // including wider punctuation, so every centered line stays inside its button.
    static int textWidthBound(String text) {
        return text.codePoints().map(ForkScreenLayout::glyphWidthBound).sum();
    }
    private static int glyphWidthBound(int c) {
        if (c == ' ') return 4;
        if (c == '@' || c == '~') return 7;
        return c < 128 ? 6 : 9;
    }
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty()) {
            int end = 0, used = 0;
            while (end < remaining.length()) {
                int cp = remaining.codePointAt(end);
                int advance = glyphWidthBound(cp);
                if (used + advance > width) break;
                used += advance;
                end += Character.charCount(cp);
            }
            if (end == remaining.length()) { lines.add(remaining); break; }
            if (end == 0) throw new IllegalArgumentException("Text width is narrower than one glyph");
            int split = remaining.lastIndexOf(' ', end);
            if (split <= 0) split = end;
            lines.add(remaining.substring(0, split));
            remaining = remaining.substring(split).stripLeading();
        }
        return lines;
    }

    private static GuiElement.Button button(String id, int y, String action, String label, boolean enabled, String tooltip) {
        return new GuiElement.Button(id, Anchor.TOP_CENTER, new int[]{0, y}, true,
                new int[]{BUTTON_WIDTH, 20}, action, label, "houseofel:gui/button_normal",
                "houseofel:gui/button_hover", "houseofel:gui/button_disabled", enabled, tooltip);
    }
}

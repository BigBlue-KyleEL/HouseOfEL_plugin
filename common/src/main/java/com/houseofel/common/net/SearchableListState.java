package com.houseofel.common.net;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Local-only filtering and viewport state. No dispatch is sent until a row is selected. */
public final class SearchableListState {
    private final GuiElement.SearchableList definition;
    private String query;
    private List<GuiElement.SearchOption> matches = List.of();
    private int first;

    public SearchableListState(GuiElement.SearchableList definition) {
        this.definition = definition;
        updateQuery("");
    }

    public static String normalize(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('_', ' ').strip();
    }

    public void updateQuery(String text) {
        String next = normalize(text);
        if (next.equals(query)) return;
        query = next;
        first = 0;
        if (query.isEmpty()) {
            matches = definition.quickPickIds().stream()
                    .flatMap(id -> definition.options().stream().filter(o -> o.id().equals(id)).limit(1)).toList();
        } else {
            String[] words = query.split("\\s+");
            matches = definition.options().stream().filter(option -> {
                String name = normalize(option.label());
                return Arrays.stream(words).allMatch(name::contains);
            }).toList();
        }
    }

    public boolean isQuickPicks() { return query.isEmpty(); }
    public List<GuiElement.SearchOption> matches() { return matches; }
    public int first() { return first; }
    public int visibleRows() { return Math.max(1, (definition.size()[1] - 16) / 24); }
    public int maxFirst() { return Math.max(0, matches.size() - visibleRows()); }
    public void scrollTo(int row) { first = Math.clamp(row, 0, maxFirst()); }
    public GuiElement.SearchOption row(int visibleRow) {
        int index = first + visibleRow;
        return visibleRow >= 0 && visibleRow < visibleRows() && index < matches.size() ? matches.get(index) : null;
    }
}

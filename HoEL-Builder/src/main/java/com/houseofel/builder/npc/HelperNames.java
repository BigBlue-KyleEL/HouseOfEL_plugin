package com.houseofel.builder.npc;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/** Plain, unique base names; no formatting codes or changes to existing Helpers. */
public final class HelperNames {
    public static final int MAX_LENGTH = 24;
    public record Result(String name, String error) { public boolean valid() { return error == null; } }
    private HelperNames() {}
    public static Result validate(String raw, Collection<String> taken) {
        String name = raw == null ? "" : Normalizer.normalize(raw.strip().replaceAll(" +", " "), Normalizer.Form.NFC);
        if (name.isEmpty()) return new Result(name, "Enter a name for your Helper.");
        if (name.codePointCount(0,name.length()) > MAX_LENGTH) return new Result(name,"Use a name of 24 characters or fewer.");
        if (!name.matches("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} '’-]*")) {
            return new Result(name,"Use letters, numbers, spaces, apostrophes or hyphens.");
        }
        if (taken.stream().anyMatch(n -> key(n).equals(key(name)))) return new Result(name,"That Helper name is already in use.");
        return new Result(name,null);
    }
    static String key(String name) {
        return Normalizer.normalize(name,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}

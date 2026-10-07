package com.houseofel.builder.visual;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Per-spawn audience policy; never changes the source entity or another viewer's state. */
final class GroundworkerAudience<T> {
    private final Set<T> owned = ConcurrentHashMap.newKeySet();
    private final Predicate<UUID> bedrock;

    GroundworkerAudience(Predicate<UUID> bedrock) { this.bedrock = bedrock; }
    void attach(T tracker) { owned.add(tracker); }
    void detach(T tracker) { owned.remove(tracker); }
    void clear() { owned.clear(); }

    boolean showRaisedNameplate(UUID viewer) { return !bedrock.test(viewer); }

    boolean cancelSpawn(T tracker, UUID viewer) {
        // Evaluate Floodgate at spawn time, including joins after the NPC was attached.
        return owned.contains(tracker) && bedrock.test(viewer);
    }
}

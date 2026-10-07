package com.houseofel.builder.visual;

import net.citizensnpcs.api.npc.NPC;

/** Visual-only hooks; the disabled implementation does nothing. */
public interface HelperVisuals extends AutoCloseable {
    HelperVisuals NONE = new HelperVisuals() {};
    default void levelUp(NPC npc) {}
    default void placed(NPC npc) {}
    default void close() {}
}

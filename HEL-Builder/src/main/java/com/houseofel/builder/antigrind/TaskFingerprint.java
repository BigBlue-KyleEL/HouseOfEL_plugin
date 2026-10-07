package com.houseofel.builder.antigrind;

import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.gui.Target;

/**
 * (task type + block position + material class) — the redundancy-decay identity from the
 * framework. {@link Target} is reused as "material class": it's already this codebase's
 * coarser-than-raw-{@code Material} taxonomy for "what kind of thing is this."
 */
public record TaskFingerprint(TaskType taskType, String world, int x, int y, int z, Target materialClass) {
}

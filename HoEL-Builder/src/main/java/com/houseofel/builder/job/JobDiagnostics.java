package com.houseofel.builder.job;

import org.bukkit.plugin.Plugin;

/** Optional job diagnostics. On-disk configuration changes require a server restart. */
final class JobDiagnostics {
    private JobDiagnostics() {}

    static boolean enabled(Plugin plugin) {
        return plugin.getConfig().getBoolean("helpers.diagnostics.enabled", false);
    }
}

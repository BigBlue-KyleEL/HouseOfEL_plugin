package com.houseofel.builder.job;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Opt-in, server-wide sponge absorption diagnostics for comparing Helper and hand-placed
 * sponges. Controlled by helpers.diagnostics.enabled (off by default). Retained for live
 * troubleshooting; changing the config file requires a graceful server restart.
 */
public final class SpongeAbsorptionDiagnosticListener implements Listener {

    private final Plugin plugin;
    private final Logger logger;

    public SpongeAbsorptionDiagnosticListener(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @EventHandler
    public void onAbsorb(SpongeAbsorbEvent event) {
        if (!JobDiagnostics.enabled(plugin)) return;
        Block sponge = event.getBlock();
        logger.info("[groundworker] Sponge diagnostic: placed at (" + sponge.getX() + "," + sponge.getY() + ","
                + sponge.getZ() + ") absorbed " + event.getBlocks().size() + " block(s)");
    }
}

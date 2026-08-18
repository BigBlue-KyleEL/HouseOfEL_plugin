package com.houseofel.builder.job;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * TEMPORARY diagnostic for gotcha #22 (Session Handoff 2026-08-17): a Helper-placed
 * Bulkhead sponge is reported to absorb less water than one placed by hand, root cause
 * not yet isolated. Logs every sponge absorption server-wide — placement coordinates and
 * blocks actually removed (confirmed real via {@code SpongeAbsorbEvent#getBlocks()},
 * 2026-08-17 javap trace against the real jar) — so the next live comparison test
 * produces real numbers instead of "seems weaker." A Bulkhead-placed sponge's line is
 * immediately followed by this job's own "Bulkhead: placed N sponge(s)" summary line; a
 * hand-placed one stands alone — no separate tagging needed to tell them apart in the log.
 *
 * <p>Remove once the weaker-Helper-sponge issue is isolated and resolved — this exists to
 * gather evidence, not as a permanent feature.
 */
public final class SpongeAbsorptionDiagnosticListener implements Listener {

    private final Logger logger;

    public SpongeAbsorptionDiagnosticListener(Plugin plugin) {
        this.logger = plugin.getLogger();
    }

    @EventHandler
    public void onAbsorb(SpongeAbsorbEvent event) {
        Block sponge = event.getBlock();
        logger.info("[groundworker] Sponge diagnostic: placed at (" + sponge.getX() + "," + sponge.getY() + ","
                + sponge.getZ() + ") absorbed " + event.getBlocks().size() + " block(s)");
    }
}

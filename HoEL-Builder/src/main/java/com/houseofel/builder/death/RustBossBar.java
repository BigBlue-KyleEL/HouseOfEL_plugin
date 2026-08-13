package com.houseofel.builder.death;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One BossBar per currently-rusted Helper, visible only to its owner and only within
 * {@link #VISIBILITY_RADIUS} blocks — proximity-gated per Kyle's call, since Rust doesn't
 * get the nameplate (already carrying name + title). Ticks on its own periodic task,
 * checking distance and Rust's remaining Toil each pass; no existing proximity-visibility
 * mechanism in this codebase to model this on, so this is a new one.
 */
public final class RustBossBar {

    private static final double VISIBILITY_RADIUS = 48.0;
    private static final long TICK_PERIOD_TICKS = 20L; // once a second — fine-grained enough for a draining bar

    private final DeathRecordStore store;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public RustBossBar(DeathRecordStore store) {
        this.store = store;
    }

    public static BukkitTask start(Plugin plugin, DeathRecordStore store) {
        RustBossBar rustBossBar = new RustBossBar(store);
        return Bukkit.getScheduler().runTaskTimer(plugin, rustBossBar::tick, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS);
    }

    private void tick() {
        Set<UUID> stillRusted = new HashSet<>();
        for (RustState rust : store.allRusted()) {
            stillRusted.add(rust.npcUuid());
            updateBar(rust);
        }
        removeStaleBars(stillRusted);
    }

    private void updateBar(RustState rust) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(rust.npcUuid());
        if (npc == null || !npc.isSpawned()) {
            // Despawned (mid-recovery, or a stale row from right after boot) — nothing to
            // anchor a distance check to yet; leave any existing bar as-is until it's back.
            return;
        }
        Entity entity = npc.getEntity();
        UUID ownerUuid = store.ownerOf(rust.npcUuid());
        Player owner = ownerUuid == null ? null : Bukkit.getPlayer(ownerUuid);

        BossBar bar = bars.computeIfAbsent(rust.npcUuid(), id -> Bukkit.createBossBar(
                "Rust — working it off", BarColor.RED, BarStyle.SOLID));
        // Draining, per the framework's "loud visible draining bar" requirement — starts
        // full at 200 Toil remaining, empties as Rust clears.
        bar.setProgress(clamp(rust.toilRemaining() / (double) HelperRust.TOTAL_RUST_TOIL));

        boolean nearby = owner != null && isNearby(entity.getLocation(), owner.getLocation());
        if (nearby) {
            if (!bar.getPlayers().contains(owner)) {
                bar.addPlayer(owner);
            }
        } else {
            bar.removeAll();
        }
    }

    private void removeStaleBars(Set<UUID> stillRusted) {
        Iterator<Map.Entry<UUID, BossBar>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BossBar> entry = iterator.next();
            if (!stillRusted.contains(entry.getKey())) {
                entry.getValue().removeAll();
                iterator.remove();
            }
        }
    }

    private static boolean isNearby(Location helper, Location owner) {
        if (!helper.getWorld().equals(owner.getWorld())) {
            return false;
        }
        return helper.distanceSquared(owner) <= VISIBILITY_RADIUS * VISIBILITY_RADIUS;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

package com.houseofel.builder.death;

import com.houseofel.builder.npc.BuilderNpcService;
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
 * One BossBar per currently-rusted Helper, visible to anyone within
 * {@link #VISIBILITY_RADIUS} blocks — NOT owner-gated (Kyle's call 2026-08-14, reversing
 * the original owner-only plan: a household where multiple people actually operate
 * Helpers shouldn't leave Rust invisible to whoever isn't the original spawner). Ticks on
 * its own periodic task, checking distance and Rust's remaining Toil each pass.
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
        Location site = npc.getEntity().getLocation();

        // Yellow, not red — red reads as a boss fight/raid warning in vanilla Minecraft,
        // which isn't the vibe here (Kyle's call, 2026-08-14). Vanilla only offers 7 boss
        // bar colors (Pink/Blue/Red/Green/Yellow/Purple/White) — no orange exists to pick
        // instead. Title includes the base name (never npc.getName() — see gotcha #7) so
        // multiple simultaneous Rust bars are distinguishable.
        BossBar bar = bars.computeIfAbsent(rust.npcUuid(), id -> Bukkit.createBossBar(
                BuilderNpcService.baseNameOf(npc) + " — Rust (working it off)", BarColor.YELLOW, BarStyle.SOLID));
        // Draining, per the framework's "loud visible draining bar" requirement — starts
        // full at 200 Toil remaining, empties as Rust clears.
        bar.setProgress(clamp(rust.toilRemaining() / (double) HelperRust.TOTAL_RUST_TOIL));

        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean nearby = isNearby(site, player.getLocation());
            boolean currentlyShown = bar.getPlayers().contains(player);
            if (nearby && !currentlyShown) {
                bar.addPlayer(player);
            } else if (!nearby && currentlyShown) {
                bar.removePlayer(player);
            }
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

    private static boolean isNearby(Location helper, Location viewer) {
        if (!helper.getWorld().equals(viewer.getWorld())) {
            return false;
        }
        return helper.distanceSquared(viewer) <= VISIBILITY_RADIUS * VISIBILITY_RADIUS;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

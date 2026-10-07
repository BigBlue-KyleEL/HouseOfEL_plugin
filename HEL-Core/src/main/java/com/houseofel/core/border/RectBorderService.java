package com.houseofel.core.border;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Gives each player in the configured world their own client-side border square (Paper's
 * per-player API) so the vanilla wall appears exactly on a rectangle. Visual + collision
 * only: the world's real border is never touched, and server-side enforcement stays with
 * ChunkyBorder. Damage settings are left at defaults on purpose.
 */
public final class RectBorderService implements Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, WorldBorder> borders = new HashMap<>();

    private boolean enabled;
    private String worldName;
    private int warningDistance;
    private RectBorderMath math;

    public RectBorderService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * Reads the {@code rect-border} section; an invalid box logs one warning and disables.
     * No-arg getters on purpose: they fall back to the jar's config.yml defaults when an
     * older server config.yml predates this section.
     */
    public void load(ConfigurationSection cfg) {
        enabled = false;
        math = null;
        if (cfg == null || !cfg.getBoolean("enabled")) return;
        worldName = cfg.getString("world");
        warningDistance = Math.max(0, cfg.getInt("warning-distance"));
        double minX = cfg.getDouble("min-x"), maxX = cfg.getDouble("max-x");
        double minZ = cfg.getDouble("min-z"), maxZ = cfg.getDouble("max-z");
        String problem = RectBorderMath.validate(minX, maxX, minZ, maxZ);
        if (problem != null) {
            logger.warning("rect-border disabled: " + problem + ".");
            return;
        }
        math = new RectBorderMath(minX, maxX, minZ, maxZ, cfg.getInt("update-step"));
        enabled = true;
        logger.info("rect-border active in '" + worldName + "': X " + minX + ".." + maxX
                + ", Z " + minZ + ".." + maxZ + " (square side " + math.side() + ").");
    }

    public void applyToOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p, true);
    }

    public void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) clear(p);
        borders.clear();
    }

    public boolean isEnabled() { return enabled; }

    public String worldName() { return worldName; }

    public RectBorderMath math() { return math; }

    /** The border currently sent to this player by us, or null. */
    public WorldBorder borderOf(Player p) { return borders.get(p.getUniqueId()); }

    // --- events -------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { later(e.getPlayer(), true); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { later(e.getPlayer(), true); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) { later(e.getPlayer(), true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) { later(e.getPlayer(), false); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (sameBlockColumn(e.getFrom(), e.getTo())) return;
        refresh(e.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (sameBlockColumn(e.getFrom(), e.getTo())) return;
        for (Entity passenger : e.getVehicle().getPassengers()) {
            if (passenger instanceof Player p) refresh(p, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { borders.remove(e.getPlayer().getUniqueId()); }

    // --- core ---------------------------------------------------------------------------

    private static boolean sameBlockColumn(Location from, Location to) {
        return to == null || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld());
    }

    /** One tick later, so our border lands after any packets the server sends for the event. */
    private void later(Player p, boolean resend) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) refresh(p, resend); });
    }

    private void refresh(Player p, boolean resend) {
        if (!enabled || !p.getWorld().getName().equals(worldName)) {
            clear(p);
            return;
        }
        Location loc = p.getLocation();
        RectBorderMath.Center c = math.centerFor(loc.getX(), loc.getZ());
        WorldBorder border = borders.get(p.getUniqueId());
        if (border == null) {
            border = Bukkit.createWorldBorder();
            border.setCenter(c.x(), c.z());
            border.setSize(math.side());
            border.setWarningDistance(warningDistance);
            borders.put(p.getUniqueId(), border);
            p.setWorldBorder(border);
            return;
        }
        Location current = border.getCenter();
        if (current.getX() != c.x() || current.getZ() != c.z()) {
            border.setCenter(c.x(), c.z()); // Paper forwards changes to the viewing player
        }
        if (resend) p.setWorldBorder(border);
    }

    private void clear(Player p) {
        if (borders.remove(p.getUniqueId()) != null) {
            p.setWorldBorder(null);
        }
    }
}

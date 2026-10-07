package com.houseofel.builder.visual;

import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.job.JobTask;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.event.ModelSpawnAtPlayerEvent;
import kr.toxicity.model.api.event.ModelEventListener;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.data.blueprint.BlueprintAnimation;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owns packet visuals only. Never replaces, scales, mounts, or navigates the Citizens entity. */
public final class GroundworkerModelService implements HelperVisuals, Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final BuilderNpcService npcs;
    private final HelperLevelService levels;
    private final DeathRecordStore deaths;
    private final JobManager jobs;
    private final String model;
    private final double nameHeight;
    private final float animationSpeed;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final BukkitTask task;
    private final GroundworkerAudience<Tracker> audience = new GroundworkerAudience<>(GroundworkerModelService::bedrock);
    private final ModelEventListener spawnListener;
    private volatile long ticks;
    private ModelRenderer playbackRenderer;
    private Map<String, BlueprintAnimation> playback;
    private boolean warned;
    private final class Entry {
        final NPC npc;
        final EntityTracker tracker;
        final TextDisplay label;
        final boolean previousNameplate;
        GroundworkerAnimation animation;
        Location previous;
        boolean tookControl;
        boolean rusted;
        long previewUntil;
        volatile long renderedAfter = Long.MAX_VALUE;
        volatile Object playbackToken;
        final Map<String, BlueprintAnimation> clips;
        Entry(NPC npc, EntityTracker tracker, TextDisplay label) {
            this.npc = npc; this.tracker = tracker; this.label = label;
            clips = playback;
            previousNameplate = npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE.getKey(), true);
            previous = npc.getEntity().getLocation();
            animation = new GroundworkerAnimation((clip, hold) -> play(this, clip, hold), animationSpeed, () -> ticks >= renderedAfter);
        }
    }
    public GroundworkerModelService(JavaPlugin plugin, BuilderNpcService npcs, HelperLevelService levels,
                                   DeathRecordStore deaths, JobManager jobs) {
        this.plugin = plugin; this.npcs = npcs; this.levels = levels; this.deaths = deaths; this.jobs = jobs;
        model = plugin.getConfig().getString("helpers.custom-model.model", "groundworker");
        animationSpeed = readAnimationSpeed(plugin);
        nameHeight = plugin.getConfig().getDouble("helpers.custom-model.nameplate-height", 2.6);
        // viewFilter only filters later display updates; it does NOT reject the spawn or source hiding.
        // Cancel before RenderPipeline puts a Floodgate viewer in its spawned-player map.
        spawnListener = BetterModel.eventBus().subscribe(plugin::isEnabled, ModelSpawnAtPlayerEvent.class, event -> {
            if (audience.cancelSpawn(event.getTracker(), event.getPlayer().uuid())) {
                event.setCancelled(true);
                if (plugin.getConfig().getBoolean("helpers.custom-model.debug", false))
                    plugin.getLogger().info("[groundworker-model] Bedrock spawn excluded: " + event.getPlayer().uuid());
            }
        });
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("helpermodel").setExecutor(this);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        plugin.getLogger().info("Groundworker custom visuals enabled; BetterModel 3.0.2; Java-only spawn gate; Bedrock retains the base entity.");
    }
    private static float readAnimationSpeed(JavaPlugin plugin) {
        try {
            return GroundworkerAnimation.validateSpeed(plugin.getConfig().getDouble("helpers.custom-model.animation-speed", 2.0));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid helpers.custom-model.animation-speed; using 1.0");
            return 1.0f;
        }
    }
    private static boolean bedrock(UUID uuid) {
        try { return FloodgateApi.getInstance().isFloodgatePlayer(uuid); }
        catch (LinkageError | IllegalStateException e) { return false; }
    }
    public void scan() {
        for (NPC npc : CitizensAPI.getNPCRegistry()) attach(npc);
    }
    private void attach(NPC npc) {
        if (!npc.isSpawned() || !npcs.isHelper(npc) || levels.specializationOf(npc) != Specialization.GROUNDWORKER)
            return;
        Entry existing = entries.get(npc.getUniqueId());
        if (existing != null && !existing.tracker.isClosed()) return;
        if (existing != null) detach(npc);
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) {
            if (!warned) plugin.getLogger().warning("Groundworker model not loaded: " + model + "; leaving Citizens visible.");
            warned = true; return;
        }
        for (var clip : GroundworkerAnimation.Clip.values()) {
            if (renderer.animation(clip.name).isEmpty()) throw new IllegalStateException("Missing approved animation " + clip.name);
        }
        if (playbackRenderer != renderer) {
            try {
                var betterModel = plugin.getServer().getPluginManager().getPlugin("BetterModel");
                playback = GroundworkerPlayback.load(betterModel.getDataFolder().toPath()
                        .resolve("models").resolve(model + ".bbmodel"), renderer.animations(), animationSpeed);
                playbackRenderer = renderer;
                plugin.getLogger().info("Groundworker runtime playback prepared at " + animationSpeed
                        + "x, 25ms cadence; model file read only; dig=" + playback.get("dig").length()
                        + "s; greet=" + playback.get("greet").length() + "s; levelup="
                        + playback.get("levelup").length() + "s; recover=" + playback.get("levelup_recover").length() + "s");
            } catch (java.io.IOException | RuntimeException ex) {
                if (!warned) plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Cannot prepare Groundworker runtime playback; leaving Citizens visible", ex);
                warned = true; return;
            }
        }
        if (!(npc.getEntity() instanceof LivingEntity living)) return;
        EntityTracker tracker = renderer.create(BukkitAdapter.adapt(living),
                new TrackerModifier(false, false, false), t -> {
                    // This callback runs before registry refreshSpawn(), covering players already nearby.
                    audience.attach(t);
                    t.handleCloseEvent((closed, reason) -> audience.detach(closed));
                });
        // No hitbox tags exist in the approved asset. Reject rather than silently change collision/pathing.
        if (tracker.bone("groundworker") == null || tracker.bone("shovel_motion") == null) {
            tracker.close(); throw new IllegalStateException("BetterModel did not retain both approved root bones");
        }
        TextDisplay label = living.getWorld().spawn(living.getLocation().add(0, nameHeight, 0), TextDisplay.class, d -> {
            // Opt in Java viewers before tracking; Bedrock already renders the native villager name.
            d.setVisibleByDefault(false);
            d.text(Component.text(npc.getName())); d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false); d.setInvulnerable(true); d.setGravity(false);
            d.setShadowed(true); d.setSeeThrough(false);
            d.addScoreboardTag("hel-groundworker-nameplate");
            d.removeScoreboardTag("hoel-groundworker-nameplate");
        });
        Entry entry = new Entry(npc, tracker, label);
        entries.put(npc.getUniqueId(), entry);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) updateNameplate(viewer, label);
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE.getKey(), false);
        plugin.getLogger().info("Attached Groundworker model to NPC #" + npc.getId() + " (" + living.getType()
                + "), roots=" + tracker.bones().size() + " bones; Citizens type/hitbox/pathing untouched.");
    }
    private void updateNameplate(Player viewer, TextDisplay label) {
        if (audience.showRaisedNameplate(viewer.getUniqueId())) viewer.showEntity(plugin, label);
        else viewer.hideEntity(plugin, label);
    }
    private void refreshNameplates(Player viewer) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            for (Entry entry : entries.values()) updateNameplate(viewer, entry.label);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { refreshNameplates(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void changedWorld(PlayerChangedWorldEvent event) { refreshNameplates(event.getPlayer()); }
    private void detach(NPC npc) {
        Entry e = entries.remove(npc.getUniqueId());
        if (e == null) return;
        e.tracker.close(); e.label.remove();
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE.getKey(), e.previousNameplate);
    }
    private void tick() {
        ticks++;
        if (ticks % 20 == 0) scan(); // self-heal for assigned-after-spawn NPCs and model reloads
        for (Entry e : java.util.List.copyOf(entries.values())) {
            if (!e.npc.isSpawned() || e.tracker.isClosed()) { detach(e.npc); continue; }
            Location at = e.npc.getEntity().getLocation();
            boolean moving = at.getWorld() == e.previous.getWorld() && at.distanceSquared(e.previous) > 0.0001;
            e.previous = at;
            e.label.teleport(at.clone().add(0, nameHeight, 0));
            e.label.text(Component.text(e.npc.getName()));
            // Title changes may restore the original plate; suppress it while our higher plate exists.
            e.npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE.getKey(), false);
            if (!e.tookControl && e.tracker.isScheduled()) {
                e.tookControl = true;
                e.tracker.stopAnimation("idle"); e.tracker.stopAnimation("walk");
                e.tracker.stopAnimation("idle_fly"); e.tracker.stopAnimation("walk_fly");
                e.animation.replay(); // replace BetterModel's initial automatic idle/walk exactly once
            }
            if (ticks < e.previewUntil) continue;
            JobTask job = jobs.find(e.npc.getId());
            boolean session = job != null && !job.isPaused();
            boolean rusted = ticks % 20 == 0 ? deaths.rustFor(e.npc.getUniqueId()) != null : e.rusted;
            e.rusted = rusted;
            e.animation.tick(ticks, session, moving, rusted);
        }
    }
    private void play(Entry e, GroundworkerAnimation.Clip clip, boolean hold) {
        for (var old : GroundworkerAnimation.Clip.values()) e.tracker.stopAnimation(old.name);
        var token = new Object();
        e.playbackToken = token;
        e.renderedAfter = Long.MAX_VALUE;
        var prepared = e.clips.get(clip.name);
        if (clip.ticks != 0) prepared = GroundworkerPlayback.onLastPose(prepared, () -> {
            // Allow one server tick for the final display interpolation, then transition immediately.
            if (e.playbackToken == token) e.renderedAfter = ticks + 1;
        });
        // Timed clips run once and hold their final pose until the controller releases it.
        // This avoids a reset between the engine's 25ms clock and the server's 50ms clock.
        AnimationIterator.Type type = clip.ticks == 0 ? AnimationIterator.Type.LOOP : AnimationIterator.Type.HOLD_ON_LAST;
        boolean started = e.tracker.animate(prepared, AnimationModifier.builder()
                .start(0).end(0).priority(100).speed(1).type(type).build());
        if (!started) plugin.getLogger().warning("Cannot play " + clip.name + " for NPC #" + e.npc.getId());
        if (plugin.getConfig().getBoolean("helpers.custom-model.debug", false))
            plugin.getLogger().info("[groundworker-model] NPC #" + e.npc.getId() + " -> " + clip.name);
    }
    @Override public void levelUp(NPC npc) {
        Entry e = entries.get(npc.getUniqueId()); if (e != null) e.animation.levelUp();
    }
    @Override public void placed(NPC npc) {
        Entry e = entries.get(npc.getUniqueId()); if (e != null) e.animation.place();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(NPCSpawnEvent event) { plugin.getServer().getScheduler().runTask(plugin, () -> attach(event.getNPC())); }
    @EventHandler(priority = EventPriority.MONITOR) public void despawn(NPCDespawnEvent event) { detach(event.getNPC()); }
    @EventHandler(priority = EventPriority.MONITOR) public void remove(NPCRemoveEvent event) { detach(event.getNPC()); }
    @EventHandler(priority = EventPriority.MONITOR) public void chunk(ChunkLoadEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, this::scan);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void greet(NPCRightClickEvent event) {
        Entry e = entries.get(event.getNPC().getUniqueId()); if (e != null) e.animation.greet();
    }
    @Override public void close() {
        task.cancel();
        for (Entry e : java.util.List.copyOf(entries.values())) detach(e.npc);
        spawnListener.unregister();
        audience.clear();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof org.bukkit.entity.Player && !sender.hasPermission("houseofel.builder.modeltest")) {
            sender.sendMessage("You do not have houseofel.builder.modeltest."); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Groundworker model=" + model + "; speed=" + animationSpeed + "x; greet=1.0x; levelup=1.0x; recover=1.0x; runtime-resampled; attached=" + entries.size() + "; Java-only visuals");
            for (Entry e : entries.values()) {
                sender.sendMessage("NPC #" + e.npc.getId() + " " + e.npc.getName()
                        + ": " + e.animation.clip() + "; viewer scheduler=" + e.tracker.isScheduled()
                        + "; source invisible=" + ((LivingEntity) e.npc.getEntity()).isInvisible());
                for (var viewer : plugin.getServer().getOnlinePlayers()) {
                    if (viewer.getWorld() != e.npc.getEntity().getWorld()) continue;
                    sender.sendMessage("  " + viewer.getName() + ": bedrock=" + bedrock(viewer.getUniqueId())
                            + "; model spawned=" + e.tracker.isSpawned(viewer.getUniqueId())
                            + "; raised nameplate=" + viewer.canSee(e.label));
                }
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("play")) {
            try {
                int id = Integer.parseInt(args[1]);
                Entry e = entries.values().stream().filter(v -> v.npc.getId() == id).findFirst().orElse(null);
                if (e == null) { sender.sendMessage("No attached Groundworker #" + id); return true; }
                var clip = java.util.Arrays.stream(GroundworkerAnimation.Clip.values())
                        .filter(c -> c.name.equals(args[2])).findFirst().orElseThrow();
                if (clip == GroundworkerAnimation.Clip.LEVELUP) e.animation.levelUp();
                else { e.animation.preview(clip, ticks); e.previewUntil = ticks + (clip.ticks == 0 ? e.animation.scaledTicks(100) : e.animation.scaledTicks(clip)); }
                sender.sendMessage("Visual preview: " + clip.name + " on NPC #" + id + " (no job/level changes)");
            } catch (IllegalArgumentException ex) { sender.sendMessage("Unknown NPC id or clip."); }
            return true;
        }
        sender.sendMessage("/helpermodel status | /helpermodel play <npcId> <clip>"); return true;
    }
}

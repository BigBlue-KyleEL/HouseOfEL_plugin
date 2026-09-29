package com.houseofel.builder.job;

import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.toil.TicketKind;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/** Persistent region maintenance. Never registers a job or requests chunk tickets. */
final class CofferdamWatchService implements Listener {
    static final long LIFETIME = 7L * 24000;
    private final Plugin plugin;
    private final JobManager manager;
    private final HelperLevelService levels;
    private final RedundancyTracker redundancy;
    private final FreshLedger fresh;
    private final File folder;
    private final Map<String, Watch> watches = new LinkedHashMap<>();
    private boolean started;
    private int ticks;
    private int nextWatch;

    private static final class Watch {
        JobState state;
        long expires;
        long cursor;
        int pendingCredit;
        boolean shortage;
        Watch(JobState state,long expires) { this.state=state; this.expires=expires; }
    }

    CofferdamWatchService(Plugin plugin, JobManager manager, HelperLevelService levels,
                           RedundancyTracker redundancy, FreshLedger fresh) {
        this.plugin=plugin; this.manager=manager; this.levels=levels;
        this.redundancy=redundancy; this.fresh=fresh;
        folder=new File(plugin.getDataFolder(),"cofferdam-watches");
    }

    void start() {
        if (started) return;
        started=true;
        loadSaved();
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,() -> tick(),1L,1L);
    }

    void loadSaved() {
        File[] files=folder.listFiles((dir,name)->name.endsWith(".yml"));
        if (files!=null) for (File file:files) {
            try {
                YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);
                YamlConfiguration job=new YamlConfiguration();
                job.loadFromString(yaml.getString("job"));
                JobState state=JobStateStore.decode(job);
                Watch watch=new Watch(state,yaml.getLong("expires"));
                watch.cursor=yaml.getLong("cursor");
                watch.pendingCredit=yaml.getInt("pendingCredit");
                watch.shortage=yaml.getBoolean("shortage");
                watches.put(state.cofferdamId,watch);
            } catch (Exception e) {
                plugin.getLogger().severe("Cannot load Cofferdam watch " + file.getName()+": "+e.getMessage());
            }
        }
    }

    /** Durable before JobManager removes the old job; the stable id makes replay idempotent. */
    void add(JobState state) {
        if (state.cofferdamId==null) state.cofferdamId=UUID.nameUUIDFromBytes(
                ("legacy:"+state.worldName+":"+state.npcId+":"+state.minX+":"+state.minY+":"+state.minZ)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        if (watches.containsKey(state.cofferdamId)) return;
        World world=Bukkit.getWorld(state.worldName);
        Watch watch=new Watch(state,world==null?-1:world.getFullTime()+LIFETIME);
        save(watch);
        watches.put(state.cofferdamId,watch);
    }

    boolean contains(String id) { return id!=null && watches.containsKey(id); }

    void saveAll() {
        for (Watch watch:watches.values()) save(watch);
    }

    private void save(Watch watch) {
        try {
            Files.createDirectories(folder.toPath());
            YamlConfiguration yaml=new YamlConfiguration();
            yaml.set("job",JobStateStore.encode(watch.state).saveToString());
            yaml.set("expires",watch.expires);
            yaml.set("cursor",watch.cursor);
            yaml.set("pendingCredit",watch.pendingCredit);
            yaml.set("shortage",watch.shortage);
            var target=new File(folder,watch.state.cofferdamId+".yml").toPath();
            var temp=target.resolveSibling(target.getFileName()+".tmp");
            Files.writeString(temp,yaml.saveToString());
            try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist Cofferdam watch",e);
        }
    }

    private void tick() {
        if (watches.isEmpty()) return;
        // Global budget, round-robin across regions, including unloaded cells.
        var list=new ArrayList<>(watches.values());
        Watch watch=list.get(nextWatch++ % list.size());
        if (nextWatch==Integer.MAX_VALUE) nextWatch=0;
        tick(watch);
        if (++ticks>=200) { ticks=0; saveAll(); }
    }

    private void tick(Watch watch) {
        JobState s=watch.state;
        World world=Bukkit.getWorld(s.worldName);
        if (world==null) return;
        if (watch.expires<0) { watch.expires=world.getFullTime()+LIFETIME; save(watch); }
        NPC npc=s.cofferdamHelperUuid==null?CitizensAPI.getNPCRegistry().getById(s.npcId)
                :CitizensAPI.getNPCRegistry().getByUniqueId(UUID.fromString(s.cofferdamHelperUuid));
        if (npc!=null && s.cofferdamHelperUuid==null) s.cofferdamHelperUuid=npc.getUniqueId().toString();
        UUID helper=s.cofferdamHelperUuid==null?null:UUID.fromString(s.cofferdamHelperUuid);
        if (world.getFullTime()<watch.expires) {
            // Legacy watches retain their old outer bounds; no fabricated historical entrance.
            if (s.cofferdamFacing!=null) CofferdamGeometry.entrance(world,s);
            long sx=(long)s.maxX-s.minX+1, sy=(long)s.maxY-s.minY+1, sz=(long)s.maxZ-s.minZ+1;
            long volume=sx*sy*sz;
            for (int i=0;i<Math.min(512L,volume);i++) {
                long index=watch.cursor++ % volume;
                int x=s.minX+(int)(index%sx);
                int z=s.minZ+(int)((index/sx)%sz);
                int y=s.minY+(int)(index/(sx*sz));
                if (!CofferdamGeometry.loaded(world,x,z)) continue;
                Block block=world.getBlockAt(x,y,z);
                if (CofferdamGeometry.interior(s,x,y,z)) {
                    if (CofferdamWork.wet(block)) {
                        int units=CofferdamWork.credit(block,helper,redundancy,fresh);
                        CofferdamWork.drain(block);
                        watch.pendingCredit+=units;
                    }
                } else if (!CofferdamGeometry.isDoor(s,x,y,z) && !block.getType().isOccluding()) {
                    if (withdrawLoaded(world,s)) {
                        int units=CofferdamWork.credit(block,helper,redundancy,fresh);
                        block.setType(Material.COBBLESTONE,false);
                        watch.pendingCredit+=units;
                        watch.shortage=false;
                    } else if (!watch.shortage) {
                        watch.shortage=true;
                        notifyOwner(s,"Cofferdam repairs need cobblestone in a loaded station chest.");
                    }
                }
            }
            watch.cursor%=volume;
        }
        if (npc!=null && watch.pendingCredit>0 && levels.specializationOf(npc)==Specialization.GROUNDWORKER) {
            for (var result:levels.awardProgress(npc,TicketKind.GROUNDWORKER_CLEAR_512,512*4,watch.pendingCredit))
                for (String line:result.announcementLines()) notifyOwner(s,line);
            watch.pendingCredit=0;
        }
        if (world.getFullTime()>=watch.expires && watch.pendingCredit==0) {
            // Flush partial ticket credit before deleting its durable delivery record.
            levels.flushProgress();
            try { Files.deleteIfExists(new File(folder,s.cofferdamId+".yml").toPath()); }
            catch (IOException e) { throw new IllegalStateException(e); }
            watches.remove(s.cofferdamId);
            notifyOwner(s,"The Cofferdam's seven-day watch has ended. The dam stays; repairs and drainage have stopped.");
        }
    }

    private boolean withdrawLoaded(World world, JobState state) {
        for (String encoded:state.chests) {
            String[] p=encoded.split(",");
            int x=Integer.parseInt(p[0]), y=Integer.parseInt(p[1]), z=Integer.parseInt(p[2]);
            if (!CofferdamGeometry.loaded(world,x,z)) continue;
            if (!(world.getBlockAt(x,y,z).getState() instanceof Chest chest)) continue;
            // Adopted double chests may list only one half. Use the combined inventory
            // only if every possible neighbour is already loaded.
            boolean neighboursLoaded=CofferdamGeometry.loaded(world,x-1,z)
                    && CofferdamGeometry.loaded(world,x+1,z)
                    && CofferdamGeometry.loaded(world,x,z-1)
                    && CofferdamGeometry.loaded(world,x,z+1);
            Inventory inventory=neighboursLoaded?chest.getInventory():chest.getBlockInventory();
            for (int slot=0;slot<inventory.getSize();slot++) {
                ItemStack stack=inventory.getItem(slot);
                if (stack==null || stack.getType()!=Material.COBBLESTONE || stack.getAmount()<1) continue;
                if (stack.getAmount()==1) inventory.setItem(slot,null);
                else { stack.setAmount(stack.getAmount()-1); inventory.setItem(slot,stack); }
                return true;
            }
        }
        return false;
    }

    private void notifyOwner(JobState state,String message) {
        var player=Bukkit.getPlayer(state.playerId);
        if (player!=null) player.sendMessage(Component.text(message,NamedTextColor.GREEN));
        else manager.queueOfflineNotification(state.playerId,message);
    }

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onWater(BlockFromToEvent event) {
        if (event.getBlock().getType()!=Material.WATER) return;
        Block to=event.getToBlock();
        for (Watch watch:watches.values()) {
            JobState s=watch.state;
            if (to.getWorld().getName().equals(s.worldName) && to.getWorld().getFullTime()<watch.expires
                    && CofferdamGeometry.interior(s,to.getX(),to.getY(),to.getZ())) {
                event.setCancelled(true); return;
            }
        }
    }
}

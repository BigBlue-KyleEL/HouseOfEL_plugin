package com.houseofel.builder.job;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * A Helper actively tracked by {@link JobManager} can't take fall damage. A tall Clearing
 * job routinely hollows out more of the working area than its own dig order accounts for
 * (multiple sweep passes revisiting scattered stragglers left an entire column open except
 * a few floating blocks — confirmed live 2026-08-18, see the banded dig order now added to
 * {@link ClearJobTask#seekNextBlock()}), and a Helper dying by falling through a pit its
 * own job dug is a bug in the digging, not a real hazard worth modeling. This is a blanket
 * safety net on top of that structural fix, not a replacement for it — scoped to
 * {@link DamageCause#FALL} only, so lava, mobs, and every other real hazard are untouched.
 */
public final class JobFallProtectionListener implements Listener {

    private final JobManager jobManager;

    public JobFallProtectionListener(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.FALL) {
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null || jobManager.find(npc.getId()) == null) {
            return;
        }
        event.setCancelled(true);
    }
}

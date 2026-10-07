package com.houseofel.builder.job;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Delivers whatever job-completion messages queued up while a player was offline. */
public final class JobNotificationListener implements Listener {

    private final JobManager jobManager;

    public JobNotificationListener(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        jobManager.deliverPendingNotifications(event.getPlayer());
    }
}

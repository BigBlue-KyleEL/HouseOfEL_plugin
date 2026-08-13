package com.houseofel.builder.npc;

import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.job.JobManager.Outcome;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Lets players steer a Helper's job with plain chat instead of a slash command —
 * "&lt;Helper's name&gt; wait" (or any of its synonyms) pauses, resumes, or cancels
 * whatever job that Helper is running, no "/" required. Deliberately playful rather
 * than technical: several words per action, each with its own response. Hardcoded for
 * now — once HoEL-LLM is wired up, these lines are the natural first thing to hand off
 * to it for more dynamic in-character responses.
 *
 * <p>The chat event this listens on fires off the main thread. Everything here up to
 * {@code event.setCancelled} is read-only (string matching, NPC lookup, a thread-safe
 * existence check) — the actual pause/resume/cancel, which touches live NPC/entity
 * state, is always dispatched back onto the main thread.
 */
public final class HelperCommandListener implements Listener {

    private record Trigger(String word, String response) { }

    private static final List<Trigger> PAUSE_TRIGGERS = List.of(
            new Trigger("hold", "Holding position."),
            new Trigger("halt", "Halted."),
            new Trigger("wait", "I shall await your call."));
    private static final List<Trigger> RESUME_TRIGGERS = List.of(
            new Trigger("proceed", "Right, proceeding."),
            new Trigger("carry on", "Carrying on, then."),
            new Trigger("continue", "Once more unto the task."));
    private static final List<Trigger> CANCEL_TRIGGERS = List.of(
            new Trigger("enough", "Then my burden is lifted."),
            new Trigger("dismiss", "As you wish."),
            new Trigger("stop", "Understood — stopping."));

    private static final Component NOT_OWNER_RESPONSE =
            Component.text("That errand isn't yours to call off.", NamedTextColor.GRAY);

    private final Plugin plugin;
    private final BuilderNpcService npcService;
    private final JobManager jobManager;

    public HelperCommandListener(Plugin plugin, BuilderNpcService npcService, JobManager jobManager) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.jobManager = jobManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        NPC npc = npcService.matchHelper(message);
        if (npc == null) {
            return;
        }
        String npcName = BuilderNpcService.baseNameOf(npc);
        String rest = message.substring(npcName.length()).trim().toLowerCase();

        Trigger pause = matchTrigger(PAUSE_TRIGGERS, rest);
        Trigger resume = pause == null ? matchTrigger(RESUME_TRIGGERS, rest) : null;
        Trigger cancel = (pause == null && resume == null) ? matchTrigger(CANCEL_TRIGGERS, rest) : null;
        if (pause == null && resume == null && cancel == null) {
            return;
        }

        int npcId = npc.getId();
        // Existence-only check — safe off the main thread since the registry is a
        // ConcurrentHashMap. Not a command for this Helper right now, so let the
        // message through as ordinary chat rather than eating it for nothing.
        if (jobManager.find(npcId) == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID requester = player.getUniqueId();

        Bukkit.getScheduler().runTask(plugin, () -> {
            Outcome outcome;
            String response;
            if (pause != null) {
                outcome = jobManager.pause(npcId, requester);
                response = pause.response();
            } else if (resume != null) {
                outcome = jobManager.resume(npcId, requester);
                response = resume.response();
            } else {
                outcome = jobManager.cancel(npcId, requester);
                response = cancel.response();
            }

            if (outcome == Outcome.NOT_OWNER) {
                player.sendMessage(NOT_OWNER_RESPONSE);
            } else if (outcome == Outcome.OK) {
                player.sendMessage(Component.text(npcName + ": " + response, NamedTextColor.YELLOW));
            }
            // ALREADY_IN_THAT_STATE here means the job ended (or changed state) in the
            // gap between the async check and this running — rare, and not worth a message.
        });
    }

    private Trigger matchTrigger(List<Trigger> triggers, String rest) {
        for (Trigger trigger : triggers) {
            if (rest.equals(trigger.word())) {
                return trigger;
            }
        }
        return null;
    }
}

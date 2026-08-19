package com.houseofel.builder.npc;

import com.houseofel.builder.choice.MilestoneChoiceOption;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceRegistry;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.death.ScarChoice;
import com.houseofel.builder.death.ScarRecord;
import com.houseofel.builder.gui.HelperTitleFormatter;
import com.houseofel.builder.toil.LevelCurve;
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
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * "&lt;Helper's name&gt; report" reads back its level, specialization, and banked Toil —
 * read-only, and works whether or not the Helper currently has an active job (unlike
 * {@link HelperCommandListener}'s pause/resume/cancel words, which only fire against a
 * running job). Built as the Phase 1-F chassis's verification tool: confirming a ticket
 * actually banked Toil otherwise means grinding 512 real blocks per check, or reading the
 * SQLite file by hand. Deliberately read-only — the framework's Rule 14 forbids any
 * Toil-injection path, so there's no "set/grant" counterpart, on purpose.
 */
public final class HelperStatusListener implements Listener {

    private static final String TRIGGER_WORD = "report";

    private final Plugin plugin;
    private final BuilderNpcService npcService;
    private final HelperLevelService levelService;
    private final DeathRecordStore deathRecordStore;
    private final MilestoneChoiceStore choiceStore;

    public HelperStatusListener(Plugin plugin, BuilderNpcService npcService, HelperLevelService levelService,
                                 DeathRecordStore deathRecordStore, MilestoneChoiceStore choiceStore) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.levelService = levelService;
        this.deathRecordStore = deathRecordStore;
        this.choiceStore = choiceStore;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        NPC npc = npcService.matchHelper(message);
        if (npc == null) {
            return;
        }
        String rest = message.substring(BuilderNpcService.baseNameOf(npc).length()).trim().toLowerCase();
        if (!rest.equals(TRIGGER_WORD)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Specialization specialization = levelService.specializationOf(npc);
            String specLabel = specialization == null ? "unassigned" : specialization.label();
            int level = levelService.levelOf(npc);
            int toil = levelService.bankedToilOf(npc);
            // Duty cycle and error rate are reported explicitly because they're otherwise
            // invisible — the framework calls that its single largest failure risk ("an
            // invisible improvement is a wasted level"). Until the per-spec ladders give
            // them somewhere better to live, this line is where they're legible.
            int dutyPercent = (int) Math.round(levelService.dutyCycleOf(npc) * 100);
            String errorPercent = String.format("%.1f", levelService.errorRateOf(npc) * 100);
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Level " + level + " " + specLabel
                            + ", " + toil + " Toil banked. Working " + dutyPercent
                            + "% of the time, slipping on " + errorPercent + "% of blocks.",
                    NamedTextColor.AQUA));

            List<ScarRecord> scars = deathRecordStore.scarsFor(npc.getUniqueId());
            String scarChoiceSuffix = "";
            ScarChoice choice = deathRecordStore.scarChoiceOf(npc.getUniqueId());
            if (choice != null) {
                scarChoiceSuffix = " — " + choice.name();
            }
            player.sendMessage(Component.text(
                    (scars.isEmpty() ? "No scars yet." : "Scars: " + scars.size() + " — " + scarSummary(scars))
                            + scarChoiceSuffix,
                    NamedTextColor.DARK_GRAY));

            // Same shared line the dispatch menu uses — see HelperTitleFormatter.rustLineFor.
            // Replaces the always-on world-space boss bar removed 2026-08-19 (Kyle's call).
            String rustLine = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);
            if (rustLine != null) {
                player.sendMessage(Component.text(rustLine, NamedTextColor.GOLD));
            }

            // Groundworker's level-3 verb, "Clears Anything" — keeps the capability
            // discoverable on demand, not just as a one-time announcement at level-up.
            if (specialization == Specialization.GROUNDWORKER && level >= 3) {
                player.sendMessage(Component.text(
                        "Can clear mixed regions — pick Anything as the target to skip material-by-material jobs.",
                        NamedTextColor.GRAY));
            }
            // Groundworker's level-6 verb, "Bulkhead" — same discoverability pattern.
            if (specialization == Specialization.GROUNDWORKER && level >= 6) {
                player.sendMessage(Component.text(
                        "Handles flooding — sponges up a water breach or plugs a lava one, waits it out, then keeps clearing.",
                        NamedTextColor.GRAY));
            }
            // Any reached Choice-slot level: either the pending offer (not yet decided —
            // matches the picker's own "right-click to choose" framing) or the current
            // pick, same discoverability pattern as the two blocks above.
            for (int choiceLevel = 1; choiceLevel <= level; choiceLevel++) {
                if (!LevelCurve.isChoiceLevel(choiceLevel)) {
                    continue;
                }
                List<MilestoneChoiceOption> options = MilestoneChoiceRegistry.optionsFor(specialization, choiceLevel);
                if (options.isEmpty()) {
                    continue;
                }
                MilestoneChoiceRecord record = choiceStore.find(npc.getUniqueId(), choiceLevel);
                if (record == null) {
                    String names = options.stream().map(MilestoneChoiceOption::label).collect(Collectors.joining(" or "));
                    player.sendMessage(Component.text(
                            "Ready to choose a path — " + names + ". Right-click to decide.", NamedTextColor.GRAY));
                } else {
                    player.sendMessage(Component.text(
                            "Chosen path: " + record.choice() + ".", NamedTextColor.GRAY));
                }
            }
        });
    }

    private static String scarSummary(List<ScarRecord> scars) {
        ScarRecord latest = scars.get(scars.size() - 1);
        String cause = latest.cause().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return "latest was " + cause + " at " + latest.world() + " " + latest.x() + "," + latest.y() + "," + latest.z() + ".";
    }
}

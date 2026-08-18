package com.houseofel.builder.choice;

import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.npc.BuilderNpcService;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Applies a picker-GUI button click — shared by {@code MilestoneChoiceDialog} (Java) and
 * {@code MilestoneChoiceForm} (Bedrock) so the guard chain and messaging live in exactly
 * one place. Mirrors {@code HelperDeathListener.applyScarChoice}'s guard shape (not-owner
 * → domain checks → apply + confirm), extended with the first-pick-is-free / respec-costs
 * distinction this milestone chassis needs that WARY/HARDENED (one-shot, never revisited)
 * didn't.
 */
public final class MilestoneChoiceService {

    private static final int RESPEC_COST = 200;
    private static final long RESPEC_COOLDOWN_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final DeathRecordStore deathRecordStore;
    private final MilestoneChoiceStore choiceStore;

    public MilestoneChoiceService(DeathRecordStore deathRecordStore, MilestoneChoiceStore choiceStore) {
        this.deathRecordStore = deathRecordStore;
        this.choiceStore = choiceStore;
    }

    public void attempt(Player player, NPC npc, int level, MilestoneChoiceOption chosen) {
        UUID npcUuid = npc.getUniqueId();
        String name = BuilderNpcService.baseNameOf(npc);

        UUID ownerUuid = deathRecordStore.ownerOf(npcUuid);
        if (ownerUuid == null || !ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("That's not your call to make for " + name + ".", NamedTextColor.RED));
            return;
        }

        MilestoneChoiceRecord existing = choiceStore.find(npcUuid, level);
        long now = System.currentTimeMillis();

        if (existing == null) {
            choiceStore.save(new MilestoneChoiceRecord(npcUuid, level, chosen.storedValue(), now));
            player.sendMessage(Component.text(
                    name + " will take the " + chosen.label() + " path.", NamedTextColor.AQUA));
            return;
        }

        if (existing.choice().equals(chosen.storedValue())) {
            player.sendMessage(Component.text(
                    name + " is already on the " + chosen.label() + " path.", NamedTextColor.GRAY));
            return;
        }

        long elapsed = now - existing.chosenAtMillis();
        if (elapsed < RESPEC_COOLDOWN_MILLIS) {
            long daysLeft = ((RESPEC_COOLDOWN_MILLIS - elapsed) / (24L * 60 * 60 * 1000)) + 1;
            player.sendMessage(Component.text(
                    name + " can't switch paths again for " + daysLeft + " more day(s).", NamedTextColor.RED));
            return;
        }

        if (!choiceStore.trySpendToil(npcUuid, RESPEC_COST)) {
            int shortfall = RESPEC_COST - choiceStore.spendableToilOf(npcUuid);
            player.sendMessage(Component.text(
                    name + " needs " + shortfall + " more Toil to switch paths.", NamedTextColor.RED));
            return;
        }

        choiceStore.save(new MilestoneChoiceRecord(npcUuid, level, chosen.storedValue(), now));
        player.sendMessage(Component.text(
                name + " will take the " + chosen.label() + " path instead.", NamedTextColor.GOLD));
    }
}

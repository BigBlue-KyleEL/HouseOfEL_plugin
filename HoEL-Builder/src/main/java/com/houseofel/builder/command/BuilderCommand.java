package com.houseofel.builder.command;

import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.BusyMenuLayout;
import com.houseofel.builder.gui.PanelTestLayout;
import com.houseofel.builder.gui.MainMenuLayout;
import com.houseofel.builder.gui.JobWizardHandler;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.SpecializationDialog;
import com.houseofel.builder.npc.SpecializationForm;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.core.gui.ScreenService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class BuilderCommand implements TabExecutor {

    private static final String SPAWN_PERMISSION = "houseofel.builder.spawn";

    private final SpecializationDialog specializationDialog;
    private final SpecializationForm specializationForm;
    private final RegionSelectionService regionService;
    private final HelperLevelService levelService;
    private final ScreenService screenService;
    private final JobWizardHandler wizardHandler;
    private final JobManager jobManager;
    private static final String TEST_MENU_PERMISSION = "houseofel.builder.testmenu";
    private static final List<String> MENU_SPECIALIZATIONS = java.util.stream.Stream.concat(
            Arrays.stream(Specialization.values()).map(s -> s.name().toLowerCase(Locale.ROOT)),
            java.util.stream.Stream.of("quarryman", "landscaper", "cofferdam", "shaft_miner", "pathfinder", "terraformer"))
            .distinct().toList();
    private static final List<String> MENU_TIERS = List.of("t1", "t2", "t3", "t4");

    public BuilderCommand(SpecializationDialog specializationDialog, SpecializationForm specializationForm,
                           RegionSelectionService regionService, HelperLevelService levelService,
                           ScreenService screenService, JobWizardHandler wizardHandler, JobManager jobManager) {
        this.specializationDialog = specializationDialog;
        this.specializationForm = specializationForm;
        this.regionService = regionService;
        this.levelService = levelService;
        this.screenService = screenService;
        this.wizardHandler = wizardHandler;
        this.jobManager = jobManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && "testmenu".equalsIgnoreCase(args[0])) {
            return testMenu(sender, args);
        }
        if (args.length > 0 && "testbusy".equalsIgnoreCase(args[0])) {
            return testBusy(sender, args);
        }
        if (args.length >= 3 && "setlevel".equalsIgnoreCase(args[0])) {
            return setLevel(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("Usage: /builder spawn|confirm|cancel");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> spawn(player);
            case "confirm" -> regionService.confirmPending(player);
            case "cancel" -> regionService.cancelPending(player);
            case "testpanel" -> screenService.openScreen(player, PanelTestLayout.create());
            default -> player.sendMessage("Usage: /builder spawn|confirm|cancel");
        }
        return true;
    }

    /** Preview only: does not create jobs or change the concurrency ceiling. */
    private boolean testBusy(CommandSender sender, String[] args) {
        if (sender instanceof Player && !sender.hasPermission("houseofel.builder.testbusy")) {
            sender.sendMessage("You don't have permission to preview the busy menu.");
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("Usage: /builder testbusy <npcId> [player]");
            return true;
        }
        Player player = args.length == 3 ? org.bukkit.Bukkit.getPlayerExact(args[2])
                : sender instanceof Player p ? p : null;
        if (player == null) {
            sender.sendMessage("Specify an online player: /builder testbusy <npcId> <player>");
            return true;
        }
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(Integer.parseInt(args[1]));
            if (npc == null) {
                sender.sendMessage("No NPC with ID " + args[1]);
                return true;
            }
            screenService.openScreen(player, BusyMenuLayout.create(npc, "2 minutes"));
            sender.sendMessage("Busy-menu preview opened; ETA is sample data (2 minutes).");
        } catch (NumberFormatException e) {
            sender.sendMessage("NPC ID must be a whole number.");
        }
        return true;
    }
    /** Preview strings need not correspond to an implemented specialization or earned choice. */
    private boolean testMenu(CommandSender sender, String[] args) {
        if (sender instanceof Player && !sender.hasPermission(TEST_MENU_PERMISSION)) {
            sender.sendMessage("You don't have permission to preview the main menu.");
            return true;
        }
        if (args.length < 2 || args.length > 5) {
            sender.sendMessage("Usage: /builder testmenu <npcId> [specialization] [tier] [player]");
            return true;
        }
        String tier = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "t1";
        if (!MENU_TIERS.contains(tier)) {
            sender.sendMessage("Tier must be t1, t2, t3, or t4.");
            return true;
        }
        Player player = args.length == 5 ? org.bukkit.Bukkit.getPlayerExact(args[4])
                : sender instanceof Player p ? p : null;
        if (player == null) {
            sender.sendMessage("Specify an online player: /builder testmenu <npcId> <specialization> <tier> <player>");
            return true;
        }
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(Integer.parseInt(args[1]));
            if (npc == null) {
                sender.sendMessage("No NPC with ID " + args[1]);
                return true;
            }
            Specialization actual = levelService.specializationOf(npc);
            boolean override = args.length >= 3;
            String specialization = override ? args[2].toLowerCase(Locale.ROOT)
                    : actual == null ? "groundworker" : actual.name().toLowerCase(Locale.ROOT);
            String texture = "houseofel:gui/class_" + specialization + "_" + tier;
            String title = override ? Arrays.stream(specialization.split("_"))
                    .filter(word -> !word.isEmpty())
                    .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
                    .collect(Collectors.joining(" ")) : null;
            Integer displayLevel = override ? switch (tier) {
                case "t2" -> 8;
                case "t3" -> 16;
                case "t4" -> 20;
                default -> 1;
            } : null;
            List<String> tags = override && (tier.equals("t3") || tier.equals("t4"))
                    ? List.of(specialization.toUpperCase(Locale.ROOT).replace('_', ' ')) : null;
            wizardHandler.openMainMenuPreview(player, npc, jobManager,
                    new MainMenuLayout.Preview(texture, title, displayLevel, tags));
            sender.sendMessage("Main-menu preview opened for " + player.getName() + ": " + texture
                    + ". NPC data is unchanged.");
        } catch (NumberFormatException e) {
            sender.sendMessage("NPC ID must be a whole number.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean canPreview = !(sender instanceof Player) || sender.hasPermission(TEST_MENU_PERMISSION);
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("spawn", "confirm", "cancel", "setlevel", "testpanel"));
            if (canPreview) options.add("testmenu");
            if (!(sender instanceof Player) || sender.hasPermission("houseofel.builder.testbusy")) options.add("testbusy");
            return matching(options, args[0]);
        }
        if (args.length > 1 && "testmenu".equalsIgnoreCase(args[0])) {
            if (!canPreview) return List.of();
            return switch (args.length) {
                case 2 -> {
                    List<String> ids = new ArrayList<>();
                    for (NPC npc : CitizensAPI.getNPCRegistry()) ids.add(Integer.toString(npc.getId()));
                    yield matching(ids, args[1]);
                }
                case 3 -> matching(MENU_SPECIALIZATIONS, args[2]);
                case 4 -> matching(MENU_TIERS, args[3]);
                case 5 -> matching(org.bukkit.Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[4]);
                default -> List.of();
            };
        }
        return null; // Preserve Bukkit's existing completion fallback for other commands.
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private void spawn(Player player) {
        if (!player.hasPermission(SPAWN_PERMISSION)) {
            player.sendMessage("You don't have permission to spawn a Helper NPC.");
            return;
        }
        if (BedrockJobForm.isBedrockPlayer(player)) {
            specializationForm.open(player, player.getLocation());
        } else {
            specializationDialog.open(player, player.getLocation());
        }
    }

    private boolean setLevel(CommandSender sender, String[] args) {
        try {
            int npcId = Integer.parseInt(args[1]);
            int targetLevel = Integer.parseInt(args[2]);
            NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
            if (npc == null) {
                sender.sendMessage("No NPC with ID " + npcId);
                return true;
            }
            levelService.setLevel(npc, targetLevel);
            sender.sendMessage(BuilderNpcService.baseNameOf(npc) + " (#" + npcId + ") set to level " + targetLevel);
        } catch (NumberFormatException e) {
            sender.sendMessage("Usage: /builder setlevel <npcId> <level>");
        }
        return true;
    }
}

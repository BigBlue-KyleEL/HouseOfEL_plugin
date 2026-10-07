package com.houseofel.builder.gui;

import com.houseofel.common.net.DispatchValue;
import com.houseofel.core.gui.GuiCapabilityService;
import com.houseofel.core.gui.ScreenDispatchHandler;
import com.houseofel.core.gui.ScreenService;
import com.houseofel.core.gui.UiPath;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** One-use server-held choices; client values never identify an NPC, level or outcome. */
public final class ForkScreenHandler implements ScreenDispatchHandler, Listener {
    private static final String PREFIX = "helper_fork:";
    private record Session(String screenId, Map<String, Consumer<Map<String, DispatchValue>>> actions) {}
    private final ScreenService screens;
    private final Function<Player, UiPath> uiPath;
    private final Consumer<Runnable> mainThread;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ForkScreenHandler(Plugin plugin, ScreenService screens, GuiCapabilityService capabilities) {
        this(screens, capabilities::getUiPath, task -> Bukkit.getScheduler().runTask(plugin, task));
    }

    ForkScreenHandler(ScreenService screens, Function<Player, UiPath> uiPath, Consumer<Runnable> mainThread) {
        this.screens = screens;
        this.uiPath = uiPath;
        this.mainThread = mainThread;
    }

    public static boolean handles(String screenId) { return screenId.startsWith(PREFIX); }

    /** False leaves the caller's existing Java dialog fallback in control. */
    public boolean open(Player player, String title, List<ForkScreenLayout.Option> options,
                        Map<String, Runnable> actions) {
        if (uiPath.apply(player) != UiPath.MOD) return false;
        if (!actions.keySet().equals(options.stream().map(ForkScreenLayout.Option::action)
                .collect(java.util.stream.Collectors.toSet())) || actions.containsKey("cancel")) {
            throw new IllegalArgumentException("Fork buttons must match the server's offered actions");
        }
        String id = PREFIX + UUID.randomUUID();
        Map<String, Consumer<Map<String, DispatchValue>>> callbacks = new HashMap<>();
        actions.forEach((key,run) -> callbacks.put(key, values -> run.run()));
        sessions.put(player.getUniqueId(), new Session(id, Map.copyOf(callbacks)));
        screens.openScreen(player, ForkScreenLayout.create(id, title, options));
        return true;
    }

    public boolean openName(Player player, String initial, String error, Consumer<String> submit) {
        if (uiPath.apply(player) != UiPath.MOD) return false;
        String id = PREFIX + UUID.randomUUID();
        sessions.put(player.getUniqueId(), new Session(id, Map.of("recruit", values -> {
            String name = values.get("name") instanceof DispatchValue.StringVal text ? text.value() : null;
            submit.accept(name);
        })));
        screens.openScreen(player, ForkScreenLayout.nameScreen(id, initial, error));
        return true;
    }

    @Override public void onDispatch(Player player, String screenId, String action, Map<String, DispatchValue> values) {
        mainThread.accept(() -> {
            Session session = sessions.get(player.getUniqueId());
            if (!player.isOnline() || uiPath.apply(player) != UiPath.MOD || session == null
                    || !session.screenId().equals(screenId) || !screenId.equals(screens.getOpenScreen(player))) return;
            Consumer<Map<String, DispatchValue>> pick = session.actions().get(action);
            if (pick == null && !"cancel".equals(action)) return;
            // Consume before invoking domain logic: repeated packets cannot charge/spawn twice.
            sessions.remove(player.getUniqueId());
            screens.closeScreen(player, screenId);
            if (pick != null) pick.accept(values);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}

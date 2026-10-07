package com.houseofel.core.gui;

import org.bukkit.entity.Player;

import com.houseofel.common.net.DispatchValue;

import java.util.Map;

@FunctionalInterface
public interface ScreenDispatchHandler {
    void onDispatch(Player player, String screenId, String action, Map<String, DispatchValue> values);
}

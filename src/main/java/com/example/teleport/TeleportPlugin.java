package com.example.teleport;

import org.bukkit.plugin.java.JavaPlugin;

public final class TeleportPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("TeleportPluginが有効化されました！");
    }

    @Override
    public void onDisable() {
        getLogger().info("TeleportPluginが無効化されました。");
    }
}

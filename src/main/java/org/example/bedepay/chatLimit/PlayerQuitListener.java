package org.example.bedepay.chatLimit;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

public class PlayerQuitListener implements Listener {
    private final ChatLimit plugin;
    
    public PlayerQuitListener(ChatLimit plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        BukkitTask task = plugin.getCheckTasks().remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            plugin.getLogger().info("Задача проверки для игрока " + player.getName() + " отменена");
        }
    }
} 
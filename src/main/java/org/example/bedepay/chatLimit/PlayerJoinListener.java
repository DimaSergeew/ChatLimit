package org.example.bedepay.chatLimit;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ChatLimit plugin = ChatLimit.getInstance();
        
        if (!player.hasPermission("chatlimit.bypass")) {
            int playTimeMinutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / (20 * 60);
            int requiredTime = plugin.getConfigTimeLimit();
            
            if (playTimeMinutes >= requiredTime) {
                if (!plugin.isPlayerVerified(player.getUniqueId())) {
                    plugin.addVerifiedPlayer(player, playTimeMinutes);
                }
                return;
            }
            
            if (plugin.isPlayerVerified(player.getUniqueId())) {
                return;
            }
        }
    }
} 
package org.example.bedepay.chatLimit;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ChatListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatLimit plugin = ChatLimit.getInstance();
        
        if (!player.hasPermission("chatlimit.bypass") && 
            (plugin.isNewPlayer(player.getUniqueId()) || !plugin.isPlayerVerified(player.getUniqueId()))) {
                
            event.setCancelled(true);
            
            int playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            int playTimeMinutes = playTimeTicks / (20 * 60);
            int remainingMinutes = ChatLimit.getInstance().getConfigTimeLimit() - playTimeMinutes;
            
            String message = ChatLimit.getInstance().getConfig()
                .getString("messages.chat_limit", "&cВы сможете писать в чат через %time% минут игры!")
                .replace("&c", "")
                .replace("%time%", String.valueOf(remainingMinutes));
                
            player.sendMessage(Component.text(message, NamedTextColor.RED));
            return;
        }
    }
}
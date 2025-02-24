package org.example.bedepay.chatLimit;

import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class CommandBlocker implements Listener {
    private final ChatLimit plugin = ChatLimit.getInstance();
    private Set<String> blockedCommandsSet;

    public CommandBlocker() {
        reloadBlockedCommands();
    }

    public final void reloadBlockedCommands() {
        blockedCommandsSet = plugin.getConfig().getStringList("blocked_commands")
            .stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().toLowerCase();
        
        // Проверяем, начинается ли команда с любой из заблокированных команд
        boolean isBlocked = blockedCommandsSet.stream()
            .anyMatch(cmd -> {
                String cmdLower = cmd.toLowerCase();
                return command.startsWith(cmdLower + " ") || command.equals(cmdLower);
            });
        
        if (!player.hasPermission("chatlimit.bypass") && 
            (ChatLimit.getInstance().isNewPlayer(player.getUniqueId()) || 
             !ChatLimit.getInstance().isPlayerVerified(player.getUniqueId())) &&
            isBlocked) {
                
            event.setCancelled(true);
            
            int playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            int playTimeMinutes = playTimeTicks / (20 * 60);
            int remainingMinutes = ChatLimit.getInstance().getConfigTimeLimit() - playTimeMinutes;
            
            String message = plugin.getConfig()
                .getString("messages.command_blocked", "&cВы сможете использовать команды через %time% минут игры!")
                .replace("&c", "")
                .replace("%time%", String.valueOf(remainingMinutes));
                
            player.sendMessage(Component.text(message, NamedTextColor.RED));
            return;
        }
    }
}
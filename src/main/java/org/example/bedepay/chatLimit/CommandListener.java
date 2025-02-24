package org.example.bedepay.chatLimit;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class CommandListener implements CommandExecutor, TabCompleter, Listener {

    private final JavaPlugin plugin = ChatLimit.getInstance();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("chatlimit")) {
            return false;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Использование: /chatlimit reload", NamedTextColor.RED));
            return true;
        }

        if (!sender.hasPermission("chatlimit.reload")) {
            sender.sendMessage(Component.text("У вас нет прав для использования этой команды!", NamedTextColor.RED));
            return true;
        }

        // Перезагружаем конфигурацию плагина
        plugin.reloadConfig();
        ((ChatLimit)plugin).reloadTimeLimit();
        ChatLimit.getInstance().getCommandBlocker().reloadBlockedCommands();
        
        String configMessage = plugin.getConfig().getString("messages.reload", "&aКонфигурация успешно перезагружена.");
        sender.sendMessage(Component.text(configMessage.replace("&a", "")).color(NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1 && sender.hasPermission("chatlimit.reload")) {
            if ("reload".startsWith(args[0].toLowerCase())) {
                completions.add("reload");
            }
        }
        
        return completions;
    }
}
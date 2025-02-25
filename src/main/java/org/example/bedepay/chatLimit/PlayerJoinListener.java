package org.example.bedepay.chatLimit;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

public class PlayerJoinListener implements Listener {
    private final ChatLimit plugin;
    
    public PlayerJoinListener(ChatLimit plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Логируем вход игрока
        plugin.getLogger().info("Игрок " + player.getName() + " зашёл на сервер");

        if (player.hasPermission("chatlimit.bypass")) {
            plugin.getLogger().info("Игрок " + player.getName() + " имеет право обхода");
            return;
        }

        // Проверяем, есть ли игрок в базе
        if (plugin.isPlayerVerified(uuid)) {
            plugin.getLogger().info("Игрок " + player.getName() + " уже верифицирован");
            return;
        }

        // Получаем текущее время игры
        int rawTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long playTimeMinutes = rawTicks / (20 * 60);
        
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Игрок " + player.getName() + " отыграл: " + playTimeMinutes + " минут");
            plugin.getLogger().info("Требуется: " + plugin.getConfigTimeLimit() + " минут");
        }

        // Проверяем, достаточно ли времени
        if (playTimeMinutes >= plugin.getConfigTimeLimit()) {
            plugin.getLogger().info("Игрок " + player.getName() + " набрал нужное время!");
            plugin.addVerifiedPlayer(player, playTimeMinutes);
            
            String message = plugin.getConfig().getString("messages.verification_success", 
                "Поздравляем! Вы теперь можете писать в чат!");
            player.sendMessage("§a" + message);
        } else {
            long remaining = plugin.getConfigTimeLimit() - playTimeMinutes;
            
            String message = plugin.getConfig().getString("messages.time_remaining", 
                "Осталось играть: %time% минут").replace("%time%", String.valueOf(remaining));
            player.sendMessage("§e" + message);
            
            startTimeCheck(player);
        }
    }

    private void startTimeCheck(Player player) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) return;
            
            UUID uuid = player.getUniqueId();
            
            if (plugin.isPlayerVerified(uuid)) {
                plugin.getLogger().info("Игрок " + player.getName() + " уже верифицирован");
                return;
            }

            // Проверяем текущее время
            int currentTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            long currentMinutes = currentTicks / (20 * 60);
            
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Проверка времени для " + player.getName() + 
                                      ": " + currentMinutes + "/" + plugin.getConfigTimeLimit());
            }

            // Прямое сравнение с требуемым временем
            if (currentMinutes >= plugin.getConfigTimeLimit()) {
                plugin.addVerifiedPlayer(player, currentMinutes);
                
                String successMessage = plugin.getConfig().getString("messages.verification_success", 
                    "Поздравляем! Вы теперь можете писать в чат!");
                player.sendMessage("§a" + successMessage);
                
                plugin.getLogger().info("Игрок " + player.getName() + " автоматически верифицирован!");
                
                // Отменяем задачу проверки
                plugin.getCheckTasks().remove(player.getUniqueId()).cancel();
            }
        }, 20L * 30L, 20L * 30L); // Проверка каждые 30 секунд
        
        // Сохраняем задачу для отмены при выходе
        plugin.getCheckTasks().put(player.getUniqueId(), task);
    }
} 
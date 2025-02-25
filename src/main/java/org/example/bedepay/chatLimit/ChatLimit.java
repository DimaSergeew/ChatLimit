package org.example.bedepay.chatLimit;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.example.bedepay.chatLimit.database.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ChatLimit extends JavaPlugin {

    private static ChatLimit instance;
    private int timeLimit;
    private int configTimeLimit;
    private final Set<UUID> newPlayers = new HashSet<>();
    private BukkitTask checkTask;
    private CommandBlocker commandBlocker;
    private DatabaseManager databaseManager;
    private final Map<UUID, BukkitTask> checkTasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Сохраняем экземпляр плагина
        instance = this;

        // Загружаем конфиг
        saveDefaultConfig();
        
        // Инициализируем менеджер базы данных
        databaseManager = new DatabaseManager(this);

        // Загружаем значение из конфига и сохраняем оригинальное значение
        configTimeLimit = getConfig().getInt("time-limit", 30);
        timeLimit = configTimeLimit * 60; // конвертируем минуты в секунды

        // Создаем экземпляры слушателей
        commandBlocker = new CommandBlocker();
        
        // Регистрируем слушатели
        getServer().getPluginManager().registerEvents(new ChatListener(), this);
        getServer().getPluginManager().registerEvents(commandBlocker, this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        
        // Регистрируем команду
        var chatlimitCommand = getCommand("chatlimit");
        if (chatlimitCommand == null) {
            getLogger().warning("Command 'chatlimit' not found in plugin.yml!");
        } else {
            CommandListener commandListener = new CommandListener();
            chatlimitCommand.setExecutor(commandListener);
            chatlimitCommand.setTabCompleter(commandListener);
        }

        // Запускаем периодическую проверку каждые 30 секунд
        startCheckTask();

        // Логируем запуск плагина
        getLogger().info("ChatLimit Plugin has been enabled.");
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        
        // Отменяем все задачи проверки
        for (BukkitTask task : checkTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        checkTasks.clear();
        newPlayers.clear();
        
        // Закрываем соединение с БД
        if (databaseManager != null) {
            databaseManager.closeConnection();
        }
        
        // Логируем остановку плагина
        getLogger().info("ChatLimit Plugin has been disabled.");
    }

    public static ChatLimit getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Plugin not initialized!");
        }
        return instance;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public boolean isNewPlayer(UUID playerUUID) {
        return newPlayers.contains(playerUUID);
    }

    public void addNewPlayer(UUID playerUUID) {
        newPlayers.add(playerUUID);
    }

    private void startCheckTask() {
        checkTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (UUID playerUUID : new HashSet<>(newPlayers)) {
                Player player = getServer().getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    int playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
                    int playTimeMinutes = playTimeTicks / (20 * 60);
                    
                    if (playTimeMinutes >= configTimeLimit) {
                        getServer().getScheduler().runTask(this, () -> {
                            newPlayers.remove(playerUUID);
                            addVerifiedPlayer(player, playTimeMinutes); // Сохраняем в БД
                            player.sendMessage(Component.text("Поздравляем! Теперь вы можете использовать чат и команды!", NamedTextColor.GREEN));
                        });
                    }
                }
            }
        }, 20L, 20L);
    }

    public void reloadTimeLimit() {
        configTimeLimit = getConfig().getInt("time-limit", 30);
        timeLimit = configTimeLimit * 60;
        
        // Если менеджер БД уже существует, пересоздаем его с новыми настройками
        if (databaseManager != null) {
            databaseManager.closeConnection();
            databaseManager = new DatabaseManager(this);
        }
    }

    public CommandBlocker getCommandBlocker() {
        return commandBlocker;
    }

    public int getConfigTimeLimit() {
        return configTimeLimit;
    }

    public boolean isPlayerVerified(UUID uuid) {
        return databaseManager.isPlayerVerified(uuid);
    }

    public void addVerifiedPlayer(Player player, long playTime) {
        databaseManager.addVerifiedPlayer(player, playTime);
    }

    public Map<UUID, BukkitTask> getCheckTasks() {
        return checkTasks;
    }
}

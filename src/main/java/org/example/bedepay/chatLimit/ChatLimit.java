package org.example.bedepay.chatLimit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ChatLimit extends JavaPlugin {

    private static ChatLimit instance;
    private int timeLimit;
    private int configTimeLimit;
    private final Set<UUID> newPlayers = new HashSet<>();
    private BukkitTask checkTask;
    private CommandBlocker commandBlocker;
    private Connection connection;

    @Override
    public void onEnable() {
        // Сохраняем экземпляр плагина
        instance = this;

        // Загружаем конфиг
        saveDefaultConfig();

        // Загружаем значение из конфига и сохраняем оригинальное значение
        configTimeLimit = getConfig().getInt("time_limit", 30);
        timeLimit = configTimeLimit * 60; // конвертируем минуты в секунды

        // Создаем экземпляры слушателей
        commandBlocker = new CommandBlocker();
        
        // Регистрируем слушатели
        getServer().getPluginManager().registerEvents(new ChatListener(), this);
        getServer().getPluginManager().registerEvents(commandBlocker, this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
        
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

        // Инициализация базы данных
        initializeDatabase();

        // Логируем запуск плагина
        getLogger().info("ChatLimit Plugin has been enabled.");
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        newPlayers.clear();
        // Закрываем соединение с БД
        closeDatabase();
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
        configTimeLimit = getConfig().getInt("time_limit", 30);
        timeLimit = configTimeLimit * 60;
    }

    public CommandBlocker getCommandBlocker() {
        return commandBlocker;
    }

    public int getConfigTimeLimit() {
        return configTimeLimit;
    }

    private void initializeDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            String dbPath = getDataFolder().getAbsolutePath() + "/players.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            
            // Включаем журналирование WAL для лучшей производительности
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }

            try (PreparedStatement stmt = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS verified_players (" +
                "uuid TEXT PRIMARY KEY, " + 
                "username TEXT NOT NULL, " +
                "play_time INTEGER NOT NULL, " +
                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)")) {
                stmt.executeUpdate();
            }
            getLogger().info("База данных успешно инициализирована: " + dbPath);
        } catch (Exception e) {
            getLogger().severe("Критическая ошибка инициализации БД: " + e.getMessage());
            // Останавливаем плагин при проблемах с БД
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void closeDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().severe("Ошибка закрытия соединения с БД: " + e.getMessage());
        }
    }

    public boolean isPlayerVerified(UUID uuid) {
        checkConnection();
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT 1 FROM verified_players WHERE uuid = ? AND play_time >= ?")) {
            
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, configTimeLimit);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            getLogger().warning("Ошибка проверки игрока: " + e.getMessage());
            return false;
        }
    }

    private void checkConnection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                getLogger().warning("Восстанавливаем соединение с БД...");
                initializeDatabase();
            }
        } catch (SQLException e) {
            getLogger().severe("Ошибка проверки соединения: " + e.getMessage());
        }
    }

    public void addVerifiedPlayer(Player player, int playTime) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            checkConnection();
            try {
                connection.setAutoCommit(false); // Начинаем транзакцию
                
                try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT OR REPLACE INTO verified_players (uuid, username, play_time) VALUES (?, ?, ?)")) {
                    
                    stmt.setString(1, player.getUniqueId().toString());
                    stmt.setString(2, player.getName());
                    stmt.setInt(3, playTime);
                    stmt.executeUpdate();
                }
                
                connection.commit(); // Фиксируем изменения
                getLogger().info("Данные игрока " + player.getName() + " успешно сохранены");
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    getLogger().severe("Ошибка отката транзакции: " + ex.getMessage());
                }
                getLogger().warning("Ошибка сохранения данных: " + e.getMessage());
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    getLogger().warning("Ошибка восстановления авто-коммита: " + e.getMessage());
                }
            }
        });
    }

}

package org.example.bedepay.chatLimit;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.example.bedepay.chatLimit.database.DatabaseManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Основной класс плагина ChatLimit.
 * Ограничивает использование чата и команд для новых игроков до достижения определенного времени игры.
 */
public final class ChatLimit extends JavaPlugin {

    private static ChatLimit instance;
    private int timeLimit;
    private int configTimeLimit;
    private final Set<UUID> newPlayers = new HashSet<>();
    private BukkitTask checkTask;
    private CommandBlocker commandBlocker;
    private DatabaseManager databaseManager;
    private final Map<UUID, BukkitTask> checkTasks = new ConcurrentHashMap<>();

    /**
     * Инициализирует плагин при его запуске.
     * Настраивает конфигурацию, базу данных и регистрирует все необходимые слушатели.
     */
    @Override
    public void onEnable() {
        try {
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
                getLogger().severe("Команда 'chatlimit' не найдена в plugin.yml!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            
            CommandListener commandListener = new CommandListener();
            chatlimitCommand.setExecutor(commandListener);
            chatlimitCommand.setTabCompleter(commandListener);

            // Запускаем периодическую проверку каждые 30 секунд
            startCheckTask();

            // Логируем запуск плагина
            getLogger().info("ChatLimit Plugin успешно запущен.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Критическая ошибка при запуске плагина", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Выполняет очистку ресурсов при отключении плагина.
     */
    @Override
    public void onDisable() {
        try {
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
            getLogger().info("ChatLimit Plugin успешно остановлен.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Ошибка при остановке плагина", e);
        }
    }

    /**
     * Возвращает единственный экземпляр плагина.
     * @return Экземпляр плагина
     * @throws IllegalStateException если плагин не инициализирован
     */
    public static ChatLimit getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Плагин не инициализирован!");
        }
        return instance;
    }

    /**
     * Возвращает временной лимит в секундах.
     * @return Временной лимит в секундах
     */
    public int getTimeLimit() {
        return timeLimit;
    }

    /**
     * Проверяет, является ли игрок новым.
     * @param playerUUID UUID игрока
     * @return true если игрок новый, false в противном случае
     */
    public boolean isNewPlayer(UUID playerUUID) {
        return newPlayers.contains(playerUUID);
    }

    /**
     * Добавляет игрока в список новых игроков.
     * @param playerUUID UUID игрока
     */
    public void addNewPlayer(UUID playerUUID) {
        newPlayers.add(playerUUID);
    }

    /**
     * Запускает периодическую проверку времени игры для новых игроков.
     */
    private void startCheckTask() {
        checkTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                for (UUID playerUUID : new HashSet<>(newPlayers)) {
                    Player player = getServer().getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        int playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
                        int playTimeMinutes = playTimeTicks / (20 * 60);
                        
                        if (playTimeMinutes >= configTimeLimit) {
                            getServer().getScheduler().runTask(this, () -> {
                                try {
                                    newPlayers.remove(playerUUID);
                                    addVerifiedPlayer(player, playTimeMinutes);
                                    player.sendMessage(Component.text("Поздравляем! Теперь вы можете использовать чат и команды!", NamedTextColor.GREEN));
                                } catch (Exception e) {
                                    getLogger().log(Level.WARNING, "Ошибка при обработке верификации игрока " + player.getName(), e);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Ошибка в задаче проверки времени", e);
            }
        }, 20L, 20L);
    }

    /**
     * Перезагружает временной лимит из конфигурации.
     */
    public void reloadTimeLimit() {
        try {
            configTimeLimit = getConfig().getInt("time-limit", 30);
            timeLimit = configTimeLimit * 60;
            
            // Если менеджер БД уже существует, пересоздаем его с новыми настройками
            if (databaseManager != null) {
                databaseManager.closeConnection();
                databaseManager = new DatabaseManager(this);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Ошибка при перезагрузке временного лимита", e);
        }
    }

    /**
     * Возвращает блокировщик команд.
     * @return Экземпляр CommandBlocker
     */
    public CommandBlocker getCommandBlocker() {
        return commandBlocker;
    }

    /**
     * Возвращает временной лимит в минутах из конфигурации.
     * @return Временной лимит в минутах
     */
    public int getConfigTimeLimit() {
        return configTimeLimit;
    }

    /**
     * Проверяет, верифицирован ли игрок.
     * @param uuid UUID игрока
     * @return true если игрок верифицирован, false в противном случае
     */
    public boolean isPlayerVerified(UUID uuid) {
        try {
            return databaseManager.isPlayerVerified(uuid);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Ошибка при проверке верификации игрока " + uuid, e);
            return false;
        }
    }

    /**
     * Добавляет или обновляет информацию о верифицированном игроке.
     * @param player Игрок
     * @param playTime Время игры в минутах
     */
    public void addVerifiedPlayer(Player player, long playTime) {
        try {
            databaseManager.addVerifiedPlayer(player, playTime);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Ошибка при сохранении верификации игрока " + player.getName(), e);
        }
    }

    /**
     * Возвращает карту задач проверки для каждого игрока.
     * @return Карта UUID игрока -> задача проверки
     */
    public Map<UUID, BukkitTask> getCheckTasks() {
        return checkTasks;
    }
}

package org.example.bedepay.chatLimit.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.example.bedepay.chatLimit.ChatLimit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Менеджер базы данных для плагина ChatLimit.
 * Обеспечивает работу с SQLite и MySQL/MariaDB базами данных.
 * Использует пул соединений HikariCP для оптимизации производительности.
 */
public class DatabaseManager {
    private final ChatLimit plugin;
    private HikariDataSource dataSource;
    private String tablePrefix = "";
    private boolean useMysql = false;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    /**
     * Создает новый экземпляр DatabaseManager.
     * @param plugin Экземпляр основного класса плагина
     */
    public DatabaseManager(ChatLimit plugin) {
        this.plugin = plugin;
        setupDatabase();
    }

    /**
     * Настраивает подключение к базе данных на основе конфигурации.
     * Поддерживает SQLite и MySQL/MariaDB.
     */
    private void setupDatabase() {
        String dbType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        useMysql = "mysql".equals(dbType);

        try {
            if (useMysql) {
                setupMysql();
            } else {
                setupSqlite();
            }
            createTables();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Критическая ошибка при инициализации базы данных", e);
            throw new RuntimeException("Не удалось инициализировать базу данных", e);
        }
    }

    /**
     * Настраивает подключение к MySQL/MariaDB.
     * @throws SQLException при ошибке подключения к базе данных
     */
    private void setupMysql() throws SQLException {
        ConfigurationSection mysqlConfig = plugin.getConfig().getConfigurationSection("database.mysql");
        if (mysqlConfig == null) {
            throw new IllegalStateException("Отсутствует секция настроек MySQL в конфиге!");
        }

        String host = mysqlConfig.getString("host", "localhost");
        int port = mysqlConfig.getInt("port", 3306);
        String database = mysqlConfig.getString("database", "chatlimit");
        String username = mysqlConfig.getString("username", "root");
        String password = mysqlConfig.getString("password", "");
        tablePrefix = mysqlConfig.getString("table_prefix", "cl_");
        int poolSize = mysqlConfig.getInt("pool_size", 5);
        int timeout = mysqlConfig.getInt("timeout", 30);
        boolean useSSL = mysqlConfig.getBoolean("useSSL", false);

        try {
            Class.forName("org.mariadb.jdbc.Driver");

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(String.format("jdbc:mariadb://%s:%d/%s", host, port, database));
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(poolSize);
            config.setConnectionTimeout(timeout * 1000L);
            config.addDataSourceProperty("useSSL", String.valueOf(useSSL));
            config.addDataSourceProperty("autoReconnect", "true");
            config.addDataSourceProperty("characterEncoding", "utf8");
            config.addDataSourceProperty("useUnicode", "true");
            config.setPoolName("ChatLimitPool");

            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("Успешное подключение к MariaDB!");

        } catch (ClassNotFoundException e) {
            throw new SQLException("Драйвер MariaDB не найден!", e);
        }
    }

    /**
     * Настраивает подключение к SQLite.
     * @throws SQLException при ошибке подключения к базе данных
     */
    private void setupSqlite() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "players.db");
            String dbPath = dbFile.getAbsolutePath();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setConnectionTestQuery("SELECT 1");
            config.setMaximumPoolSize(1);
            config.setPoolName("ChatLimitSQLitePool");

            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("Успешное подключение к SQLite!");

            // Включаем режим WAL для лучшей производительности
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }

        } catch (ClassNotFoundException e) {
            throw new SQLException("Драйвер SQLite не найден!", e);
        }
    }

    /**
     * Создает необходимые таблицы в базе данных.
     * @throws SQLException при ошибке создания таблиц
     */
    private void createTables() throws SQLException {
        String tableName = tablePrefix + "verified_players";
        String query;

        if (useMysql) {
            query = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                  + "uuid VARCHAR(36) PRIMARY KEY, "
                  + "username VARCHAR(16) NOT NULL, "
                  + "play_time BIGINT NOT NULL, "
                  + "server_id VARCHAR(64) NOT NULL, "
                  + "verified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                  + "INDEX (username), "
                  + "INDEX (server_id)"
                  + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        } else {
            query = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                  + "uuid TEXT PRIMARY KEY, "
                  + "username TEXT NOT NULL, "
                  + "play_time BIGINT NOT NULL, "
                  + "server_id TEXT NOT NULL, "
                  + "verified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                  + ")";
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(query);
            
            if (!useMysql) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_username ON " + tableName + " (username)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_server_id ON " + tableName + " (server_id)");
            }
            
            plugin.getLogger().info("Таблицы в базе данных успешно созданы");
        }
    }

    /**
     * Проверяет, верифицирован ли игрок.
     * @param uuid UUID игрока
     * @return true если игрок верифицирован, false в противном случае
     */
    public boolean isPlayerVerified(UUID uuid) {
        String tableName = tablePrefix + "verified_players";
        String query = "SELECT play_time FROM " + tableName + " WHERE uuid = ?";
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() && rs.getLong("play_time") >= plugin.getConfigTimeLimit();
                }
            } catch (SQLException e) {
                if (attempt == MAX_RETRIES - 1) {
                    plugin.getLogger().log(Level.WARNING, "Ошибка проверки игрока " + uuid, e);
                    return false;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Добавляет или обновляет информацию о верифицированном игроке.
     * @param player Игрок
     * @param playTime Время игры в минутах
     */
    public void addVerifiedPlayer(Player player, long playTime) {
        String tableName = tablePrefix + "verified_players";
        String serverId = plugin.getConfig().getString("server-id", "default");
        String query = "INSERT INTO " + tableName + " (uuid, username, play_time, server_id) VALUES (?, ?, ?, ?) "
                     + (useMysql ? "ON DUPLICATE KEY UPDATE" : "ON CONFLICT(uuid) DO UPDATE SET") 
                     + " username = ?, play_time = ?, server_id = ?";
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                conn.setAutoCommit(false);
                
                stmt.setString(1, player.getUniqueId().toString());
                stmt.setString(2, player.getName());
                stmt.setLong(3, playTime);
                stmt.setString(4, serverId);
                
                stmt.setString(5, player.getName());
                stmt.setLong(6, playTime);
                stmt.setString(7, serverId);
                
                stmt.executeUpdate();
                conn.commit();
                
                plugin.getLogger().info("Игрок " + player.getName() + " успешно добавлен в БД");
                return;
            } catch (SQLException e) {
                if (attempt == MAX_RETRIES - 1) {
                    plugin.getLogger().log(Level.WARNING, "Ошибка сохранения игрока " + player.getName(), e);
                    return;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Закрывает соединение с базой данных.
     */
    public void closeConnection() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
} 
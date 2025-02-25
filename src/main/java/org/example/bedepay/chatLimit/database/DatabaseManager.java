package org.example.bedepay.chatLimit.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.example.bedepay.chatLimit.ChatLimit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DatabaseManager {
    private final ChatLimit plugin;
    private HikariDataSource dataSource;
    private String tablePrefix = "";
    private boolean useMysql = false;

    public DatabaseManager(ChatLimit plugin) {
        this.plugin = plugin;
        setupDatabase();
    }

    private void setupDatabase() {
        String dbType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        useMysql = "mysql".equals(dbType);

        if (useMysql) {
            setupMysql();
        } else {
            setupSqlite();
        }

        // Создание таблиц
        createTables();
    }

    private void setupMysql() {
        ConfigurationSection mysqlConfig = plugin.getConfig().getConfigurationSection("database.mysql");
        if (mysqlConfig == null) {
            plugin.getLogger().severe("Отсутствует секция настроек MySQL в конфиге!");
            return;
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
            plugin.getLogger().severe("Драйвер MariaDB не найден! " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка подключения к MariaDB", e);
        }
    }

    private void setupSqlite() {
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
            config.setMaximumPoolSize(1); // SQLite поддерживает только одно соединение
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
            plugin.getLogger().severe("Драйвер SQLite не найден! " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка подключения к SQLite", e);
        }
    }

    private void createTables() {
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
            
            // Создаем индексы для SQLite
            if (!useMysql) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_username ON " + tableName + " (username)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_server_id ON " + tableName + " (server_id)");
            }
            
            plugin.getLogger().info("Таблицы в базе данных успешно созданы");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка создания таблиц", e);
        }
    }

    public boolean isPlayerVerified(UUID uuid) {
        String tableName = tablePrefix + "verified_players";
        String query = "SELECT play_time FROM " + tableName + " WHERE uuid = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, uuid.toString());
            
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getLong("play_time") >= plugin.getConfigTimeLimit();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка проверки игрока " + uuid, e);
            return false;
        }
    }

    public void addVerifiedPlayer(Player player, long playTime) {
        String tableName = tablePrefix + "verified_players";
        String serverId = plugin.getConfig().getString("server-id", "default");
        String query = "INSERT INTO " + tableName + " (uuid, username, play_time, server_id) VALUES (?, ?, ?, ?) "
                     + (useMysql ? "ON DUPLICATE KEY UPDATE" : "ON CONFLICT(uuid) DO UPDATE SET") 
                     + " username = ?, play_time = ?, server_id = ?";
        
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка сохранения игрока " + player.getName(), e);
        }
    }

    public void closeConnection() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
} 
package dev.zachdehooge.Utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class Database {
    private static final Logger logger = LoggerFactory.getLogger(Database.class);
    private static final String URL = "jdbc:sqlite:./anvil_database.db";

    private static Database instance;

    private Database() {
        initialize();
    }

    public static synchronized Database getInstance() {
        if (instance == null) instance = new Database();
        return instance;
    }

    private void initialize() {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alert_log (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    alert_id   TEXT    NOT NULL,
                    event_name TEXT    NOT NULL,
                    logged_at  TEXT    NOT NULL DEFAULT (date('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tempest_obs (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    air_temp         REAL,
                    feels_like       REAL,
                    humidity         REAL,
                    pressure         REAL,
                    logged_at        TEXT NOT NULL DEFAULT (datetime('now'))
                )
            """);
            addColumnIfMissing(stmt, "tempest_obs", "dew_point",        "REAL");
            addColumnIfMissing(stmt, "tempest_obs", "wind_avg",         "REAL");
            addColumnIfMissing(stmt, "tempest_obs", "wind_gust",        "REAL");
            addColumnIfMissing(stmt, "tempest_obs", "rain_accum_local", "REAL");
            addColumnIfMissing(stmt, "tempest_obs", "lightning_count",  "INTEGER");
        } catch (SQLException e) {
            logger.error("Failed to initialize database", e);
        }
    }

    private void addColumnIfMissing(Statement stmt, String table, String column, String type) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return;
            }
        }
        stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    public void logAlert(String alertId, String eventName) {
        String sql = "INSERT INTO alert_log (alert_id, event_name, logged_at) VALUES (?, ?, date('now'))";
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, alertId);
            ps.setString(2, eventName);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to log alert", e);
        }
    }

    public void logTempestObs(double airTemp, double feelsLike, double humidity, double pressure,
                              double dewPoint, double windAvg, double windGust, double rainAccumLocal, int lightningCount) {
        String sql = """
            INSERT INTO tempest_obs
                (air_temp, feels_like, humidity, pressure, dew_point, wind_avg, wind_gust, rain_accum_local, lightning_count, logged_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
        """;
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, airTemp);
            ps.setDouble(2, feelsLike);
            ps.setDouble(3, humidity);
            ps.setDouble(4, pressure);
            ps.setDouble(5, dewPoint);
            ps.setDouble(6, windAvg);
            ps.setDouble(7, windGust);
            ps.setDouble(8, rainAccumLocal);
            ps.setInt(9, lightningCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to log Tempest observation", e);
        }
    }

    public Map<String, Integer> getDailyAlertCounts() {
        String sql = """
            SELECT event_name, COUNT(*) AS cnt
            FROM alert_log
            WHERE logged_at = date('now')
            GROUP BY event_name
            ORDER BY cnt DESC
        """;
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("event_name"), rs.getInt("cnt"));
            }
        } catch (SQLException e) {
            logger.error("Failed to get daily alert counts", e);
        }
        return counts;
    }

    public record TempestStats(
        double minAirTemp,   double maxAirTemp,
        double minFeelsLike, double maxFeelsLike,
        double minHumidity,  double maxHumidity,
        double minPressure,  double maxPressure,
        double minDewPoint,  double maxDewPoint,
        double minWindAvg,   double maxWindGust,
        double totalRain,
        int    totalLightning,
        boolean hasData
    ) {}

    public TempestStats getDailyTempestStats() {
        String sql = """
            SELECT
                MIN(air_temp)         AS min_air,   MAX(air_temp)         AS max_air,
                MIN(feels_like)       AS min_fl,    MAX(feels_like)       AS max_fl,
                MIN(humidity)         AS min_hum,   MAX(humidity)         AS max_hum,
                MIN(pressure)         AS min_pres,  MAX(pressure)         AS max_pres,
                MIN(dew_point)        AS min_dew,   MAX(dew_point)        AS max_dew,
                MIN(wind_avg)         AS min_wind,  MAX(wind_gust)        AS max_gust,
                MAX(rain_accum_local) AS total_rain,
                SUM(lightning_count)  AS total_lightning,
                COUNT(*)              AS cnt
            FROM tempest_obs
            WHERE date(logged_at) = date('now')
        """;
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt("cnt") > 0) {
                return new TempestStats(
                    rs.getDouble("min_air"),   rs.getDouble("max_air"),
                    rs.getDouble("min_fl"),    rs.getDouble("max_fl"),
                    rs.getDouble("min_hum"),   rs.getDouble("max_hum"),
                    rs.getDouble("min_pres"),  rs.getDouble("max_pres"),
                    rs.getDouble("min_dew"),   rs.getDouble("max_dew"),
                    rs.getDouble("min_wind"),  rs.getDouble("max_gust"),
                    rs.getDouble("total_rain"),
                    rs.getInt("total_lightning"),
                    true
                );
            }
        } catch (SQLException e) {
            logger.error("Failed to get daily Tempest stats", e);
        }
        return new TempestStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }
}

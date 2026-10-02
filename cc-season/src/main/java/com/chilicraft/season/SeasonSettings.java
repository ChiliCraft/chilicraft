package com.chilicraft.season;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SeasonSettings {

    int daysPerSeason;
    int realMinutes;
    int maxCatchup;
    int mainPeriodSeconds;
    int migrationPeriod;
    int migrationRadius;
    int migrationMax;
    int migrationMinDistance;
    int migrationMaxDistance;
    int greenhouseAbove;
    boolean followWorld;
    boolean advanceSleep;
    boolean requirePlayers;
    boolean weatherEnabled;
    boolean hudEnabled;
    boolean visualEnabled;
    boolean resend;
    int refreshBatchSize;
    boolean migrationEnabled;
    boolean greenhouseEnabled;
    boolean villagerEnabled;
    String overworld;
    String hudTitle;
    List<String> weatherDisabledWorlds = List.of();
    List<String> disabledWorlds = List.of();
    List<String> migrationWorlds = List.of();
    Set<String> animals = Set.of();
    Map<String, Map<String, Double>> weather = Map.of();
    Map<String, String> hudColors = Map.of();
    Map<String, String> visualBiomes = Map.of();
    Map<String, Map<String, Double>> cropRates = Map.of();
    Map<String, String> messages = Map.of();
    List<String> guideLines = List.of();

    private final JavaPlugin plugin;

    SeasonSettings(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void refresh() {
        FileConfiguration config = plugin.getConfig();
        daysPerSeason = positive(config.getInt("calendar.days-per-season", 28), 28,
                "calendar.days-per-season");
        realMinutes = nonNegative(config.getInt("calendar.real-time-minutes-per-day", 0), 0,
                "calendar.real-time-minutes-per-day");
        followWorld = config.getBoolean("calendar.follow-overworld-time", false);
        advanceSleep = config.getBoolean("calendar.advance-on-sleep", true);
        if (realMinutes > 0) {
            followWorld = false;
            advanceSleep = false;
        } else if (followWorld) {
            advanceSleep = false;
        }
        requirePlayers = config.getBoolean("calendar.require-players", true);
        maxCatchup = positive(config.getInt("calendar.max-catchup", 2), 2,
                "calendar.max-catchup");
        overworld = config.getString("calendar.overworld", "world");

        mainPeriodSeconds = positive(config.getInt("weather.check-seconds", 60), 60,
                "weather.check-seconds");
        weatherEnabled = config.getBoolean("weather.enabled", true);
        weatherDisabledWorlds = List.copyOf(config.getStringList("weather.disabled-worlds"));
        weather = loadWeights(config, "weather.seasons");

        hudEnabled = config.getBoolean("hud.enabled", true);
        hudTitle = config.getString("hud.title", "第%year%年 · %season% · 第%day%天");
        hudColors = Map.copyOf(Map.of(
                "spring", config.getString("hud.colors.spring", "PINK"),
                "summer", config.getString("hud.colors.summer", "YELLOW"),
                "autumn", config.getString("hud.colors.autumn", "RED"),
                "winter", config.getString("hud.colors.winter", "BLUE")
        ));
        visualEnabled = config.getBoolean("visual.enabled", true);
        resend = config.getBoolean("visual.resend-on-season-change", true);
        refreshBatchSize = positive(config.getInt("visual.refresh-batch-size", 64), 64,
                "visual.refresh-batch-size");
        disabledWorlds = List.copyOf(config.getStringList("visual.disabled-worlds"));
        visualBiomes = loadBiomes(config.getConfigurationSection("visual.mappings"));

        migrationEnabled = config.getBoolean("migration.enabled", true);
        migrationPeriod = positive(config.getInt("migration.period-seconds", 300), 300,
                "migration.period-seconds");
        migrationRadius = positive(config.getInt("migration.radius", 32), 32,
                "migration.radius");
        migrationMax = positive(config.getInt("migration.max-entities", 24), 24,
                "migration.max-entities");
        migrationMinDistance = positive(config.getInt("migration.min-distance", 8), 8,
                "migration.min-distance");
        migrationMaxDistance = positive(config.getInt("migration.max-distance", 16), 16,
                "migration.max-distance");
        if (migrationMaxDistance < migrationMinDistance) {
            plugin.getLogger().warning("配置 migration.max-distance 小于 min-distance，已回退为 min-distance");
            migrationMaxDistance = migrationMinDistance;
        }
        migrationWorlds = List.copyOf(config.getStringList("migration.worlds"));
        Set<String> animalTypes = new HashSet<>();
        for (String animal : config.getStringList("migration.animals")) {
            animalTypes.add(animal.toUpperCase(Locale.ROOT));
        }
        animals = Set.copyOf(animalTypes);
        villagerEnabled = config.getBoolean("villagers.enabled", true);

        messages = loadStrings(config, "messages");
        guideLines = List.copyOf(config.getStringList("messages.guide-lines"));
    }

    void loadCrops(FileConfiguration config) {
        cropRates = loadWeights(config, "crops");
        greenhouseEnabled = config.getBoolean("greenhouse.enabled", true);
        greenhouseAbove = positive(config.getInt("greenhouse.glass-above", 3), 3,
                "greenhouse.glass-above");
    }

    private Map<String, String> loadBiomes(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String season : section.getKeys(false)) {
            String value = section.getString(season, "");
            try {
                org.bukkit.block.Biome.valueOf(value.toUpperCase(Locale.ROOT));
                out.put(season.toLowerCase(Locale.ROOT), value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("配置 visual.mappings." + season + " 不是有效 biome：" + value);
            }
        }
        return Map.copyOf(out);
    }

    private Map<String, Map<String, Double>> loadWeights(FileConfiguration config, String path) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        ConfigurationSection root = config.getConfigurationSection(path);
        if (root == null) {
            return Map.of();
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Map<String, Double> row = new HashMap<>();
            for (String child : section.getKeys(false)) {
                row.put(child.toLowerCase(Locale.ROOT), Math.max(0.0, section.getDouble(child, 0.0)));
            }
            out.put(key.toLowerCase(Locale.ROOT), Map.copyOf(row));
        }
        return Map.copyOf(out);
    }

    private Map<String, String> loadStrings(FileConfiguration config, String path) {
        Map<String, String> out = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key) || section.isList(key)) {
                continue;
            }
            String value = section.getString(key);
            if (value != null) {
                out.put(key, value);
            }
        }
        return Map.copyOf(out);
    }

    private int positive(int value, int fallback, String path) {
        if (value > 0) {
            return value;
        }
        plugin.getLogger().warning("配置 " + path + " 必须大于 0，已回退为 " + fallback);
        return fallback;
    }

    private int nonNegative(int value, int fallback, String path) {
        if (value >= 0) {
            return value;
        }
        plugin.getLogger().warning("配置 " + path + " 不能为负数，已回退为 " + fallback);
        return fallback;
    }
}

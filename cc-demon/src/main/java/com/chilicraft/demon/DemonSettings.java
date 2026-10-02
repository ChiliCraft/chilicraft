package com.chilicraft.demon;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 数值缓存：refresh() 从活配置重建全部字段（onEnable 与 core.reload 事件时调用）。
 * 字段包内可见，周期任务直接读取，避免每周期穿透配置树。
 */
final class DemonSettings {

    // —— 夜间判定（与 cc-survival 一致的原版时间刻区间） ——
    long nightStart;
    long nightEnd;

    // —— 刷怪 ——
    boolean spawnEnabled;
    int spawnIntervalSec;
    int radiusMin;
    int radiusMax;
    int maxPerPlayer;
    int globalLimit;
    int impGroupMin;
    int impGroupMax;
    double hunterChance;
    double motherChance;
    /** 巡演联动：排除世界名单（方街安全区）——名单内不刷怪、不索敌 */
    List<String> excludedWorlds = List.of();

    // —— 饿意追踪 ——
    boolean hungerEnabled;
    int hungerThreshold;
    int hungerRadius;
    double hungerSpeedBoost;

    // —— 母体 ——
    int motherSummonIntervalSec;
    int summonMin;
    int summonMax;
    int maxSummons;

    // —— 白天清除 ——
    boolean daytimeClear;

    // —— 掉落 ——
    Material fangMaterial;
    int fangMin;
    int fangMax;
    Material soulMaterial;
    double soulChance;

    // —— 属性表 ——
    Map<DemonType, Stats> stats = Map.of();

    // —— MythicMobs 接管（规格 v1.1：MM 在场且开关开启时，配置了 MM ID 的类型由 MM 生成） ——
    boolean integrationMythicMobs;
    /** 饿魔类型 → MM 内部 ID；未配置的类型永远走原版属性改造路径 */
    Map<DemonType, String> mythicIds = Map.of();

    // —— 消息模板（messages / feedback 段原始字符串，MiniMessage 由展示层解析） ——
    Map<String, String> messageTemplates = Map.of();
    Map<String, String> feedbackTemplates = Map.of();

    /** 原版生物改造目标属性 */
    record Stats(double health, double damage) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    DemonSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** 是否为夜间（原版时间刻在 [nightStart, nightEnd) 内） */
    boolean isNight(long worldTime) {
        return worldTime >= nightStart && worldTime < nightEnd;
    }

    /** 世界是否在刷怪排除名单（方街安全区联动：不刷怪、不索敌） */
    boolean inExcludedWorld(String worldName) {
        return excludedWorlds.contains(worldName);
    }

    /** 取 messages 段模板；缺失返回空串 */
    String message(String key) {
        return messageTemplates.getOrDefault(key, "");
    }

    /** 取 messages 段模板；缺失回退默认（GUI 按钮名不空白） */
    String messageOr(String key, String def) {
        String value = messageTemplates.get(key);
        return value != null ? value : def;
    }

    /** 取 feedback 段模板；缺失返回空串 */
    String feedback(String key) {
        return feedbackTemplates.getOrDefault(key, "");
    }

    /** 从活配置重建缓存；非法值回退默认并告警 */
    void refresh() {
        FileConfiguration c = plugin.getConfig();

        nightStart = c.getLong("night-start", 13042);
        nightEnd = c.getLong("night-end", 23458);

        spawnEnabled = c.getBoolean("spawn.enabled", true);
        spawnIntervalSec = (int) positive(c, "spawn.interval", 30);
        radiusMin = (int) positive(c, "spawn.radius-min", 24);
        radiusMax = (int) positive(c, "spawn.radius-max", 48);
        if (radiusMax < radiusMin) {
            warn("spawn.radius-max", radiusMax, radiusMin);
            radiusMax = radiusMin;
        }
        maxPerPlayer = (int) positive(c, "spawn.max-per-player", 12);
        // 性能红线：全服硬上限，配置过大时强制压回
        globalLimit = (int) clamp(c.getInt("spawn.global-limit", 80), 1, 80);
        impGroupMin = (int) positive(c, "spawn.imp.group-min", 3);
        impGroupMax = (int) positive(c, "spawn.imp.group-max", 6);
        if (impGroupMax < impGroupMin) {
            warn("spawn.imp.group-max", impGroupMax, impGroupMin);
            impGroupMax = impGroupMin;
        }
        hunterChance = clamp01(c, "spawn.hunter-chance", 0.35);
        motherChance = clamp01(c, "spawn.mother-chance", 0.05);
        excludedWorlds = List.copyOf(c.getStringList("spawn.excluded-worlds"));

        hungerEnabled = c.getBoolean("hunger.enabled", true);
        hungerThreshold = (int) clamp(c.getDouble("hunger.threshold", 6), 0, 20);
        hungerRadius = (int) positive(c, "hunger.radius", 48);
        hungerSpeedBoost = clamp(c.getDouble("hunger.speed-boost", 0.30), 0.0, 2.0);

        motherSummonIntervalSec = (int) nonNegative(c, "mother.summon-interval", 10);
        summonMin = (int) nonNegative(c, "mother.summon-min", 1);
        summonMax = (int) nonNegative(c, "mother.summon-max", 2);
        if (summonMax < summonMin) {
            warn("mother.summon-max", summonMax, summonMin);
            summonMax = summonMin;
        }
        maxSummons = (int) nonNegative(c, "mother.max-summons", 8);

        daytimeClear = c.getBoolean("daytime.clear", true);

        fangMaterial = parseMaterial(c.getString("drops.fang.material"), Material.SLIME_BALL);
        fangMin = (int) nonNegative(c, "drops.fang.min", 1);
        fangMax = (int) nonNegative(c, "drops.fang.max", 2);
        if (fangMax < fangMin) {
            warn("drops.fang.max", fangMax, fangMin);
            fangMax = fangMin;
        }
        soulMaterial = parseMaterial(c.getString("drops.soul.material"), Material.GHAST_TEAR);
        soulChance = clamp01(c, "drops.soul.chance", 0.15);

        stats = parseStats(c);
        integrationMythicMobs = c.getBoolean("integration.mythicmobs", false);
        mythicIds = parseMythicIds(c);
        messageTemplates = loadSection(c, "messages");
        feedbackTemplates = loadSection(c, "feedback");
    }

    // ---------------- 解析辅助 ----------------

    private Map<DemonType, Stats> parseStats(FileConfiguration c) {
        Map<DemonType, Stats> result = new EnumMap<>(DemonType.class);
        Map<DemonType, Stats> defaults = new EnumMap<>(DemonType.class);
        defaults.put(DemonType.IMP, new Stats(12.0, 4.0));
        defaults.put(DemonType.HUNTER, new Stats(24.0, 7.0));
        defaults.put(DemonType.MOTHER, new Stats(200.0, 12.0));
        for (DemonType type : DemonType.values()) {
            Stats def = defaults.get(type);
            double health = positive(c, "attributes." + type.id() + ".health", def.health());
            double damage = positive(c, "attributes." + type.id() + ".damage", def.damage());
            result.put(type, new Stats(health, damage));
        }
        return Map.copyOf(result);
    }

    /** 解析 MM 内部 ID 表；开关未开或未配置的类型返回空映射（该类型走原版路径） */
    private Map<DemonType, String> parseMythicIds(FileConfiguration c) {
        if (!integrationMythicMobs) {
            return Map.of();
        }
        Map<DemonType, String> result = new EnumMap<>(DemonType.class);
        for (DemonType type : DemonType.values()) {
            String id = c.getString("mythic.types." + type.id(), "");
            if (!id.isEmpty()) {
                result.put(type, id);
            } else {
                logger.info(() -> "integration.mythicmobs 已开启但 mythic.types." + type.id() + " 未配置，该类型走原版属性改造路径");
            }
        }
        return Map.copyOf(result);
    }

    /** 泛化读取 messages / feedback 两段键值模板 */
    private Map<String, String> loadSection(FileConfiguration c, String path) {
        ConfigurationSection section = c.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }

    private Material parseMaterial(String name, Material def) {
        if (name == null || name.isEmpty()) {
            return def;
        }
        Material material = Material.matchMaterial(name);
        if (material == null) {
            logger.warning(() -> "掉落材质非法: " + name + "，回退默认 " + def);
            return def;
        }
        return material;
    }

    private double positive(FileConfiguration c, String path, double def) {
        double v = c.getDouble(path, def);
        if (v <= 0) {
            warn(path, v, def);
            return def;
        }
        return v;
    }

    private double nonNegative(FileConfiguration c, String path, double def) {
        double v = c.getDouble(path, def);
        if (v < 0) {
            warn(path, v, def);
            return def;
        }
        return v;
    }

    private double clamp01(FileConfiguration c, String path, double def) {
        double v = c.getDouble(path, def);
        if (v < 0 || v > 1) {
            warn(path, v, def);
            return def;
        }
        return v;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void warn(String path, double value, double def) {
        logger.warning(() -> "配置键 " + path + " 非法值 " + value + "，回退默认 " + def);
    }
}

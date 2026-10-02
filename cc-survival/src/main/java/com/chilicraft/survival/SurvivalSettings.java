package com.chilicraft.survival;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 数值缓存：refresh() 从活配置重建全部字段（onEnable 与 core.reload 事件时调用）。
 * 字段包内可见，周期任务直接读取，避免每秒穿透配置树。
 */
final class SurvivalSettings {

    // —— 饥饿 ——
    boolean hungerEnabled;
    double hungerMultiplier;
    double nightMultiplier;
    double baseExhaustion;
    long nightStart;
    long nightEnd;

    // —— 体温 ——
    boolean tempEnabled;
    double initialTemp;
    double tempLerp;
    double nightOffset;
    double wetOffset;
    double heatGuarantee;
    int heatRadius;
    List<Material> heatBlocks = List.of();
    List<Climate> climates = List.of();
    List<Zone> zones = List.of();
    int hypothermiaInterval;
    double hypothermiaDamage;

    // —— 负重 ——
    boolean weightEnabled;
    double weightLimit;
    double defaultPerItem;
    Map<Material, Double> weightValues = Map.of();
    int slowThreshold;
    int slowAmplifier;
    int jumpBlockThreshold;
    int effectDurationTicks;

    // —— 耐久 ——
    boolean durabilityEnabled;
    double durabilityMultiplier;

    // —— HUD ——
    boolean hudEnabled;
    boolean hudHideWhenNormal;

    // —— 方街安全区（巡演联动） ——
    double streetMultiplier;
    List<String> streetWorlds = List.of();

    // —— 消息模板（messages 段原始字符串，MiniMessage 由展示层解析） ——
    Map<String, String> messageTemplates = Map.of();

    /** 生物群系温度档位（maxBiomeTemp 升序，匹配第一个 biomeTemp ≤ max 的档） */
    record Climate(double maxBiomeTemp, double target) {
    }

    /** 体温分区（min 降序，匹配第一个 temp ≥ min 的档） */
    record Zone(int min, int slowAmplifier, int weaknessAmplifier, double extraExhaustion) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    SurvivalSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** 是否为夜间（原版时间刻在 [nightStart, nightEnd) 内） */
    boolean isNight(long worldTime) {
        return worldTime >= nightStart && worldTime < nightEnd;
    }

    /** 世界是否为方街安全区（巡演联动：名单内世界压力折减） */
    boolean inStreet(String worldName) {
        return streetWorlds.contains(worldName);
    }

    /** 从活配置重建缓存；非法值回退默认并告警 */
    void refresh() {
        FileConfiguration c = plugin.getConfig();

        hungerEnabled = c.getBoolean("hunger.enabled", true);
        hungerMultiplier = positive(c, "hunger.multiplier", 1.5);
        nightMultiplier = positive(c, "hunger.night-multiplier", 2.0);
        baseExhaustion = nonNegative(c, "hunger.base-exhaustion", 0.02);
        nightStart = c.getLong("hunger.night-start", 13042);
        nightEnd = c.getLong("hunger.night-end", 23458);

        tempEnabled = c.getBoolean("temperature.enabled", true);
        initialTemp = clamp(c.getDouble("temperature.initial", 70.0), 0.0, 100.0);
        tempLerp = clamp(c.getDouble("temperature.lerp", 0.05), 0.0, 1.0);
        nightOffset = c.getDouble("temperature.night-offset", -10.0);
        wetOffset = c.getDouble("temperature.wet-offset", -20.0);
        heatGuarantee = c.getDouble("temperature.heat-guarantee", 70.0);
        heatRadius = (int) clamp(c.getDouble("temperature.heat-source.radius", 3), 1, 8);
        heatBlocks = parseMaterials(c.getStringList("temperature.heat-source.blocks"));
        climates = parseClimates(c);
        zones = parseZones(c);
        hypothermiaInterval = (int) positive(c, "temperature.hypothermia-interval", 10);
        hypothermiaDamage = positive(c, "temperature.hypothermia-damage", 1.0);

        weightEnabled = c.getBoolean("weight.enabled", true);
        weightLimit = positive(c, "weight.limit", 200.0);
        defaultPerItem = nonNegative(c, "weight.default-per-item", 0.1);
        weightValues = parseWeightValues(c);
        slowThreshold = (int) clamp(c.getDouble("weight.slow-threshold", 70), 1, 100);
        slowAmplifier = c.getInt("weight.slow-amplifier", 0);
        jumpBlockThreshold = (int) clamp(c.getDouble("weight.jump-block-threshold", 100), 1, 100);
        effectDurationTicks = (int) clamp(c.getDouble("weight.effect-duration-ticks", 40), 20, 200);

        durabilityEnabled = c.getBoolean("durability.enabled", true);
        durabilityMultiplier = positive(c, "durability.multiplier", 1.5);

        hudEnabled = c.getBoolean("hud.actionbar", true);
        hudHideWhenNormal = c.getBoolean("hud.hide-when-normal", true);

        // 方街安全区（巡演联动）：名单内世界压力按倍率折减
        streetMultiplier = clamp(c.getDouble("street.pressure-multiplier", 0.5), 0.0, 1.0);
        streetWorlds = List.copyOf(c.getStringList("street.worlds"));

        messageTemplates = loadMessages(c);
    }

    /** 取消息模板；缺失返回空串（HUD 展示层对空串静默） */
    String message(String key) {
        return messageTemplates.getOrDefault(key, "");
    }

    String messageOr(String key, String fallback) {
        String value = message(key);
        return value.isEmpty() ? fallback : value;
    }

    private Map<String, String> loadMessages(FileConfiguration c) {
        ConfigurationSection section = c.getConfigurationSection("messages");
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

    // ---------------- 解析辅助 ----------------

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

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void warn(String path, double value, double def) {
        logger.warning(() -> "配置键 " + path + " 非法值 " + value + "，回退默认 " + def);
    }

    private List<Material> parseMaterials(List<String> names) {
        List<Material> result = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                result.add(material);
            } else {
                logger.warning(() -> "热源方块列表含未知材质: " + name);
            }
        }
        return List.copyOf(result);
    }

    private List<Climate> parseClimates(FileConfiguration c) {
        List<Climate> result = new ArrayList<>();
        for (Map<?, ?> entry : c.getMapList("temperature.climates")) {
            Object max = entry.get("max-biome-temp");
            Object target = entry.get("target");
            if (max instanceof Number maxNum && target instanceof Number targetNum) {
                result.add(new Climate(maxNum.doubleValue(), targetNum.doubleValue()));
            } else {
                logger.warning(() -> "temperature.climates 存在缺字段的档位，已跳过: " + entry);
            }
        }
        if (result.isEmpty()) {
            logger.warning("temperature.climates 为空，回退内置默认档位");
            return List.of(new Climate(0.15, 20.0), new Climate(0.4, 45.0),
                    new Climate(0.8, 65.0), new Climate(99.0, 95.0));
        }
        result.sort(Comparator.comparingDouble(Climate::maxBiomeTemp));
        return List.copyOf(result);
    }

    private List<Zone> parseZones(FileConfiguration c) {
        List<Zone> result = new ArrayList<>();
        for (Map<?, ?> entry : c.getMapList("temperature.zones")) {
            Object min = entry.get("min");
            Object slow = entry.get("slow-amplifier");
            Object weak = entry.get("weakness-amplifier");
            Object exhaust = entry.get("extra-exhaustion");
            if (min instanceof Number minNum) {
                result.add(new Zone(minNum.intValue(),
                        slow instanceof Number n ? n.intValue() : -1,
                        weak instanceof Number n ? n.intValue() : -1,
                        exhaust instanceof Number n ? n.doubleValue() : 0.0));
            } else {
                logger.warning(() -> "temperature.zones 存在缺 min 字段的档位，已跳过: " + entry);
            }
        }
        if (result.isEmpty()) {
            logger.warning("temperature.zones 为空，回退内置默认分区");
            return List.of(new Zone(90, -1, -1, 0.02), new Zone(60, -1, -1, 0.0),
                    new Zone(40, -1, -1, 0.0), new Zone(20, 0, -1, 0.0), new Zone(0, 1, 0, 0.0));
        }
        result.sort(Comparator.comparingInt(Zone::min).reversed());
        return List.copyOf(result);
    }

    private Map<Material, Double> parseWeightValues(FileConfiguration c) {
        ConfigurationSection section = c.getConfigurationSection("weight.values");
        if (section == null) {
            return Map.of();
        }
        Map<Material, Double> result = new EnumMap<>(Material.class);
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            double value = section.getDouble(key, -1);
            if (material != null && value >= 0) {
                result.put(material, value);
            } else {
                logger.warning(() -> "weight.values 含非法条目: " + key + " = " + value);
            }
        }
        return result;
    }
}

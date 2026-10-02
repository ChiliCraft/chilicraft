package com.chilicraft.core.config;

import com.chilicraft.api.GameMode;
import com.chilicraft.api.ParamKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Map;

/**
 * 核心配置快照：volatile 单值字段 + 不可变 Map 快照，
 * /cc reload 时主线程整体重读替换，读方永远看到一致视图。
 *
 * <p>校验策略：缺失/非法键使用默认值并告警，绝不让坏配置炸掉启动。</p>
 */
public final class CoreConfig {

    private final Logger logger;

    // —— mode 段 ——
    private volatile GameMode defaultMode = GameMode.COZY;
    private volatile long switchCooldownMs = 24L * 60 * 60 * 1000;

    // —— home-zone 段 ——
    private volatile int homeRadius = 128;
    private volatile double homeMobMultiplier = 0.1;
    private volatile boolean homeDemonEnabled = false;

    // —— params 段 ——
    private volatile Map<GameMode, Map<ParamKey, Double>> params = defaultParams();

    // —— economy 段 ——
    private volatile String soulName = "灵魂";
    private volatile double soulLossPct = 50.0;

    // —— auto-save 段 ——
    private volatile int autoSaveIntervalTicks = 1200; // 60s

    /** 原始配置引用：消息模板等杂项键从这读（reload 时随整体替换） */
    private volatile FileConfiguration raw;

    public CoreConfig(Logger logger) {
        this.logger = logger;
    }

    /** 重读配置（主线程调用）：校验缺失/非法键并替换全部快照 */
    public void reload(FileConfiguration config) {
        // mode.default
        GameMode def = GameMode.fromName(config.getString("mode.default", "cozy"));
        if (def == null) {
            logger.warn("配置键 mode.default 值非法（{}），回退 COZY", config.getString("mode.default"));
            def = GameMode.COZY;
        }
        this.defaultMode = def;

        // mode.switch-cooldown-hours
        int hours = config.getInt("switch-cooldown-hours", 24);
        if (hours < 0) {
            logger.warn("配置键 switch-cooldown-hours 不能为负（{}），回退 24", hours);
            hours = 24;
        }
        this.switchCooldownMs = hours * 3_600_000L;

        // home-zone
        int radius = config.getInt("home-zone.radius", 128);
        if (radius <= 0) {
            logger.warn("配置键 home-zone.radius 必须为正（{}），回退 128", radius);
            radius = 128;
        }
        this.homeRadius = radius;

        double multiplier = config.getDouble("home-zone.mob-multiplier", 0.1);
        if (multiplier < 0) {
            logger.warn("配置键 home-zone.mob-multiplier 不能为负（{}），回退 0.1", multiplier);
            multiplier = 0.1;
        }
        this.homeMobMultiplier = multiplier;
        this.homeDemonEnabled = config.getBoolean("home-zone.demon-enabled", false);

        // params（每个模式 × 每个参数键都有默认 1.0）
        Map<GameMode, Map<ParamKey, Double>> parsed = new EnumMap<>(GameMode.class);
        for (GameMode mode : GameMode.values()) {
            EnumMap<ParamKey, Double> inner = new EnumMap<>(ParamKey.class);
            for (ParamKey key : ParamKey.values()) {
                String path = "params." + modePath(mode) + "." + keyPath(key);
                double value = config.getDouble(path, 1.0);
                if (value < 0) {
                    logger.warn("配置键 {} 不能为负（{}），回退 1.0", path, value);
                    value = 1.0;
                }
                inner.put(key, value);
            }
            parsed.put(mode, inner);
        }
        this.params = parsed;

        // economy
        String soul = config.getString("economy.soul-name", "灵魂");
        this.soulName = (soul == null || soul.isEmpty()) ? "灵魂" : soul;
        double lossPct = config.getDouble("economy.soul-loss-pct", 50.0);
        if (lossPct < 0 || lossPct > 100) {
            logger.warn("配置键 economy.soul-loss-pct 应在 0-100 之间（{}），回退 50", lossPct);
            lossPct = 50.0;
        }
        this.soulLossPct = lossPct;

        // auto-save（下限 5 秒，防止误配打爆调度器）
        int seconds = config.getInt("auto-save.interval-seconds", 60);
        if (seconds < 5) {
            logger.warn("配置键 auto-save.interval-seconds 下限 5（{}），回退 60", seconds);
            seconds = 60;
        }
        this.autoSaveIntervalTicks = seconds * 20;

        this.raw = config;
    }

    // ---------- getters ----------

    public GameMode defaultMode() {
        return defaultMode;
    }

    public long switchCooldownMs() {
        return switchCooldownMs;
    }

    /** 模式切换剩余冷却分钟数（向上取整；无冷却或已到期返回 0） */
    public long remainingCooldownMinutes(long modeSwitchAt) {
        long cooldown = switchCooldownMs();
        if (cooldown <= 0 || modeSwitchAt <= 0) {
            return 0;
        }
        long remain = cooldown - (System.currentTimeMillis() - modeSwitchAt);
        return remain <= 0 ? 0 : (remain + 59_999) / 60_000;
    }

    public int homeRadius() {
        return homeRadius;
    }

    public double homeMobMultiplier() {
        return homeMobMultiplier;
    }

    public boolean homeDemonEnabled() {
        return homeDemonEnabled;
    }

    /** 指定模式下的参数基准值；缺失回退 1.0 */
    public double param(GameMode mode, ParamKey key) {
        Map<ParamKey, Double> inner = params.get(mode);
        if (inner == null) {
            return 1.0;
        }
        Double value = inner.get(key);
        return value == null ? 1.0 : value;
    }

    public String soulName() {
        return soulName;
    }

    /** 死亡灵魂损失百分比（0-100） */
    public double soulLossPct() {
        return soulLossPct;
    }

    public int autoSaveIntervalTicks() {
        return autoSaveIntervalTicks;
    }

    /** 读取消息模板（MiniMessage 原文）；键缺失返回 null，由调用方兜底 */
    public String message(String key) {
        FileConfiguration cfg = raw;
        return cfg == null ? null : cfg.getString("messages." + key);
    }

    /** 联动开关（config: integrations.<key>，默认 true；插件本体未装时自然不生效） */
    public boolean integrationEnabled(String key) {
        FileConfiguration cfg = raw;
        return cfg == null || cfg.getBoolean("integrations." + key, true);
    }

    // ---------- 路径映射 ----------

    private static String modePath(GameMode mode) {
        return mode == GameMode.ADVENTURE ? "adventure" : "cozy";
    }

    private static String keyPath(ParamKey key) {
        return switch (key) {
            case HUNGER_DECAY -> "hunger-decay";
            case DROP_RATE -> "drop-rate";
            case WEIGHT_LIMIT -> "weight-limit";
            case MOB_SPAWN_RATE -> "mob-spawn-rate";
            case DURABILITY_COST -> "durability-cost";
        };
    }

    private static Map<GameMode, Map<ParamKey, Double>> defaultParams() {
        Map<GameMode, Map<ParamKey, Double>> result = new EnumMap<>(GameMode.class);
        for (GameMode mode : GameMode.values()) {
            EnumMap<ParamKey, Double> inner = new EnumMap<>(ParamKey.class);
            for (ParamKey key : ParamKey.values()) {
                inner.put(key, 1.0);
            }
            result.put(mode, inner);
        }
        return result;
    }
}

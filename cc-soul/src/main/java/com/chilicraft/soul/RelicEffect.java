package com.chilicraft.soul;

/**
 * 遗物效果类型（12 型，与配置 relics.definitions.*.effect 一一对应）。
 *
 * <p>效果分两类，由 RelicService 统一按玩家汇总持有加成：</p>
 * <ul>
 *   <li>持续型（{@link #sustained()} 为 true）：由 2 秒循环任务维护——
 *       SPEED / NIGHT_VISION / WATER_BREATH 按「当前应有 - 当前已上」差异
 *       增删药水效果，NIGHT_REGEN 周期性直接回血；脱下遗物即失效；</li>
 *   <li>事件钩子型：监听对应 Bukkit 事件时读取缓存加成即时生效，触发式消耗灵韵。</li>
 * </ul>
 */
enum RelicEffect {

    /** 雨中受到的伤害按 value 比例减免（0.20 = -20%） */
    RAIN_RESIST("rain_resist"),

    /** 灵魂碎片自动拾回半径额外增加 value 格 */
    FRAGMENT_RADIUS("fragment_radius"),

    /** 饱食度下降量按 value 比例减免（0.20 = -20%） */
    HUNGER_KEEP("hunger_keep"),

    /** 夜间每 2 秒恢复 value 点生命（持续型，周期直接回血） */
    NIGHT_REGEN("night_regen"),

    /** 水下呼吸（药水型，value 为等级参考，恒为 1 级） */
    WATER_BREATH("water_breath"),

    /** 摔落伤害按 value 比例减免（0.50 = -50%） */
    FALL_RESIST("fall_resist"),

    /** 饿魔（cc-demon 标记实体）造成的伤害按 value 比例减免 */
    DEMON_WARD("demon_ward"),

    /** 移动速度按 value 比例加成（药水型，0.10 = +10%） */
    SPEED("speed"),

    /** 夜视（药水型，仅夜间生效，白天自动摘除） */
    NIGHT_VISION("night_vision"),

    /** 通用伤害按 value 比例减免（0.10 = -10%） */
    GENERIC_RESIST("generic_resist"),

    /** 饮用药水 / 牛奶 / 水瓶时按 value 比例回复最大生命（0.20 = +20%） */
    DRINK_HEAL("drink_heal"),

    /** 击杀生物获得的灵魂按 value 比例加成（0.10 = +10%） */
    KILL_SOUL("kill_soul");

    /** 配置标识（snake_case） */
    private final String id;

    RelicEffect(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    /** 是否为持续型效果（由 2 秒循环任务维护，而非事件钩子） */
    boolean sustained() {
        return this == SPEED || this == NIGHT_VISION || this == WATER_BREATH
                || this == NIGHT_REGEN;
    }

    /** 解析配置标识；未知返回 null（调用方负责跳过并告警） */
    static RelicEffect parse(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (RelicEffect effect : values()) {
            if (effect.id.equals(id)) {
                return effect;
            }
        }
        return null;
    }
}

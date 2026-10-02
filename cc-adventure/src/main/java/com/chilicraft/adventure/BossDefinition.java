package com.chilicraft.adventure;

import java.util.List;
import java.util.Locale;

/**
 * 世界 Boss 定义（bosses.yml 数据驱动）。
 *
 * <p>5 个 Boss（规格：饿魔母体·真身 3,000 / 水做之影 2,500 / 台风之眼 2,800 /
 * 幻形鹦鹉 2,000 / 梦魇 4,000），触发条件按总纲（连续夜间死亡 / 雨天深海 /
 * 台风 / 随机 / 半梦界深层）。默认原版生物改属性＋技能事件；MythicMobs 在场时
 * 技能定义交 MM（mythic-id 键），缺失降级原版路径。</p>
 */
final class BossDefinition {

    /** 触发条件类型（总纲口径） */
    enum TriggerType {
        NIGHT_DEATH_STREAK,  // 连续夜间死亡 N 次（param=阈值）
        RAIN_OCEAN,          // 雨天且玩家处于深海群系（param=触发概率%）
        THUNDER,             // 雷暴（台风）天气（param=触发概率%）
        RANDOM,              // 任意时刻低概率（param=触发概率%）
        DEPTH                // 玩家深层（半梦界深层占位：y < param）
    }

    /** 原版路径技能事件类型（MM 在场时由 MM 配置接管，不走此列表） */
    enum SkillType {
        SUMMON,     // 召唤护卫：summon=实体类型, count=数量
        EFFECT,     // 对附近玩家施放药水效果：effect=类型, amplifier=强度, radius=半径
        LIGHTNING   // 对随机附近玩家落雷：radius=半径
    }

    /** 一条技能事件定义 */
    record Skill(SkillType type, String effect, int amplifier, int count, int radius, String summon) {
    }

    final String id;
    final String displayName;
    final String entityType;        // 原版实体类型名
    final int health;               // 最大生命（规格数值）
    final int attack;               // 攻击伤害加值
    final TriggerType trigger;
    final int param;                // 触发参数（阈值/概率%/y 阈）
    final int firstKillSoul;        // 首杀灵魂奖励（按玩家）
    final String mythicId;          // MM 内部 ID（空串=不用 MM）
    final List<Skill> skills;       // 原版路径技能事件
    final boolean enabled;

    BossDefinition(String id, String displayName, String entityType, int health, int attack,
                   TriggerType trigger, int param, int firstKillSoul, String mythicId,
                   List<Skill> skills, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.entityType = entityType;
        this.health = health;
        this.attack = attack;
        this.trigger = trigger;
        this.param = param;
        this.firstKillSoul = firstKillSoul;
        this.mythicId = mythicId == null ? "" : mythicId;
        this.skills = skills;
        this.enabled = enabled;
    }

    static TriggerType parseTrigger(String name) {
        try {
            return TriggerType.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

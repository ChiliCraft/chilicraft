package com.chilicraft.adventure;

import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Locale;

/**
 * 地城定义（dungeons.yml 数据驱动）。
 *
 * <p>一个定义 = 一种地城；实例化时每队占用一个独立区域（数据隔离不串场）。
 * 机制类型见 {@link Mechanic}，波次 / 献祭目标 / 时限 / 奖励全部可配置。</p>
 */
final class DungeonDefinition {

    /** 5 类机制（规格：守锅/献祭/承重/水下/双界，事件与状态机实现） */
    enum Mechanic {
        GUARD_CAULDRON,   // 守锅：守护大锅不被攻破（波次清怪）
        SACRIFICE,        // 献祭：向祭坛提交指定物品
        BEAR_WEIGHT,      // 承重：地面逐排塌陷，限时抵达终点板
        UNDERWATER,       // 水下：水房清光守卫（溺尸）
        DUAL_REALM        // 双界：限时内激活两座界门（占位简化实现）
    }

    final String id;
    final String displayName;
    final Mechanic mechanic;
    final int sizeX;             // 房间占地 X（含墙）
    final int sizeY;
    final int sizeZ;
    final String template;       // 结构模板名（jar 内 structures/<template>.nbt；空串=程序生成）
    final int waves;             // 守锅：波次数
    final int waveSize;          // 守锅：每波怪数
    final String mobType;        // 守锅：怪物类型（EntityType 名，非法回退 ZOMBIE）
    final int offerTarget;       // 献祭：目标数量
    final List<String> offerItems; // 献祭：可提交物品（Material 名）
    final int timeLimitSec;      // 时限（秒）
    final int rewardSoul;        // 通关灵魂
    final boolean enabled;

    DungeonDefinition(String id, String displayName, Mechanic mechanic, int sizeX, int sizeY, int sizeZ,
                      String template, int waves, int waveSize, String mobType,
                      int offerTarget, List<String> offerItems, int timeLimitSec, int rewardSoul, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.mechanic = mechanic;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.template = template == null ? "" : template;
        this.waves = waves;
        this.waveSize = waveSize;
        this.mobType = mobType;
        this.offerTarget = offerTarget;
        this.offerItems = offerItems;
        this.timeLimitSec = timeLimitSec;
        this.rewardSoul = rewardSoul;
        this.enabled = enabled;
    }

    /** 怪物类型解析：非法名回退 ZOMBIE */
    EntityType mobEntityType() {
        try {
            return EntityType.valueOf(mobType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EntityType.ZOMBIE;
        }
    }
}

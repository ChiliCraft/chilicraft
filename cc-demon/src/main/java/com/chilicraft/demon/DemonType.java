package com.chilicraft.demon;

import org.bukkit.entity.EntityType;

/**
 * 饿魔类型：原版生物改属性 + 改名（不重写 AI，靠周期 setTarget 驱动敌对）。
 */
enum DemonType {

    /** 小饿魔：僵尸系，成群出现（规格：12 血 / 4 攻，3-6 只一群） */
    IMP("imp", EntityType.ZOMBIE),

    /** 饿意猎手：骷髅系，无视距离追踪（规格：24 血 / 7 攻） */
    HUNTER("hunter", EntityType.SKELETON),

    /** 饿魔母体：铁傀儡系，周期召唤小饿魔（规格：200 血 / 12 攻，每 10 秒召唤） */
    MOTHER("mother", EntityType.IRON_GOLEM);

    private final String id;
    private final EntityType entity;

    DemonType(String id, EntityType entity) {
        this.id = id;
        this.entity = entity;
    }

    /** PDC 存储用的稳定标识 */
    String id() {
        return id;
    }

    /** 底层原版实体类型 */
    EntityType entity() {
        return entity;
    }

    /** 中文展示名（管理反馈用，硬兜底） */
    String displayName() {
        switch (this) {
            case IMP -> {
                return "小饿魔";
            }
            case HUNTER -> {
                return "饿意猎手";
            }
            default -> {
                return "饿魔母体";
            }
        }
    }

    /** 按 PDC 存储 id 解析；未知返回 null */
    static DemonType fromId(String id) {
        for (DemonType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}

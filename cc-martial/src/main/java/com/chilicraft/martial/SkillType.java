package com.chilicraft.martial;

/**
 * 技能行为类型：结算逻辑内建于代码，skills.yml 以 type 键选择并配 params 数值参数。
 *
 * <p>主动技能由武学手册（右键）施展；被动技能习得后常驻生效
 * （生效条件：所属流派 = 玩家当前流派）。</p>
 */
enum SkillType {

    // ---------- 主动（即时型，SkillExecutor 结算） ----------
    /** 范围伤害：radius / damage / knockback(可选) */
    AOE_DAMAGE(false),
    /** 冲刺位移：speed（速度倍率） */
    DASH(false),
    /** 治疗：amount（满血时不施展，不进冷却） */
    HEAL(false),
    /** 速度增益：amplifier(0=速度I) / duration-ticks */
    SPEED_BUFF(false),
    /** 缓速领域：radius / amplifier / duration-ticks / poison(可选，秒) */
    SLOW_FIELD(false),
    /** 拉拽周围敌人：radius */
    PULL(false),
    /** 跃击（跳向视线方向 + 起跳瞬间范围伤害）：power / damage / radius */
    LEAP(false),

    // ---------- 主动（buff 型，SkillService 结算） ----------
    /** 强力一击（下次近战附加伤害，一次性）：bonus-damage / duration-ticks */
    STRIKE(false),
    /** 吸血一击（下次近战按最终伤害吸血，一次性）：pct / duration-ticks */
    LIFESTEAL_STRIKE(false),
    /** 护盾（吸收伤害，到期回收）：amount / duration-ticks */
    SHIELD(false),

    // ---------- 被动（常驻结算，等级缩放主参数） ----------
    /** 受击减伤：pct（主参数） */
    DAMAGE_REDUCTION(true),
    /** 近战吸血：pct（主参数） */
    LIFESTEAL(true),
    /** 暴击：pct（主参数，概率%）/ multiplier（倍率，不随等级缩放） */
    CRIT_CHANCE(true),
    /** 生命恢复（每 2 秒心跳）：amount（半心=1，主参数） */
    REGEN(true),
    /** 常驻速度增益：amplifier(0=速度I，主参数) */
    SPEED_BOOST(true);

    private final boolean passive;

    SkillType(boolean passive) {
        this.passive = passive;
    }

    boolean isPassive() {
        return passive;
    }

    /** 被动主参数键（等级缩放对象；主动技能不使用） */
    String primaryParam() {
        return switch (this) {
            case DAMAGE_REDUCTION, LIFESTEAL, CRIT_CHANCE -> "pct";
            case REGEN -> "amount";
            case SPEED_BOOST -> "amplifier";
            default -> "";
        };
    }

    /** 解析配置键（支持中划线与下划线），非法返回 null（由内容加载器警告并跳过） */
    static SkillType parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

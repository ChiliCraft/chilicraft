package com.chilicraft.api;

import java.util.UUID;

/**
 * 玩家档案只读视图（对应 cc_players 表核心列）。
 *
 * <p>注意：与 {@code org.bukkit.profile.PlayerProfile} 无关。
 * 可变实现位于核心内部，对外仅暴露只读方法，主线程读写安全。</p>
 */
public interface PlayerProfile {
    /** 玩家 UUID（档案主键） */
    UUID playerId();

    /** 当前全局模式 */
    GameMode mode();

    /** 武学境界等级（cc-martial 维护，0 = 未入门） */
    int martialRealm();

    /** 职业标识（cc-economy 维护，空串 = 无职业） */
    String profession();

    /** 灵魂余额 */
    int soul();

    /** 赛季积分（cc-adventure 等 PVE 成就结算用） */
    int seasonPoints();

    /** 是否已设置家园区锚点 */
    boolean isHomeSet();

    /** 上次模式切换时间（epoch 毫秒，0 = 从未切换） */
    long modeSwitchAt();
}

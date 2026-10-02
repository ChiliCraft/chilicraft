package com.chilicraft.martial;

/**
 * 单个玩家的武学状态缓存（仅主线程读写）。
 *
 * <p>持久化映射（cc_skills 表，skill_id 约定键）：
 * __realm__=境界（level 列）、__kills__/__dungeons__/__bosses__/__arena__=解锁计数（xp 列）。
 * 流派与技能装备（batch 3）追加 __school__/loadout_* 键。</p>
 */
final class PlayerMartialData {

    /** 当前境界 0-5 */
    int realm;
    /** 累计击杀生物数 */
    int kills;
    /** 通关地城次数 */
    int dungeons;
    /** 击杀 Boss 次数 */
    int bosses;
    /** 击败世界 Boss（破天下专用里程碑） */
    boolean worldBoss;
    /** 完成擂台次数 */
    int arenaWins;
    /** 有未落库变更 */
    boolean dirty;
}

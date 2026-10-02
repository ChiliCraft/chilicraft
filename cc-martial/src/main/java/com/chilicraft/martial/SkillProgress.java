package com.chilicraft.martial;

/**
 * 单个技能的成长进度（等级 / 经验）。
 *
 * <p>等级上限 = passive-xp-curve 长度（默认 5）；
 * 经验来源统一为武学经验（击杀 / Boss / 地城，addXp 分配给全部已习得技能）。</p>
 */
final class SkillProgress {

    int level;
    int xp;
    boolean dirty;

    SkillProgress(int level, int xp) {
        this.level = level;
        this.xp = xp;
    }
}

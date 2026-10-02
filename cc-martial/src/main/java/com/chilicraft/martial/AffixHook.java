package com.chilicraft.martial;

/**
 * 词缀事件钩子类型（skills.yml 词缀 hook 键的合法值）。
 *
 * <p>扫描槽位约定：ATTACK / KILL 查主手；
 * DAMAGED / HUNGER / WATER / PASSIVE 查主手 + 四盔甲。</p>
 */
enum AffixHook {
    /** 命中结算：近战或箭矢命中时附加伤害 / 药水 / 燃烧 / 吸血等 */
    ATTACK,
    /** 受击结算：减伤 / 荆棘反伤 / 反药水 / 应急回血 */
    DAMAGED,
    /** 饱食变化：饱食降低减免 / 进食回血 */
    HUNGER,
    /** 氧气变化：氧气消耗减免 / 最大氧气加成 */
    WATER,
    /** 击杀结算：回血 / 回饱食 / 自身增益 */
    KILL,
    /** 常驻灵光：周期任务刷新药水 / 回血 / 黄心 */
    PASSIVE;

    /** 解析 hook 字符串（大小写不敏感；非法返回 null） */
    static AffixHook parse(String input) {
        for (AffixHook hook : values()) {
            if (hook.name().equalsIgnoreCase(input)) {
                return hook;
            }
        }
        return null;
    }
}

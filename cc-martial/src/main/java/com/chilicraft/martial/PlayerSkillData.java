package com.chilicraft.martial;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家武学技能数据（会话缓存；服务端 Map 键恒为 UUID，退出/禁用时移除）。
 *
 * <p>持久化布局（cc_skills 表，复用 skill_id 键空间编码元数据）：
 * <ul>
 *   <li>{@code <school>:<skillId>} —— 技能进度行（level=等级，xp=经验）</li>
 *   <li>{@code __school__:<schoolId>} —— 流派归属（level/xp 恒 0）</li>
 *   <li>{@code __selected__:<school>:<skillId>} —— 当前选中施展技能</li>
 *   <li>{@code __slot<N>__:<school>:<skillId>} —— 装备槽 N（N=0-4）</li>
 * </ul>
 * 与 RealmService 的 {@code __realm__/__kills__/__dungeons__/__bosses__/__arena__}
 * 键空间互不重叠。</p>
 */
final class PlayerSkillData {

    /** 所属流派 id（null = 未拜师） */
    String school;
    /** 当前选中施展的技能 key="school:skillId"（null = 未选） */
    String selected;
    /** 装备槽（固定 5 格；仅前 slotLimit 格生效，null = 空） */
    final String[] slots = new String[5];
    /** 已习得技能进度：key="school:skillId" */
    final Map<String, SkillProgress> skills = new HashMap<>();
    /** 脏标记（技能进度落库用；流派/槽位/选中在变更时即时落库） */
    boolean dirty;
}

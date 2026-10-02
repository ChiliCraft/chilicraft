package com.chilicraft.api;

/**
 * 核心参数键。
 *
 * <p>附属模块通过 {@code API.getParam(playerId, key)} 读取当前玩家
 * 在其所属模式下应使用的参数基准值（config: params 段），
 * 核心已在内部应用家园区加成，附属无需二次判断。</p>
 */
public enum ParamKey {
    /** 饥饿消耗速度倍率（cc-survival） */
    HUNGER_DECAY,
    /** 掉落率倍率 */
    DROP_RATE,
    /** 负重上限（cc-survival） */
    WEIGHT_LIMIT,
    /** 刷怪速率倍率（cc-demon / 原版刷怪控制） */
    MOB_SPAWN_RATE,
    /** 工具/护甲耐久损耗倍率 */
    DURABILITY_COST
}

package com.chilicraft.api;

import java.util.UUID;
import org.bukkit.Location;

/**
 * 家园区服务。
 *
 * <p>家园区为以玩家设置的家锚点为圆心、半径 {@link #radius()} 的圆柱区域，
 * 采用 XZ 平面距离判定（不比较 Y）。
 * 数值来自 config: home-zone 段，核心 reload 时整体刷新实现实例，
 * 因此不要缓存本接口返回的坐标数据。</p>
 */
public interface HomeZone {
    /** 家园区半径（格） */
    int radius();

    /** 家园区内刷怪倍率（config: home-zone.mob-multiplier，如 0.1） */
    double mobMultiplier();

    /** 家园区内是否允许饿魔类事件（config: home-zone.demon-enabled） */
    boolean demonEnabled();

    /** 该玩家是否已设置家锚点 */
    boolean isHomeSet(UUID playerId);

    /**
     * 判断位置是否位于该玩家的家园区内。
     * 玩家未设置家锚点、或世界不同时返回 false。
     */
    boolean isInside(UUID playerId, Location location);
}

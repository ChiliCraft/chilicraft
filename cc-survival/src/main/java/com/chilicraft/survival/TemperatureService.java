package com.chilicraft.survival;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 体温服务：持有每玩家体温状态，推导环境目标温度并向其缓慢趋近。
 *
 * <p>所有方法仅主线程调用（1 秒周期任务驱动），状态无需并发保护。
 * 环境探测（群系温度、水/雨、着火、热源方块）都在玩家自身已加载区块内，
 * 满足规格「温度用 1 秒周期任务，禁止 PlayerMoveEvent」红线。</p>
 */
final class TemperatureService {

    private final SurvivalSettings settings;

    /** 玩家体温（0-100）；缺省时惰性初始化为配置初值 */
    private final Map<UUID, Double> bodyTemp = new HashMap<>();

    /** 失温上次周期伤害时间戳（epoch 毫秒） */
    private final Map<UUID, Long> lastHypothermia = new HashMap<>();

    TemperatureService(SurvivalSettings settings) {
        this.settings = settings;
    }

    /** 当前体温；无记录时以配置初值建立 */
    double body(Player player) {
        return bodyTemp.computeIfAbsent(player.getUniqueId(), k -> settings.initialTemp);
    }

    /** 重置为初始体温（重生时调用） */
    void reset(UUID playerId) {
        bodyTemp.remove(playerId);
        lastHypothermia.remove(playerId);
    }

    /**
     * 周期步进：计算目标 → 体温向目标 lerp → 落盘状态。
     *
     * @return 步进后的体温（0-100）
     */
    double tick(Player player) {
        double current = body(player);
        double target = target(player);
        double next = current + (target - current) * settings.tempLerp;
        next = Math.max(0.0, Math.min(100.0, next));
        bodyTemp.put(player.getUniqueId(), next);
        return next;
    }

    /** 体温分区：从上往下匹配第一个 min ≤ temp 的档位 */
    SurvivalSettings.Zone zone(double temp) {
        for (SurvivalSettings.Zone zone : settings.zones) {
            if (temp >= zone.min()) {
                return zone;
            }
        }
        return settings.zones.get(settings.zones.size() - 1);
    }

    /** 失温周期伤害时间判定：到期返回 true 并记录本次时间 */
    boolean hypothermiaDue(UUID playerId, long now) {
        Long last = lastHypothermia.get(playerId);
        long intervalMs = settings.hypothermiaInterval * 1000L;
        if (last == null || now - last >= intervalMs) {
            lastHypothermia.put(playerId, now);
            return true;
        }
        return false;
    }

    /** 玩家退出：清状态 */
    void handleQuit(UUID playerId) {
        bodyTemp.remove(playerId);
        lastHypothermia.remove(playerId);
    }

    /** 插件禁用：清全部状态 */
    void clear() {
        bodyTemp.clear();
        lastHypothermia.clear();
    }

    // ---------------- 环境目标推导 ----------------

    /**
     * 目标体温 = 群系档位基础值 + 夜间/浸水修正（着火直接 100）；
     * 目标低于热源保障值时扫描周围热源方块，命中则抬升到保障值。
     */
    private double target(Player player) {
        if (player.getFireTicks() > 0) {
            return 100.0;
        }
        Location location = player.getLocation();
        // 1.19.3 起 Biome 接口不再携带温度数据，改从世界按坐标读取
        double biomeTemp = player.getWorld().getTemperature(
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        double target = climateTarget(biomeTemp);
        if (settings.isNight(player.getWorld().getTime())) {
            target += settings.nightOffset;
        }
        if (isWet(player)) {
            target += settings.wetOffset;
        }
        if (target < settings.heatGuarantee && nearHeatSource(player, location)) {
            target = settings.heatGuarantee;
        }
        return Math.max(0.0, Math.min(100.0, target));
    }

    /** 群系温度 → 基础目标体温（升序档位表匹配） */
    private double climateTarget(double biomeTemp) {
        for (SurvivalSettings.Climate climate : settings.climates) {
            if (biomeTemp <= climate.maxBiomeTemp()) {
                return climate.target();
            }
        }
        return settings.climates.get(settings.climates.size() - 1).target();
    }

    /** 在水中、或淋雨且头顶露天 */
    private boolean isWet(Player player) {
        if (player.isInWater()) {
            return true;
        }
        World world = player.getWorld();
        if (!world.hasStorm()) {
            return false;
        }
        Location location = player.getLocation();
        return world.getHighestBlockYAt(location) <= location.getY();
    }

    /** 扫描玩家周围立方体（半径 r）内是否有热源方块；仅寒冷时调用，代价可控 */
    private boolean nearHeatSource(Player player, Location location) {
        int r = settings.heatRadius;
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        for (Material heat : settings.heatBlocks) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Block block = player.getWorld().getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                        if (block.getType() == heat) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}

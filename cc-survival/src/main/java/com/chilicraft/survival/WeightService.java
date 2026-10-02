package com.chilicraft.survival;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 负重服务：物品变动时标记脏位，由 1 秒周期任务在脏位上重算，避免高频事件重算与事件时序问题。
 * 所有方法仅主线程调用。
 */
final class WeightService {

    private final SurvivalSettings settings;

    /** 玩家当前负重（kg） */
    private final Map<UUID, Double> weights = new HashMap<>();

    /** 物品栏变动待重算的玩家 */
    private final Set<UUID> dirty = new HashSet<>();

    WeightService(SurvivalSettings settings) {
        this.settings = settings;
    }

    /** 物品变动（点击 / 拖拽 / 丢弃 / 拾取 / 加入）时标记重算 */
    void markDirty(UUID playerId) {
        dirty.add(playerId);
    }

    /** 消费脏位：有待重算返回 true 并清除标记 */
    boolean consumeDirty(UUID playerId) {
        return dirty.remove(playerId);
    }

    /** 重算玩家负重并缓存 */
    void recalculate(Player player) {
        PlayerInventory inventory = player.getInventory();
        double total = 0.0;
        for (ItemStack item : inventory.getStorageContents()) {
            total += itemWeight(item);
        }
        total += itemWeight(inventory.getItemInOffHand());
        weights.put(player.getUniqueId(), total);
    }

    /**
     * 负重占上限比例（0 = 空，1 = 满载）。
     * 上限 = 配置 limit × 核心模式参数（WEIGHT_LIMIT）。
     */
    double ratio(UUID playerId, double effectiveLimit) {
        if (effectiveLimit <= 0) {
            return 0.0;
        }
        Double weight = weights.get(playerId);
        return weight == null ? 0.0 : Math.max(0.0, weight / effectiveLimit);
    }

    private double itemWeight(ItemStack item) {
        if (item == null) {
            return 0.0;
        }
        Material type = item.getType();
        if (type.isAir() || item.getAmount() <= 0) {
            return 0.0;
        }
        double perItem = settings.weightValues.getOrDefault(type, settings.defaultPerItem);
        return perItem * item.getAmount();
    }

    /** 玩家退出：清状态 */
    void handleQuit(UUID playerId) {
        weights.remove(playerId);
        dirty.remove(playerId);
    }

    /** 插件禁用：清全部状态 */
    void clear() {
        weights.clear();
        dirty.clear();
    }
}

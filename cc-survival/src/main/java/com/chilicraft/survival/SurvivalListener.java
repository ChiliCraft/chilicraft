package com.chilicraft.survival;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.ParamKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Random;
import java.util.UUID;

/**
 * 生命周期与物品变动监听：退出清理 / 重生重置 / 饥饿减缓 / 负重脏位标记。
 *
 * <p>高频事件（点击/拖拽/拾取）只做 Set.add 脏位标记，
 * 重算统一交给 1 秒周期任务消费（规格红线：不在高频事件里做昂贵操作）。</p>
 */
final class SurvivalListener implements Listener {

    private static final Random RANDOM = new Random();

    private final SurvivalSettings settings;
    private final WeightService weight;
    private final TemperatureService temperature;
    private final ChiliCraftAPI api;

    SurvivalListener(SurvivalSettings settings, WeightService weight,
                     TemperatureService temperature, ChiliCraftAPI api) {
        this.settings = settings;
        this.weight = weight;
        this.temperature = temperature;
        this.api = api;
    }

    /** 加入：标记负重待重算（脏位由周期任务消费） */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        weight.markDirty(event.getPlayer().getUniqueId());
    }

    /** 退出：清理负重与体温状态（服务以 UUID 为键，退出即清防泄漏） */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        weight.handleQuit(playerId);
        temperature.handleQuit(playerId);
    }

    /** 重生：体温回满到初值，失温计时器清零 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        temperature.reset(event.getPlayer().getUniqueId());
    }

    /**
     * 饥饿减缓：总倍率（模块 × 模式参数 × 夜间）&lt; 1 时（如 cozy 0.45），
     * 对掉食按概率取消，等效整体掉食速率 ≈ 倍率。
     * 加速路径（倍率 &gt; 1）不在此处理，由周期任务施加额外耗竭。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!settings.hungerEnabled || !(event.getEntity() instanceof Player player)) {
            return;
        }
        // 只干预掉食（数值下降）；进食回复不碰
        if (event.getFoodLevel() >= player.getFoodLevel()) {
            return;
        }
        double mult = settings.hungerMultiplier
                * api.getParam(player.getUniqueId(), ParamKey.HUNGER_DECAY);
        if (settings.isNight(player.getWorld().getTime())) {
            mult *= settings.nightMultiplier;
        }
        if (mult >= 1.0) {
            return;
        }
        // 取消掉食时原版不清空耗竭值，耗竭持续累积会再次触发掉食判定，
        // 因此按 mult 概率放行即可等效整体速率 mult
        if (RANDOM.nextDouble() >= mult) {
            event.setCancelled(true);
        }
    }

    // ---------------- 负重脏位：物品变动只做标记，重算交给周期任务 ----------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            weight.markDirty(player.getUniqueId());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            weight.markDirty(player.getUniqueId());
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        weight.markDirty(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            weight.markDirty(player.getUniqueId());
        }
    }

    /** 物品耗尽销毁（如工具用完消失）也改变负重 */
    @EventHandler
    public void onItemBreak(PlayerItemBreakEvent event) {
        weight.markDirty(event.getPlayer().getUniqueId());
    }
}

package com.chilicraft.survival;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.ParamKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;

/**
 * 耐久损耗监听：PlayerItemDamageEvent 按总倍率（模块 × 模式参数）缩放。
 * 减免后四舍五入为 0 的单点损耗直接取消（等效本次不损耗，如 cozy 0.45）。
 */
final class DurabilityListener implements Listener {

    private final SurvivalSettings settings;
    private final ChiliCraftAPI api;

    DurabilityListener(SurvivalSettings settings, ChiliCraftAPI api) {
        this.settings = settings;
        this.api = api;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (!settings.durabilityEnabled) {
            return;
        }
        double mult = settings.durabilityMultiplier
                * api.getParam(event.getPlayer().getUniqueId(), ParamKey.DURABILITY_COST);
        if (mult == 1.0) {
            return;
        }
        int scaled = (int) Math.round(event.getDamage() * mult);
        if (scaled <= 0) {
            event.setCancelled(true);
        } else {
            event.setDamage(scaled);
        }
    }
}

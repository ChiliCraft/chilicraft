package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 灵魂附属事件总线：九事件接线。
 *
 * <p>全部为「早退优先」的薄接线层——进入方法先判断归属与前置条件，
 * 昂贵逻辑一律下沉到各服务；效果触发统一经 {@link RelicService#consume}
 * 消耗 1 灵韵（沉眠提示由服务内部处理，本层不发消息）。</p>
 *
 * <ul>
 *   <li>AsyncChat（异步线程）：临终遗言采集（服务内并发容器）</li>
 *   <li>PlayerDeath：死亡结算 + 葬礼中断（死亡即离开仪式点语义）</li>
 *   <li>PlayerRespawn：归还死亡保留物品</li>
 *   <li>PlayerQuit：遗物/遗言清理 + 葬礼中断</li>
 *   <li>EntityPickupItem：遗物持有者登记；非玩家捡遗物一律取消（防破坏唯一性追踪）</li>
 *   <li>EntityDamage：雨阻 / 摔落减免 / 饿魔庇护 / 通用减伤（总减免封顶 80%，逐项独立消耗）</li>
 *   <li>FoodLevelChange：自然消耗按概率减免</li>
 *   <li>PlayerItemConsume：饮用回复（按最大生命比例）</li>
 *   <li>EntityDeath：kill_soul 概率「缠上一缕」（+1 灵魂）</li>
 * </ul>
 */
final class SoulListener implements Listener {

    private final ChiliCraftAPI api;
    private final RelicService relics;
    private final DeathService deaths;
    private final FuneralService funeral;

    SoulListener(ChiliCraftAPI api, RelicService relics, DeathService deaths, FuneralService funeral) {
        this.api = api;
        this.relics = relics;
        this.deaths = deaths;
        this.funeral = funeral;
    }

    // ---------------- 临终遗言采集（异步线程，服务内并发容器） ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        deaths.noteChat(event.getPlayer().getUniqueId(), event.message());
    }

    // ---------------- 死亡 / 重生 / 退出 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        deaths.handleDeath(event);
        // 死亡视为离开仪式点：中断进行中的葬礼（费用不退，服务内提示）
        funeral.interrupt(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        deaths.handleRespawn(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        relics.handleQuit(player);
        deaths.handleQuit(player);
        funeral.interrupt(player.getUniqueId());
    }

    // ---------------- 遗物流转：拾取登记 / 唯一性保护 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        if (!relics.isRelic(stack)) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            relics.onPickup(player, stack);
        } else {
            // 怪物 / 盔甲架等非玩家实体不得捡走全服唯一遗物
            event.setCancelled(true);
        }
    }

    // ---------------- 伤害减免四钩子（高频事件：先汇总加成，无效果直接早退） ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        double total = 0.0;
        boolean hitRain = false;
        boolean hitFall = false;
        boolean hitDemon = false;
        boolean hitGeneric = false;

        double bonus = relics.effectBonus(player, RelicEffect.RAIN_RESIST);
        if (bonus > 0 && relics.isRainingOn(player)) {
            total += bonus;
            hitRain = true;
        }
        bonus = relics.effectBonus(player, RelicEffect.FALL_RESIST);
        if (bonus > 0 && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            total += bonus;
            hitFall = true;
        }
        bonus = relics.effectBonus(player, RelicEffect.DEMON_WARD);
        if (bonus > 0 && event instanceof EntityDamageByEntityEvent byEntity
                && relics.isDemon(byEntity.getDamager())) {
            total += bonus;
            hitDemon = true;
        }
        bonus = relics.effectBonus(player, RelicEffect.GENERIC_RESIST);
        if (bonus > 0) {
            total += bonus;
            hitGeneric = true;
        }

        if (total <= 0) {
            return;
        }
        // 多遗物叠加封顶 80%，保底仍有伤害
        double reduction = Math.min(0.8, total);
        event.setDamage(Math.max(0.0, event.getDamage() * (1.0 - reduction)));
        // 命中的每个效果独立消耗 1 灵韵
        if (hitRain) {
            relics.consume(player, RelicEffect.RAIN_RESIST);
        }
        if (hitFall) {
            relics.consume(player, RelicEffect.FALL_RESIST);
        }
        if (hitDemon) {
            relics.consume(player, RelicEffect.DEMON_WARD);
        }
        if (hitGeneric) {
            relics.consume(player, RelicEffect.GENERIC_RESIST);
        }
    }

    // ---------------- 饱食度保持：仅自然消耗时按概率减免 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getItem() != null || event.getFoodLevel() >= player.getFoodLevel()) {
            // 进食不算消耗；只在饥饿值下降（自然消耗）时判定
            return;
        }
        double keep = relics.effectBonus(player, RelicEffect.HUNGER_KEEP);
        if (keep <= 0 || Math.random() >= keep) {
            return;
        }
        event.setCancelled(true);
        relics.consume(player, RelicEffect.HUNGER_KEEP);
    }

    // ---------------- 饮用回复：药水 / 牛奶 / 蜂蜜瓶 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Material type = event.getItem().getType();
        if (type != Material.POTION && type != Material.MILK_BUCKET && type != Material.HONEY_BOTTLE) {
            return;
        }
        Player player = event.getPlayer();
        double heal = relics.effectBonus(player, RelicEffect.DRINK_HEAL);
        if (heal <= 0) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        relics.heal(player, maxHealth.getValue() * heal);
        relics.consume(player, RelicEffect.DRINK_HEAL);
    }

    // ---------------- 收割缠缕：kill_soul 概率 +1 灵魂 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            // 击杀玩家不走此通道（灵魂变动由死亡结算负责）
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        double chance = relics.effectBonus(killer, RelicEffect.KILL_SOUL);
        if (chance <= 0 || Math.random() >= chance) {
            return;
        }
        api.addSoul(killer.getUniqueId(), 1, "cc-soul:relic-kill");
        relics.consume(killer, RelicEffect.KILL_SOUL);
    }
}

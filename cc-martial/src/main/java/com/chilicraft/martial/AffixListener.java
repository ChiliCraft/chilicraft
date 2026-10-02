package com.chilicraft.martial;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 词缀事件钩子结算：ATTACK + DAMAGED（单 handler 双侧）/ HUNGER / WATER / KILL + 掉落 roll。
 *
 * <p>全部 HIGH 优先级（修改伤害数值、取消事件、修改掉落均不允许 MONITOR 后操作）；
 * 伤害事件 ignoreCancelled 防止对已取消事件结算。荆棘反伤用
 * {@link LivingEntity#damage(double)}（CUSTOM cause，不重入 ByEntity 监听器，无递归）。</p>
 */
final class AffixListener implements Listener {

    private final AffixService service;

    AffixListener(AffixService service) {
        this.service = service;
    }

    // ================= 命中 / 受击 =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        double damage = event.getDamage();

        // ATTACK：攻击者主手词缀（近战或箭矢；不结算内战）
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            for (AffixContent.AffixDef def : service.equippedAffixes(attacker, AffixHook.ATTACK)) {
                if (!rollChance(def)) {
                    continue;
                }
                damage += def.param("bonus-damage", 0);
                double burn = def.param("burn-seconds", 0);
                if (burn > 0) {
                    victim.setFireTicks(Math.max(victim.getFireTicks(), (int) (burn * 20)));
                }
                applyTaggedPotion(victim, def);
                double heal = def.param("heal", 0);
                if (heal > 0) {
                    healPlayer(attacker, heal);
                }
                double hunger = def.param("self-hunger", 0);
                if (hunger > 0) {
                    consumeHunger(attacker, hunger);
                }
            }
        }

        // DAMAGED：victim 装备词缀（主手 + 四盔甲；仅实体伤害）
        for (AffixContent.AffixDef def : service.equippedAffixes(victim, AffixHook.DAMAGED)) {
            if (!rollChance(def)) {
                continue;
            }
            double reduce = def.param("reduce-pct", 0);
            if (reduce > 0) {
                damage *= 1.0 - Math.min(100.0, reduce) / 100.0;
            }
            double thorns = def.param("thorns", 0);
            if (thorns > 0) {
                LivingEntity thornTarget = attacker != null
                        ? attacker
                        : event.getDamager() instanceof LivingEntity le ? le : null;
                if (thornTarget != null) {
                    thornTarget.damage(thorns);
                }
            }
            applyTaggedPotion(attacker, def);
            double heal = def.param("heal", 0);
            if (heal > 0) {
                healPlayer(victim, heal);
            }
        }

        if (damage != event.getDamage()) {
            event.setDamage(damage);
        }
    }

    // ================= 饱食 =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        List<AffixContent.AffixDef> defs = service.equippedAffixes(player, AffixHook.HUNGER);
        if (defs.isEmpty()) {
            return;
        }
        ItemStack consumed = event.getItem();
        if (consumed != null) {
            // 进食：回血
            for (AffixContent.AffixDef def : defs) {
                double heal = def.param("heal-on-eat", 0);
                if (heal > 0) {
                    healPlayer(player, heal);
                }
            }
        } else if (event.getFoodLevel() < player.getFoodLevel()) {
            // 自然消耗：save-chance 取消本次降低
            for (AffixContent.AffixDef def : defs) {
                double save = def.param("save-chance", 0);
                if (save > 0 && ThreadLocalRandom.current().nextDouble(100) < save) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // ================= 氧气 =================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAir(EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        List<AffixContent.AffixDef> defs = service.equippedAffixes(player, AffixHook.WATER);
        // 最大氧气维护（卸下词缀后回落原版默认 300）
        double bonus = 0;
        for (AffixContent.AffixDef def : defs) {
            bonus = Math.max(bonus, def.param("max-air-bonus", 0));
        }
        int expected = 300 + (int) bonus;
        if (player.getMaximumAir() != expected) {
            player.setMaximumAir(expected);
            if (bonus <= 0 && player.getRemainingAir() > expected) {
                player.setRemainingAir(expected);
            }
        }
        if (event.getAmount() < player.getRemainingAir()) {
            // 消耗中：save-chance 取消本次消耗
            for (AffixContent.AffixDef def : defs) {
                double save = def.param("save-chance", 0);
                if (save > 0 && ThreadLocalRandom.current().nextDouble(100) < save) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    // ================= 击杀 + 掉落 =================

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        // KILL 词缀结算（主手）
        for (AffixContent.AffixDef def : service.equippedAffixes(killer, AffixHook.KILL)) {
            double heal = def.param("heal", 0);
            if (heal > 0) {
                healPlayer(killer, heal);
            }
            double food = def.param("food", 0);
            if (food > 0 && killer.getFoodLevel() < 20) {
                killer.setFoodLevel(Math.min(20, killer.getFoodLevel() + (int) food));
            }
            applyTaggedPotion(killer, def);
        }
        // 掉落 roll（武器工具单物品单次）
        service.rollDropLoot(killer, event.getDrops());
    }

    // ================= 静态工具 =================

    /** 触发概率 roll（chance 缺省 100 = 必发；值域 0-100 百分比） */
    static boolean rollChance(AffixContent.AffixDef def) {
        double chance = def.param("chance", 100);
        return chance >= 100 || ThreadLocalRandom.current().nextDouble(100) < chance;
    }

    /** 应用 potion 三件套（potion 名 + amplifier + duration-seconds，tag 缺失静默跳过） */
    static void applyTaggedPotion(LivingEntity target, AffixContent.AffixDef def) {
        if (target == null) {
            return;
        }
        String potionName = def.tag("potion", "");
        if (potionName.isEmpty()) {
            return;
        }
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(potionName.toLowerCase(Locale.ROOT)));
        if (type != null) {
            target.addPotionEffect(new PotionEffect(
                    type,
                    (int) def.param("duration-seconds", 3) * 20,
                    (int) def.param("amplifier", 0),
                    true, false, true));
        }
    }

    /** 伤害来源玩家归属（直伤玩家 / 投射物射手为玩家；其余返回 null） */
    static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** 回血（上限封顶 GENERIC_MAX_HEALTH 当前值） */
    static void healPlayer(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        double max = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
                ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
                : 20.0;
        if (player.getHealth() > 0 && player.getHealth() < max) {
            player.setHealth(Math.min(max, player.getHealth() + amount));
        }
    }

    /** 消耗饱食度（先扣 saturation，不足部分进位扣 1 点 foodLevel） */
    static void consumeHunger(Player player, double amount) {
        if (amount <= 0) {
            return;
        }
        float sat = player.getSaturation();
        if (sat >= amount) {
            player.setSaturation(sat - (float) amount);
            return;
        }
        player.setSaturation(0);
        player.setFoodLevel(Math.max(0, player.getFoodLevel() - 1));
    }
}

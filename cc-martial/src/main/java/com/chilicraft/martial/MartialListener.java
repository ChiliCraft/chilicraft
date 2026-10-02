package com.chilicraft.martial;

import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 武学监听器：档案生命周期 / 武学经验入账 / 战斗结算
 * （境界加成 + 技能被动 + 近战 buff 消耗 + 吸血）。
 *
 * <p>高频事件（伤害）内只做类型判断与数值结算，禁止昂贵操作。</p>
 */
final class MartialListener implements Listener {

    /** 减伤上限：境界减伤与被动减伤相加后封顶，防止叠成完全免疫 */
    private static final double MAX_REDUCTION = 0.8;
    /** 吸血比例上限（%） */
    private static final double MAX_LIFESTEAL_PCT = 100.0;

    private final MartialSettings settings;
    private final RealmService realms;
    private final SkillService skills;

    MartialListener(MartialSettings settings, RealmService realms, SkillService skills) {
        this.settings = settings;
        this.realms = realms;
        this.skills = skills;
    }

    // ---------------- 档案生命周期 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        realms.load(player.getUniqueId());
        skills.load(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        realms.handleQuit(player);
        skills.handleQuit(player);
    }

    // ---------------- 击杀：境界计数 + 武学经验 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.getKiller() instanceof Player)) {
            return;
        }
        Player killer = (Player) entity.getKiller();
        realms.recordKill(killer);
        skills.addXp(killer.getUniqueId(), settings.skillXpKill);
    }

    // ---------------- 战斗结算 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        double damage = event.getDamage();
        Player attacker = event.getDamager() instanceof Player ? (Player) event.getDamager() : null;
        Player victim = event.getEntity() instanceof Player ? (Player) event.getEntity() : null;

        // 进攻方为玩家：近战 buff 附加 → 境界加成 → 暴击判定
        if (attacker != null) {
            UUID attackerId = attacker.getUniqueId();
            // 一次性近战 buff（STRIKE 附加伤害，消耗后即使为 0 也已取走）
            double strikeBonus = skills.consumeStrikeBonus(attackerId);
            if (strikeBonus > 0) {
                damage += strikeBonus;
            }
            // 境界近战加成
            double meleePct = realms.meleeDamagePct(attackerId);
            if (meleePct > 0) {
                damage *= 1 + meleePct / 100.0;
            }
            // 暴击（CRIT_CHANCE 被动：概率触发，倍率不随等级缩放）
            double critPct = skills.passiveValue(attackerId, SkillType.CRIT_CHANCE);
            if (critPct > 0 && ThreadLocalRandom.current().nextDouble(100) < critPct) {
                damage *= skills.critMultiplier(attackerId);
                attacker.getWorld().spawnParticle(Particle.CRIT,
                        attacker.getLocation().add(0, 1.0, 0), 8, 0.3, 0.3, 0.3, 0.1);
            }
        }

        // 受击方为玩家：境界减伤 + 被动减伤（相加后封顶）
        if (victim != null) {
            UUID victimId = victim.getUniqueId();
            double totalPct = realms.damageReductionPct(victimId)
                    + skills.passiveValue(victimId, SkillType.DAMAGE_REDUCTION);
            if (totalPct > 0) {
                damage *= 1 - Math.min(MAX_REDUCTION, totalPct / 100.0);
            }
        }

        if (damage != event.getDamage()) {
            event.setDamage(Math.max(0.0, damage));
        }

        // 吸血（LIFESTEAL 被动 + LIFESTEAL_STRIKE buff，基于最终伤害）
        if (attacker != null && victim != null) {
            UUID attackerId = attacker.getUniqueId();
            double lifestealPct = skills.passiveValue(attackerId, SkillType.LIFESTEAL)
                    + skills.consumeLifestealStrike(attackerId);
            if (lifestealPct > 0) {
                double heal = Math.min(MAX_LIFESTEAL_PCT, lifestealPct) / 100.0
                        * Math.max(0.0, event.getDamage());
                if (heal > 0) {
                    AttributeInstance attr = attacker.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    double max = attr != null ? attr.getValue() : 20.0;
                    if (attacker.getHealth() > 0) {
                        attacker.setHealth(Math.min(max, attacker.getHealth() + heal));
                    }
                }
            }
        }
    }
}

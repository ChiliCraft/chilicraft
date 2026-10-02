package com.chilicraft.martial;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 技能效果执行器（静态工具）：即时型主动技能结算 + 原版音效/粒子播放。
 *
 * <p>buff 型主动（STRIKE / LIFESTEAL_STRIKE / SHIELD）由 {@code SkillService}
 * 直接管理，不经过本类。所有方法仅主线程调用。</p>
 */
final class SkillExecutor {

    private SkillExecutor() {
    }

    /**
     * 执行即时型主动技能；返回 false 表示未实际生效（不进冷却）。
     * 数值按等级缩放：参数 × (1 + 0.1 × (level-1))。
     */
    static boolean execute(Player player, SkillContent.SkillDef def, int level) {
        double scale = 1.0 + 0.1 * (level - 1);
        switch (def.type) {
            case AOE_DAMAGE -> {
                double radius = def.param("radius", 3.0);
                double damage = def.param("damage", 6.0) * scale;
                double knockback = def.param("knockback", 0.0);
                for (LivingEntity target : nearbyTargets(player, radius)) {
                    target.damage(damage, player);
                    if (knockback > 0) {
                        applyKnockback(player, target, knockback);
                    }
                }
                return true;
            }
            case DASH -> {
                double speed = def.param("speed", 2.0);
                Vector dir = player.getLocation().getDirection().clone();
                dir.setY(0);
                if (dir.lengthSquared() < 1.0E-4) {
                    dir = new Vector(0, 0, 1);
                }
                player.setVelocity(dir.normalize().multiply(speed).setY(0.2));
                return true;
            }
            case HEAL -> {
                double amount = def.param("amount", 6.0) * scale;
                AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double max = attr != null ? attr.getValue() : 20.0;
                if (player.getHealth() >= max) {
                    return false; // 满血不施展（不进冷却）
                }
                player.setHealth(Math.min(max, player.getHealth() + amount));
                return true;
            }
            case SPEED_BUFF -> {
                int amplifier = (int) def.param("amplifier", 1);
                int duration = (int) def.param("duration-ticks", 100);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, amplifier, true, false, true));
                return true;
            }
            case SLOW_FIELD -> {
                double radius = def.param("radius", 4.0);
                int amplifier = (int) def.param("amplifier", 1);
                int duration = (int) def.param("duration-ticks", 80);
                int poison = (int) def.param("poison", 0);
                for (LivingEntity target : nearbyTargets(player, radius)) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, amplifier, true, false, true));
                    if (poison > 0) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poison * 20, 0, true, false, true));
                    }
                }
                return true;
            }
            case PULL -> {
                double radius = def.param("radius", 4.0);
                for (LivingEntity target : nearbyTargets(player, radius)) {
                    Vector delta = player.getLocation().toVector().subtract(target.getLocation().toVector());
                    delta.setY(0);
                    if (delta.lengthSquared() > 1.0E-4) {
                        target.setVelocity(delta.normalize().multiply(1.2).setY(0.25));
                    }
                }
                return true;
            }
            case LEAP -> {
                double power = def.param("power", 1.0);
                double damage = def.param("damage", 6.0) * scale;
                double radius = def.param("radius", 3.0);
                Vector dir = player.getLocation().getDirection().clone();
                dir.setY(Math.max(0.35, dir.getY() * 0.4 + 0.25));
                if (dir.lengthSquared() < 1.0E-4) {
                    dir = new Vector(0, 1, 0);
                }
                player.setVelocity(dir.normalize().multiply(power + 0.6));
                for (LivingEntity target : nearbyTargets(player, radius)) {
                    target.damage(damage, player);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 施展反馈：原版音效 + 粒子（配置名非法时静默跳过） */
    static void playFx(Player player, SkillContent.SkillDef def) {
        if (!def.sound.isEmpty()) {
            try {
                player.getWorld().playSound(player.getLocation(), Sound.valueOf(def.sound), 1.0f, 1.0f);
            } catch (IllegalArgumentException ignored) {
                // 配置音效名无效：静默跳过
            }
        }
        if (!def.particle.isEmpty()) {
            try {
                player.getWorld().spawnParticle(Particle.valueOf(def.particle),
                        player.getLocation().add(0, 1.0, 0), 16, 0.5, 0.5, 0.5, 0.05);
            } catch (IllegalArgumentException ignored) {
                // 配置粒子名无效：静默跳过
            }
        }
    }

    /** 范围内有效目标（排除自己/盔甲架/死亡实体；含其他玩家，PvP 由服务端规则管） */
    static Collection<LivingEntity> nearbyTargets(Player center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity)) {
                continue;
            }
            LivingEntity living = (LivingEntity) entity;
            if (living.getUniqueId().equals(center.getUniqueId())
                    || living.isDead() || !living.isValid()
                    || living instanceof ArmorStand) {
                continue;
            }
            out.add(living);
        }
        return out;
    }

    private static void applyKnockback(Player source, LivingEntity target, double strength) {
        Vector delta = target.getLocation().toVector().subtract(source.getLocation().toVector());
        delta.setY(0);
        if (delta.lengthSquared() > 1.0E-4) {
            target.setVelocity(delta.normalize().multiply(strength).setY(0.3));
        }
    }
}

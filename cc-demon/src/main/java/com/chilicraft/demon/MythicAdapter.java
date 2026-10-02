package com.chilicraft.demon;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MythicMobs 适配器：饿魔技能与行为接管（规格 v1.1：MM 在场时技能与行为由 MM 接管，仍用原版模型）。
 *
 * <p>类引用隔离原则：{@link #spawnMob} 内的 MM 类引用只在调用方确认
 * {@link #available} 为真（插件在场 + 开关开启）后才会触达；
 * 所有调用 catch Throwable，联动失败静默降级（调用方回退原版属性修改器路径）。</p>
 */
final class MythicAdapter {

    private MythicAdapter() {
    }

    /** MM 在场且 integration.mythicmobs 开启 */
    static boolean available(JavaPlugin plugin, DemonSettings settings) {
        if (!settings.integrationMythicMobs) {
            return false;
        }
        return plugin.getServer().getPluginManager().isPluginEnabled("MythicMobs");
    }

    /**
     * 按 MM 内部 ID 生成一只 Mythic 生物并返回其 Bukkit 实体。
     * ID 未定义 / 生成异常返回 null，调用方回退原版生物改造路径。
     */
    static Mob spawnMob(String mythicId, Location location) {
        try {
            // MM 5.x MobManager#spawnMob(String, Location) 直接返回 ActiveMob（生成失败返回 null）
            ActiveMob spawned = MythicBukkit.inst().getMobManager().spawnMob(mythicId, location);
            if (spawned == null) {
                return null;
            }
            Entity bukkit = spawned.getEntity().getBukkitEntity();
            return bukkit instanceof Mob mob ? mob : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

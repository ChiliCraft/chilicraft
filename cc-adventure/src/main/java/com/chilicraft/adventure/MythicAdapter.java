package com.chilicraft.adventure;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;

/** MythicMobs 可选适配器；联动失败时由调用方回退原版生成。 */
final class MythicAdapter {

    private MythicAdapter() {
    }

    static boolean available(JavaPlugin plugin, AdventureSettings settings) {
        return settings.integrationMythicMobs
                && plugin.getServer().getPluginManager().isPluginEnabled("MythicMobs");
    }

    static Mob spawnMob(String mythicId, Location location) {
        try {
            ActiveMob spawned = MythicBukkit.inst().getMobManager().spawnMob(mythicId, location);
            if (spawned == null) {
                return null;
            }
            Entity bukkit = spawned.getEntity().getBukkitEntity();
            return bukkit instanceof Mob mob ? mob : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

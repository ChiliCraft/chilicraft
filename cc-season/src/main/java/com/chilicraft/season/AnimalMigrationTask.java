package com.chilicraft.season;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.concurrent.ThreadLocalRandom;

final class AnimalMigrationTask implements Runnable {

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;

    AnimalMigrationTask(ChiliSeasonPlugin plugin, SeasonSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void run() {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getLogger().warning("动物迁徙任务误入异步线程，本轮已跳过");
            return;
        }
        if (!settings.migrationEnabled) {
            return;
        }
        for (String worldName : settings.migrationWorlds) {
            World world = plugin.getServer().getWorld(worldName);
            if (world != null) {
                migrateWorld(world);
            }
        }
    }

    private void migrateWorld(World world) {
        Location spawn = world.getSpawnLocation();
        double radiusSquared = (double) settings.migrationRadius * settings.migrationRadius;
        int migrated = 0;
        for (Entity entity : world.getNearbyEntities(
                spawn, settings.migrationRadius, world.getMaxHeight() - world.getMinHeight(),
                settings.migrationRadius)) {
            if (migrated >= settings.migrationMax) {
                break;
            }
            try {
                if (!settings.animals.contains(entity.getType().name())) {
                    continue;
                }
                Location current = entity.getLocation();
                if (horizontalDistanceSquared(current, spawn) > radiusSquared) {
                    continue;
                }
                Location target = findSafeTarget(world, current);
                if (target != null && entity.teleport(target)) {
                    migrated++;
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning(
                        "动物迁徙处理失败（" + entity.getUniqueId() + "）：" + throwable.getMessage());
            }
        }
    }

    private Location findSafeTarget(World world, Location origin) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = random.nextDouble(
                    settings.migrationMinDistance, settings.migrationMaxDistance + 1.0);
            int x = (int) Math.floor(origin.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(origin.getZ() + Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Location target = new Location(world, x + 0.5, y, z + 0.5, origin.getYaw(), origin.getPitch());
            if (target.getBlock().isPassable()
                    && target.clone().add(0, 1, 0).getBlock().isPassable()
                    && !target.clone().add(0, -1, 0).getBlock().isLiquid()) {
                return target;
            }
        }
        return null;
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}

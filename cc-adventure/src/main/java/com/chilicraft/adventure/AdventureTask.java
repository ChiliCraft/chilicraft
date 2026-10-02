package com.chilicraft.adventure;

import org.bukkit.scheduler.BukkitRunnable;

/** 冒险线统一每秒主循环。 */
final class AdventureTask extends BukkitRunnable {
    private final DungeonService dungeons;
    private final ExpeditionService expeditions;
    private final BossService bosses;

    AdventureTask(DungeonService dungeons, ExpeditionService expeditions, BossService bosses) {
        this.dungeons = dungeons;
        this.expeditions = expeditions;
        this.bosses = bosses;
    }

    @Override
    public void run() {
        dungeons.tick();
        expeditions.tick();
        bosses.tick();
    }
}

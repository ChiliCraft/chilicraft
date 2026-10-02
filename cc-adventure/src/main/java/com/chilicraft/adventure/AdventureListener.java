package com.chilicraft.adventure;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 冒险线低频 Bukkit 事件入口。 */
final class AdventureListener implements Listener {
    private final PartyService parties;
    private final DungeonService dungeons;
    private final ExpeditionService expeditions;
    private final BossService bosses;

    AdventureListener(PartyService parties, DungeonService dungeons,
                      ExpeditionService expeditions, BossService bosses) {
        this.parties = parties;
        this.dungeons = dungeons;
        this.expeditions = expeditions;
        this.bosses = bosses;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();
        dungeons.handleQuit(id);
        expeditions.handleQuit(id);
        bosses.handleQuit(id);
        parties.handleQuit(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        var killer = event.getEntity().getKiller();
        bosses.handleDeath(event.getEntity(), killer == null ? null : killer.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        var id = event.getPlayer().getUniqueId();
        if (dungeons.handleInteract(id, event.getClickedBlock())
                || expeditions.handleInteract(id, event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }
}

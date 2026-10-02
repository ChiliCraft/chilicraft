package com.chilicraft.martial;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 擂台事件监听：死亡判负、重生归还快照、退赛/上线处理、边界拉回。
 *
 * <p>除上线归还外，全部回调先经 {@link ArenaService#isFighting} 快速分流，
 * 非对局玩家零开销返回；移动为高频事件，仅做引用比较与距离平方判断。</p>
 */
final class ArenaListener implements Listener {

    private final ArenaService arenas;

    ArenaListener(ArenaService arenas) {
        this.arenas = arenas;
    }

    /** 对局死亡：清掉落与经验（随身物品在快照中，防复制），对手判胜 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        // isTracked 兜底：同 tick 双亡时第二人的死亡事件到达前对局引用已清空，
        // 仍需清除掉落，否则快照归还 + 掉落拾取会复制物品
        if (!arenas.isFighting(dead.getUniqueId()) && !arenas.isTracked(dead.getUniqueId())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        arenas.handleDeath(dead);
    }

    /** 被淘汰者重生：延迟 1t 归还快照并传送回开打前位置 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        arenas.handleRespawn(event.getPlayer());
    }

    /** 退赛：报名期移除；对局中判负并当场归还快照 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        arenas.handleQuit(event.getPlayer());
    }

    /** 上线：归还在离线期间待归还的快照（离线淘汰者） */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        arenas.handleJoin(event.getPlayer());
    }

    /** 边界保护：对局玩家越出半径即拉回中心 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!arenas.isFighting(player.getUniqueId())) {
            return;
        }
        if (arenas.outOfBounds(player)) {
            arenas.pullBack(player);
        }
    }
}

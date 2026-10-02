package com.chilicraft.core.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

/**
 * 档案生命周期监听：登录预载 / 退出冲刷。
 * 事件薄壳，业务全部委托 ProfileManager。
 */
public final class ProfileListener implements Listener {

    private final ProfileManager profiles;
    private final Logger logger;

    public ProfileListener(ProfileManager profiles, Logger logger) {
        this.profiles = profiles;
        this.logger = logger;
    }

    /**
     * 登录预载：MONITOR 级别，尊重其他插件的拒登决定；
     * 档案加载失败时拒登——宁可拒登也不允许无档案进服。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            profiles.preload(event.getUniqueId());
        } catch (Exception e) {
            logger.error("玩家 {} 档案预载失败，已拒登", event.getUniqueId(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("档案加载失败，请稍后再试或联系管理员"));
        }
    }

    /** 退出：移除缓存并统一冲刷 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        profiles.handleQuit(event.getPlayer().getUniqueId());
    }
}

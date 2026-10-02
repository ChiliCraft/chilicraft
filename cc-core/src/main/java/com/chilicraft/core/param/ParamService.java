package com.chilicraft.core.param;

import com.chilicraft.api.GameMode;
import com.chilicraft.api.HomeZone;
import com.chilicraft.api.ParamKey;
import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.profile.CraftPlayerProfile;
import com.chilicraft.core.profile.ProfileManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 参数服务：模式基准值（config: params 段）+ 家园区加成。
 *
 * <p>getParam 是附属高频调用路径（饥饿衰减、耐久消耗、掉落监听等
 * 可能每事件触发），实现保持轻量：缓存查表 + volatile 读，
 * 仅 MOB_SPAWN_RATE 且玩家在线时才做一次家园区距离判定。</p>
 */
public final class ParamService {

    private final ProfileManager profiles;
    private final CoreConfig config;
    private final HomeZone homeZone;

    public ParamService(ProfileManager profiles, CoreConfig config, HomeZone homeZone) {
        this.profiles = profiles;
        this.config = config;
        this.homeZone = homeZone;
    }

    /**
     * 玩家当前生效参数（主线程）。
     * 档案不存在（离线/未预载）时按 config 默认模式取基准值，不套家园区加成。
     */
    public double param(UUID playerId, ParamKey key) {
        CraftPlayerProfile profile = playerId == null ? null : profiles.getProfile(playerId);
        GameMode mode = profile != null ? profile.mode() : config.defaultMode();
        double value = config.param(mode, key);
        // 家园区加成：仅刷怪倍率且玩家实际位于自家园区内
        if (key == ParamKey.MOB_SPAWN_RATE && profile != null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && homeZone.isInside(playerId, player.getLocation())) {
                value *= homeZone.mobMultiplier();
            }
        }
        return value;
    }
}

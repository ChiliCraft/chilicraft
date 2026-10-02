package com.chilicraft.core.param;

import com.chilicraft.api.HomeZone;
import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.profile.CraftPlayerProfile;
import com.chilicraft.core.profile.ProfileManager;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * 家园区服务实现。
 *
 * <p>数值直读 CoreConfig 的 volatile 快照字段，
 * /cc reload 后无需重建本实例即可读到最新值
 * （接口契约：调用方不得缓存本接口返回的坐标与数值）。</p>
 *
 * <p>判定规则：家锚点世界名相同且 XZ 平面距离² ≤ 半径²（不比较 Y，圆柱区域）。</p>
 */
public final class HomeZoneImpl implements HomeZone {

    private final ProfileManager profiles;
    private final CoreConfig config;

    public HomeZoneImpl(ProfileManager profiles, CoreConfig config) {
        this.profiles = profiles;
        this.config = config;
    }

    @Override
    public int radius() {
        return config.homeRadius();
    }

    @Override
    public double mobMultiplier() {
        return config.homeMobMultiplier();
    }

    @Override
    public boolean demonEnabled() {
        return config.homeDemonEnabled();
    }

    @Override
    public boolean isHomeSet(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        CraftPlayerProfile profile = profiles.getProfile(playerId);
        return profile != null && profile.isHomeSet();
    }

    @Override
    public boolean isInside(UUID playerId, Location location) {
        if (playerId == null || location == null) {
            return false;
        }
        CraftPlayerProfile profile = profiles.getProfile(playerId);
        if (profile == null || !profile.isHomeSet()) {
            return false;
        }
        World world = location.getWorld();
        if (world == null || !world.getName().equals(profile.homeWorld())) {
            return false;
        }
        double dx = location.getX() - profile.homeX();
        double dz = location.getZ() - profile.homeZ();
        double radius = config.homeRadius();
        return dx * dx + dz * dz <= radius * radius;
    }
}

package com.chilicraft.core.profile;

import com.chilicraft.api.GameMode;
import com.chilicraft.api.PlayerProfile;

import java.util.UUID;

/**
 * 档案可变实现：写操作仅允许在主线程执行（API 线程契约），
 * DB 线程只访问不可变快照 record，不触碰本对象。
 *
 * <p>写方法公开给核心内部服务（模式切换、灵魂货币、家园区等）使用；
 * 对附属只暴露 {@link PlayerProfile} 只读视图，附属永远接触不到可变写方法。</p>
 */
public final class CraftPlayerProfile implements PlayerProfile {

    private final UUID playerId;
    private final long createdAt;

    private volatile GameMode mode;
    private volatile int martialRealm;
    private volatile String profession = "";
    private volatile int soul;
    private volatile int seasonPoints;
    private volatile String homeWorld = "";
    private volatile double homeX;
    private volatile double homeY;
    private volatile double homeZ;
    private volatile long modeSwitchAt;
    private volatile long updatedAt;

    /** 脏标记：主线程读写，标记后等待自动保存/退出冲刷落库 */
    private boolean dirty;

    private CraftPlayerProfile(UUID playerId, GameMode mode, long createdAt) {
        this.playerId = playerId;
        this.mode = mode;
        this.createdAt = createdAt;
    }

    /** 新玩家建档（首刷即脏，保证首次落库） */
    static CraftPlayerProfile create(UUID playerId, GameMode mode, long now) {
        CraftPlayerProfile p = new CraftPlayerProfile(playerId, mode, now);
        p.updatedAt = now;
        p.dirty = true;
        return p;
    }

    /** 从 DB 快照恢复 */
    static CraftPlayerProfile fromSnapshot(ProfileSnapshot s) {
        CraftPlayerProfile p = new CraftPlayerProfile(s.playerId(), s.mode(), s.createdAt());
        p.martialRealm = s.martialRealm();
        p.profession = s.profession();
        p.soul = s.soul();
        p.seasonPoints = s.seasonPoints();
        p.homeWorld = s.homeWorld();
        p.homeX = s.homeX();
        p.homeY = s.homeY();
        p.homeZ = s.homeZ();
        p.modeSwitchAt = s.modeSwitchAt();
        p.updatedAt = s.updatedAt();
        return p;
    }

    // ---------- 只读视图（PlayerProfile 契约） ----------

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public GameMode mode() {
        return mode;
    }

    @Override
    public int martialRealm() {
        return martialRealm;
    }

    @Override
    public String profession() {
        return profession;
    }

    @Override
    public int soul() {
        return soul;
    }

    @Override
    public int seasonPoints() {
        return seasonPoints;
    }

    @Override
    public boolean isHomeSet() {
        return !homeWorld.isEmpty();
    }

    /** 家锚点世界名（空串 = 未设置）；核心内部家园区判定使用 */
    public String homeWorld() {
        return homeWorld;
    }

    /** 家锚点 X 坐标（未设置时 0） */
    public double homeX() {
        return homeX;
    }

    /** 家锚点 Y 坐标（未设置时 0） */
    public double homeY() {
        return homeY;
    }

    /** 家锚点 Z 坐标（未设置时 0） */
    public double homeZ() {
        return homeZ;
    }

    @Override
    public long modeSwitchAt() {
        return modeSwitchAt;
    }

    // ---------- 写方法（仅主线程；公开给核心内部服务，附属不可见本类） ----------

    public void setMode(GameMode mode) {
        this.mode = mode;
        markDirty();
    }

    public void setMartialRealm(int realm) {
        this.martialRealm = realm;
        markDirty();
    }

    public void setProfession(String profession) {
        this.profession = profession == null ? "" : profession;
        markDirty();
    }

    public void setSoul(int soul) {
        this.soul = soul;
        markDirty();
    }

    public void setSeasonPoints(int points) {
        this.seasonPoints = points;
        markDirty();
    }

    public void setHome(String world, double x, double y, double z) {
        this.homeWorld = world;
        this.homeX = x;
        this.homeY = y;
        this.homeZ = z;
        markDirty();
    }

    public void clearHome() {
        this.homeWorld = "";
        this.homeX = 0;
        this.homeY = 0;
        this.homeZ = 0;
        markDirty();
    }

    public void setModeSwitchAt(long at) {
        this.modeSwitchAt = at;
        markDirty();
    }

    // ---------- 快照与脏标记 ----------

    boolean isDirty() {
        return dirty;
    }

    private void markDirty() {
        dirty = true;
    }

    /** 原子地取出脏状态并复位（主线程调用） */
    boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    /** 构造不可变快照（主线程调用，安全发布给 DB 线程） */
    ProfileSnapshot snapshot() {
        return new ProfileSnapshot(playerId, mode, martialRealm, profession, soul, seasonPoints,
                homeWorld, homeX, homeY, homeZ, modeSwitchAt, createdAt, System.currentTimeMillis());
    }
}

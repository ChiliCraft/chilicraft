package com.chilicraft.core.profile;

import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.database.DatabaseManager;
import com.chilicraft.core.economy.LedgerEntry;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 档案管理器：缓存 + 脏标记 + 异步批量落库。
 *
 * <p>生命周期：</p>
 * <ul>
 *   <li>登录预载：AsyncPlayerPreLoginEvent（异步线程）经 DB 写队列
 *       串行读库，杜绝「退出冲刷未完成又回读旧值」的竞态；失败拒登。</li>
 *   <li>在线期间：全部写操作在主线程置脏标记。</li>
 *   <li>自动保存：主线程周期扫描脏档案 → 快照 → 提交 DB 线程批量写。</li>
 *   <li>退出：移除缓存 + 立即统一冲刷（幂等 UPSERT，多存无害）。</li>
 *   <li>关停：停任务 → 最后一次冲刷 → 由主类关闭 DB（写队列保证落盘）。</li>
 * </ul>
 *
 * <p>身份一律使用 UUID，绝不持有 Player 对象。</p>
 */
public final class ProfileManager {

    /** 台账缓冲冲刷阈值，防异常流量下内存增长 */
    private static final int LEDGER_FLUSH_THRESHOLD = 1000;

    private final Map<UUID, CraftPlayerProfile> cache = new ConcurrentHashMap<>();
    private final DatabaseManager db;
    private final ProfileRepository repository;
    private final CoreConfig config;
    private final Logger logger;

    /** 灵魂台账缓冲：仅主线程读写，随档案统一冲刷 */
    private final List<LedgerEntry> ledgerBuffer = new ArrayList<>();

    private BukkitTask autoSaveTask;

    public ProfileManager(DatabaseManager db, CoreConfig config, Logger logger) {
        this.db = db;
        this.repository = new ProfileRepository(db);
        this.config = config;
        this.logger = logger;
    }

    // ---------- 预载与访问 ----------

    /**
     * 登录预载（异步线程调用）。
     * 读经 DB 写队列串行化，保证读到最新已提交数据；
     * 失败向上抛异常，由监听器 disallow 拒登。
     */
    public void preload(UUID playerId) {
        if (cache.containsKey(playerId)) {
            return; // 并发登录/快速重连：沿用现有缓存
        }
        ProfileSnapshot snapshot = db.supply(() -> {
            try {
                return repository.load(playerId);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }).join();
        long now = System.currentTimeMillis();
        CraftPlayerProfile profile;
        if (snapshot == null) {
            profile = CraftPlayerProfile.create(playerId, config.defaultMode(), now);
            logger.info("为 {} 创建新档案（默认模式 {}）", playerId, config.defaultMode());
        } else {
            profile = CraftPlayerProfile.fromSnapshot(snapshot);
        }
        cache.putIfAbsent(playerId, profile);
    }

    /**
     * 档案访问（主线程）。
     * 仅保证在线玩家非 null；离线玩家返回 null（API 契约）。
     */
    public CraftPlayerProfile getProfile(UUID playerId) {
        return cache.get(playerId);
    }

    /** 任务奖励事务提交后，在主线程同步在线档案余额。 */
    public void applyQuestReward(UUID playerId, int balanceAfter) {
        CraftPlayerProfile profile = cache.get(playerId);
        if (profile != null) {
            profile.setSoul(balanceAfter);
        }
    }

    // ---------- 台账 ----------

    /** 记录灵魂流水（主线程）；超阈值立即冲刷，防内存增长 */
    public void recordLedger(LedgerEntry entry) {
        ledgerBuffer.add(entry);
        if (ledgerBuffer.size() >= LEDGER_FLUSH_THRESHOLD) {
            flushAll();
        }
    }

    // ---------- 冲刷 ----------

    /** 玩家退出（主线程）：移除缓存并立即统一冲刷，保证退出即落盘 */
    public void handleQuit(UUID playerId) {
        CraftPlayerProfile removed = cache.remove(playerId);
        doFlush(removed);
    }

    /** 统一冲刷全部脏档案 + 台账（主线程调用） */
    public void flushAll() {
        doFlush(null);
    }

    private void doFlush(CraftPlayerProfile removed) {
        List<ProfileSnapshot> dirty = new ArrayList<>();
        if (removed != null && removed.consumeDirty()) {
            dirty.add(removed.snapshot());
        }
        for (CraftPlayerProfile p : cache.values()) {
            if (p.consumeDirty()) {
                dirty.add(p.snapshot());
            }
        }
        List<LedgerEntry> ledger = List.of();
        if (!ledgerBuffer.isEmpty()) {
            ledger = new ArrayList<>(ledgerBuffer);
            ledgerBuffer.clear();
        }
        if (dirty.isEmpty() && ledger.isEmpty()) {
            return;
        }
        final List<LedgerEntry> finalLedger = ledger;
        db.write(() -> {
            try {
                repository.saveAll(dirty, finalLedger);
            } catch (SQLException e) {
                // 转为非受检异常，由 DatabaseManager.write 统一记录日志
                throw new CompletionException(e);
            }
        });
    }

    // ---------- 自动保存 ----------

    /** 启动自动保存周期任务（主线程 tick） */
    public void startAutoSave(JavaPlugin plugin) {
        long ticks = config.autoSaveIntervalTicks();
        autoSaveTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::flushAll, ticks, ticks);
    }

    /** 停止自动保存任务（reload / onDisable 时由主类调用） */
    public void stopAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }
}

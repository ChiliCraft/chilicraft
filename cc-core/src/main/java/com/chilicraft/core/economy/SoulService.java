package com.chilicraft.core.economy;

import com.chilicraft.core.profile.CraftPlayerProfile;
import com.chilicraft.core.profile.ProfileManager;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 灵魂货币服务：余额读写 + 台账流水。
 *
 * <p>线程契约：全部方法仅主线程调用（ChiliApiImpl 入口统一断言）。
 * 余额写在档案上（volatile + 脏标记），台账缓冲在 ProfileManager，
 * 两者随同一次冲刷进同一事务，保证 cc_soul_ledger.balance_after
 * 与 cc_players.soul 严格一致。</p>
 */
public final class SoulService {

    private final ProfileManager profiles;
    private final Logger logger;

    public SoulService(ProfileManager profiles, Logger logger) {
        this.profiles = profiles;
        this.logger = logger;
    }

    /** 余额；档案不存在（离线）返回 0 */
    public int getSoul(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        CraftPlayerProfile profile = profiles.getProfile(playerId);
        return profile == null ? 0 : profile.soul();
    }

    /**
     * 增加灵魂；amount 为负表示无校验扣减（结果下限 0，不出负余额）。
     * amount 为 0 时无操作。档案不存在时记警告并忽略。
     */
    public void addSoul(UUID playerId, int amount, String reason) {
        if (amount == 0) {
            return;
        }
        CraftPlayerProfile profile = lookup(playerId, "addSoul");
        if (profile == null) {
            return;
        }
        int balanceAfter = Math.max(0, profile.soul() + amount);
        apply(profile, amount, balanceAfter, reason);
    }

    /**
     * 余额校验扣减。
     * 余额不足或档案不存在返回 false 且零变更；扣减成功记台账并返回 true。
     *
     * @throws IllegalArgumentException amount 为负
     */
    public boolean spendSoul(UUID playerId, int amount, String reason) {
        if (amount < 0) {
            throw new IllegalArgumentException("spendSoul 金额不能为负: " + amount);
        }
        if (amount == 0) {
            return true;
        }
        CraftPlayerProfile profile = lookup(playerId, "spendSoul");
        if (profile == null) {
            return false;
        }
        int balance = profile.soul();
        if (balance < amount) {
            return false;
        }
        apply(profile, -amount, balance - amount, reason);
        return true;
    }

    private CraftPlayerProfile lookup(UUID playerId, String action) {
        if (playerId == null) {
            return null;
        }
        CraftPlayerProfile profile = profiles.getProfile(playerId);
        if (profile == null) {
            logger.warn("灵魂操作 {} 失败：玩家 {} 无在线档案（不支持离线操作）", action, playerId);
        }
        return profile;
    }

    /** 写档案 + 记台账（同一冲刷事务落库） */
    private void apply(CraftPlayerProfile profile, int delta, int balanceAfter, String reason) {
        profile.setSoul(balanceAfter);
        profiles.recordLedger(new LedgerEntry(profile.playerId(), delta, balanceAfter,
                reason == null ? "" : reason, System.currentTimeMillis()));
    }
}

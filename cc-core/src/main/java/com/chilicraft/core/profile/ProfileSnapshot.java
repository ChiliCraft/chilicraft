package com.chilicraft.core.profile;

import com.chilicraft.api.GameMode;

import java.util.UUID;

/**
 * 档案不可变快照（record）。
 * 主线程从 {@link CraftPlayerProfile#snapshot()} 构造后提交给 DB 线程，
 * record 语义保证跨线程安全发布。
 */
public record ProfileSnapshot(
        UUID playerId,
        GameMode mode,
        int martialRealm,
        String profession,
        int soul,
        int seasonPoints,
        String homeWorld,
        double homeX,
        double homeY,
        double homeZ,
        long modeSwitchAt,
        long createdAt,
        long updatedAt
) {
}

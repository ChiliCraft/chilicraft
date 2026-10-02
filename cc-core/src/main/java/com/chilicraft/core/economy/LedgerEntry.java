package com.chilicraft.core.economy;

import java.util.UUID;

/**
 * 灵魂流水台账条目（对应 cc_soul_ledger 表）。
 * 每笔灵魂增减都产生一条记录，随档案冲刷一起单事务落库。
 */
public record LedgerEntry(
        UUID playerId,
        int delta,
        int balanceAfter,
        String reason,
        long occurredAt
) {
}

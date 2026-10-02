package com.chilicraft.api;

import java.util.UUID;

/** 任务奖励事务结果，包含提交后的灵魂余额。 */
public record QuestRewardResult(
        QuestRewardStatus status,
        UUID playerId,
        String rewardId,
        int balanceAfter
) {
    public boolean success() {
        return status == QuestRewardStatus.SUCCESS;
    }
}

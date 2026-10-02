package com.chilicraft.core.profile;

import com.chilicraft.api.QuestRewardStatus;

/** 核心数据库任务奖励事务的内部结果。 */
public record QuestRewardCommit(QuestRewardStatus status, int balanceAfter) {

    static QuestRewardCommit success(int balanceAfter) {
        return new QuestRewardCommit(QuestRewardStatus.SUCCESS, balanceAfter);
    }

    static QuestRewardCommit playerNotFound() {
        return new QuestRewardCommit(QuestRewardStatus.PLAYER_NOT_FOUND, 0);
    }

    static QuestRewardCommit questNotCompleted() {
        return new QuestRewardCommit(QuestRewardStatus.QUEST_NOT_COMPLETED, 0);
    }

    static QuestRewardCommit alreadyClaimed() {
        return new QuestRewardCommit(QuestRewardStatus.ALREADY_CLAIMED, 0);
    }

    static QuestRewardCommit conflict() {
        return new QuestRewardCommit(QuestRewardStatus.CONFLICT, 0);
    }
}

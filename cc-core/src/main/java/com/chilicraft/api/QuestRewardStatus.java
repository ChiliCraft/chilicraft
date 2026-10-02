package com.chilicraft.api;

/** 任务奖励事务结果。 */
public enum QuestRewardStatus {
    SUCCESS,
    PLAYER_NOT_FOUND,
    QUEST_NOT_COMPLETED,
    ALREADY_CLAIMED,
    CONFLICT,
    FAILED
}

package com.chilicraft.quest;
public record QuestDefinition(String chapterId, String id, String title, String description, QuestTarget target, QuestReward reward) {}

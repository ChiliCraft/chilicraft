package com.chilicraft.quest;
import java.util.List;
public record ChapterDefinition(String id, String title, int order, List<QuestDefinition> quests) {}

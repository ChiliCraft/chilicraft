package com.chilicraft.quest;
import java.util.*;
public final class PlayerQuestProgress {
    private final UUID playerId;
    private final Map<String, QuestState> states = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();
    public PlayerQuestProgress(UUID playerId) { this.playerId = playerId; }
    public UUID playerId() { return playerId; }
    private String key(String c, String q) { return c + "\\0" + q; }
    public QuestState state(String c, String q) { return states.getOrDefault(key(c,q), QuestState.LOCKED); }
    public int progress(String c, String q) { return counts.getOrDefault(key(c,q), 0); }
    public void set(String c, String q, QuestState state, int progress) { states.put(key(c,q), state); counts.put(key(c,q), Math.max(0, progress)); }
    public boolean allClaimed(ChapterDefinition chapter) {
        for (QuestDefinition quest : chapter.quests()) {
            if (state(chapter.id(), quest.id()) != QuestState.CLAIMED) {
                return false;
            }
        }
        return true;
    }

    public void clear() { states.clear(); counts.clear(); }
}

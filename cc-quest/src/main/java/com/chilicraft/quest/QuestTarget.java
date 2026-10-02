package com.chilicraft.quest;
public record QuestTarget(String event, int amount, String target, Integer value) {
    public boolean matches(com.chilicraft.api.EventData data) {
        if (target != null && !target.equalsIgnoreCase(data.target())) return false;
        if (value != null && data.amount() != value) return false;
        return true;
    }
}

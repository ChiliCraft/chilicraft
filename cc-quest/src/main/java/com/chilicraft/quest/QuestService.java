package com.chilicraft.quest;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import com.chilicraft.api.QuestRewardResult;
import com.chilicraft.api.QuestRewardStatus;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class QuestService {

    private final ChiliCraftAPI api;
    private final QuestRepository repository;
    private final QuestSettings settings;
    private QuestContentLoader content;
    private final Map<UUID, PlayerQuestProgress> cache = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();
    private final Set<UUID> claiming = new HashSet<>();
    private boolean shuttingDown;

    QuestService(
            ChiliCraftAPI api,
            QuestRepository repository,
            QuestSettings settings,
            QuestContentLoader content) {
        this.api = api;
        this.repository = repository;
        this.settings = settings;
        this.content = content;
    }

    void load(UUID playerId) {
        if (!loading.add(playerId)) {
            return;
        }

        PlayerQuestProgress progress = cache.computeIfAbsent(
                playerId,
                PlayerQuestProgress::new
        );
        repository.load(playerId, rows -> {
            for (Map<String, Object> row : rows) {
                String chapterId = String.valueOf(row.get("chapter_id"));
                String questId = String.valueOf(row.get("quest_id"));
                QuestState state;
                try {
                    state = QuestState.valueOf(
                            String.valueOf(row.get("state"))
                    );
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                progress.set(
                        chapterId,
                        questId,
                        state,
                        number(row.get("progress"))
                );
            }
            initialize(progress);
            loading.remove(playerId);
        }, error -> {
            loading.remove(playerId);
            if (error != null) {
                // 数据库错误由核心线程链路记录；这里不阻塞玩家操作。
            }
        });
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void initialize(PlayerQuestProgress progress) {
        if (content.chapters().isEmpty()) {
            return;
        }

        for (int chapterIndex = 0;
             chapterIndex < content.chapters().size();
             chapterIndex++) {
            ChapterDefinition chapter = content.chapters().get(chapterIndex);
            for (int questIndex = 0;
                 questIndex < chapter.quests().size();
                 questIndex++) {
                QuestDefinition quest = chapter.quests().get(questIndex);
                if (progress.state(chapter.id(), quest.id())
                        == QuestState.LOCKED) {
                    QuestState state = chapterIndex == 0 && questIndex == 0
                            ? QuestState.AVAILABLE
                            : QuestState.LOCKED;
                    progress.set(chapter.id(), quest.id(), state, 0);
                }
            }
        }
    }

    PlayerQuestProgress progress(UUID playerId) {
        load(playerId);
        return cache.computeIfAbsent(playerId, PlayerQuestProgress::new);
    }

    QuestDefinition find(UUID playerId, String questId) {
        PlayerQuestProgress progress = progress(playerId);
        for (ChapterDefinition chapter : content.chapters()) {
            for (QuestDefinition quest : chapter.quests()) {
                if (quest.id().equalsIgnoreCase(questId)
                        && progress.state(chapter.id(), quest.id())
                        != QuestState.LOCKED) {
                    return quest;
                }
            }
        }
        return null;
    }

    void event(String eventName, EventData data) {
        if (data == null || data.playerId() == null) {
            return;
        }

        PlayerQuestProgress progress = progress(data.playerId());
        List<QuestDefinition> quests = content.index()
                .getOrDefault(eventName, List.of());

        for (QuestDefinition quest : quests) {
            QuestState state = progress.state(
                    quest.chapterId(),
                    quest.id()
            );
            if (state != QuestState.AVAILABLE
                    && state != QuestState.ACTIVE) {
                continue;
            }
            if (!quest.target().matches(data)) {
                continue;
            }

            if (state == QuestState.AVAILABLE) {
                progress.set(
                        quest.chapterId(),
                        quest.id(),
                        QuestState.ACTIVE,
                        0
                );
            }

            int increment = eventName.equals("adventure.dungeon_clear")
                    ? 1
                    : Math.max(1, data.amount());
            int nextProgress = Math.min(
                    quest.target().amount(),
                    progress.progress(quest.chapterId(), quest.id())
                            + increment
            );
            QuestState nextState = nextProgress >= quest.target().amount()
                    ? QuestState.COMPLETED
                    : QuestState.ACTIVE;
            progress.set(
                    quest.chapterId(),
                    quest.id(),
                    nextState,
                    nextProgress
            );
            repository.save(
                    data.playerId(),
                    quest.chapterId(),
                    quest.id(),
                    nextState,
                    nextProgress,
                    nextState == QuestState.COMPLETED
                            ? (int) (System.currentTimeMillis() / 1000)
                            : null
            );
        }
    }

    void claim(Player player, String questId, Consumer<QuestRewardResult> callback) {
        UUID playerId = player.getUniqueId();
        QuestDefinition quest = find(playerId, questId);
        if (quest == null) {
            callback.accept(new QuestRewardResult(
                    QuestRewardStatus.QUEST_NOT_COMPLETED, playerId, questId, 0));
            return;
        }

        PlayerQuestProgress progress = progress(playerId);
        if (progress.state(quest.chapterId(), quest.id()) != QuestState.COMPLETED) {
            callback.accept(new QuestRewardResult(
                    QuestRewardStatus.QUEST_NOT_COMPLETED, playerId, quest.id(), 0));
            return;
        }
        if (shuttingDown || !claiming.add(playerId)) {
            callback.accept(new QuestRewardResult(
                    QuestRewardStatus.CONFLICT, playerId, quest.id(), 0));
            return;
        }

        String rewardId = playerId + ":" + quest.chapterId() + ":" + quest.id();
        String reason = "quest_reward_" + quest.id().toLowerCase(Locale.ROOT);
        api.claimQuestReward(
                playerId,
                rewardId,
                quest.chapterId(),
                quest.id(),
                quest.reward().souls(),
                reason
        ).whenComplete((result, error) -> {
            claiming.remove(playerId);
            if (shuttingDown) {
                return;
            }
            if (error != null) {
                callback.accept(new QuestRewardResult(
                        QuestRewardStatus.FAILED, playerId, rewardId, 0));
                return;
            }
            if (result.status() != QuestRewardStatus.SUCCESS) {
                callback.accept(result);
                return;
            }

            progress.set(
                    quest.chapterId(),
                    quest.id(),
                    QuestState.CLAIMED,
                    quest.target().amount()
            );
            api.publish(
                    "quest.quest_completed",
                    new EventData(playerId, quest.id(), 1)
            );

            ChapterDefinition chapter = content.chapters().stream()
                    .filter(value -> value.id().equals(quest.chapterId()))
                    .findFirst()
                    .orElse(null);
            if (chapter != null && progress.allClaimed(chapter)) {
                api.publish(
                        "quest.chapter_cleared",
                        new EventData(playerId, chapter.id(), 1)
                );
            }
            callback.accept(result);
        });
    }

    void shutdown() {
        shuttingDown = true;
        claiming.clear();
        loading.clear();
    }

    void reset(UUID playerId, Runnable done) {
        repository.reset(playerId, () -> {
            PlayerQuestProgress progress = cache.computeIfAbsent(
                    playerId,
                    PlayerQuestProgress::new
            );
            progress.clear();
            initialize(progress);
            done.run();
        });
    }

    void reload(QuestContentLoader loader) {
        content = loader;
    }

    List<ChapterDefinition> chapters() {
        return content.chapters();
    }
}

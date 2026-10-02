package com.chilicraft.martial;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 武学境界服务：6 层境界（凡人 0 → 破天下 5）的计数、晋升、加成与持久化。
 *
 * <p>解锁条件（配置驱动）：拙劲=累计击杀、素青纱=通关地城、铜钱挂=击杀 Boss、
 * 绣花蹄=完成擂台、破天下=击败世界 Boss。后三者的事件源（cc-adventure / 擂台）
 * 未实装时事件不会到达，条件自然悬置，不影响其余境界。</p>
 *
 * <p>线程契约：公开方法仅主线程调用；RowStore 回调在 DB 线程，
 * 回写游戏状态前统一 runTask 回主线程。</p>
 */
final class RealmService {

    private static final String TABLE = "cc_skills";
    /** 在线玩家状态：键恒为 UUID，退出/禁用时移除，杜绝泄漏 */
    private final Map<UUID, PlayerMartialData> players = new HashMap<>();

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final ChiliCraftAPI api;
    private final Logger logger;
    /** 最大生命加成修饰符键（固定键便于幂等覆盖与卸载清理） */
    private final NamespacedKey healthModifierKey;

    RealmService(JavaPlugin plugin, MartialSettings settings, ChiliCraftAPI api, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.logger = logger;
        this.healthModifierKey = new NamespacedKey(plugin, "realm_health");
    }

    // ---------------- 载入与退出 ----------------

    /** 玩家加入：异步载库 → 主线程填充缓存并应用生命加成 */
    void load(UUID playerId) {
        api.rowStore().select(TABLE, "player_id = ?", playerId).thenAccept(rows -> {
            PlayerMartialData data = new PlayerMartialData();
            for (Map<String, Object> row : rows) {
                String skillId = String.valueOf(row.get("skill_id"));
                int level = row.get("level") instanceof Number n ? n.intValue() : 0;
                int xp = row.get("xp") instanceof Number n ? n.intValue() : 0;
                switch (skillId) {
                    case "__realm__" -> data.realm = Math.max(0, Math.min(5, level));
                    case "__kills__" -> data.kills = Math.max(0, xp);
                    case "__dungeons__" -> data.dungeons = Math.max(0, xp);
                    case "__bosses__" -> data.bosses = Math.max(0, xp);
                    case "__arena__" -> data.arenaWins = Math.max(0, xp);
                    default -> { }
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    return; // 载库期间退出：丢弃，等下次登录再载
                }
                players.put(playerId, data);
                applyHealth(online, data.realm);
            });
        }).exceptionally(ex -> {
            logger.warn("载入武学档案失败 {}：{}", playerId, ex.toString());
            return null;
        });
    }

    /** 玩家退出：落库 + 释放缓存与生命修饰符 */
    void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        removeHealthModifier(player);
        PlayerMartialData data = players.remove(id);
        if (data != null && data.dirty) {
            persist(id, data);
        }
    }

    // ---------------- 计数入口（监听器 / 事件总线调用） ----------------

    /** 击杀生物计数（拙劲解锁进度） */
    void recordKill(Player killer) {
        PlayerMartialData data = players.get(killer.getUniqueId());
        if (data == null) {
            return;
        }
        data.kills++;
        data.dirty = true;
        checkPromotion(killer, data);
    }

    /** 通关地城计数（素青纱解锁进度，adventure.dungeon_clear） */
    void recordDungeonClear(UUID playerId) {
        PlayerMartialData data = players.get(playerId);
        if (data == null) {
            return;
        }
        data.dungeons++;
        data.dirty = true;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            checkPromotion(player, data);
        }
    }

    /** 击杀 Boss 计数（铜钱挂解锁进度 + 世界 Boss 里程碑，adventure.boss_killed） */
    void recordBossKill(UUID playerId, String bossId) {
        PlayerMartialData data = players.get(playerId);
        if (data == null) {
            return;
        }
        data.bosses++;
        if (bossId != null && settings.unlockWorldBossIds.contains(bossId)) {
            data.worldBoss = true;
        }
        data.dirty = true;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            checkPromotion(player, data);
        }
    }

    /** 完成擂台计数（绣花蹄解锁进度，擂台服务在冠军产生时调用） */
    void recordArenaWin(UUID playerId) {
        PlayerMartialData data = players.get(playerId);
        if (data == null) {
            return;
        }
        data.arenaWins++;
        data.dirty = true;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            checkPromotion(player, data);
        }
    }

    // ---------------- 晋升 ----------------

    /** 下一境界条件是否已满足；已是最高境界或数据缺失返回 false */
    private boolean nextRealmUnlocked(PlayerMartialData data, int next) {
        return switch (next) {
            case 1 -> data.kills >= settings.unlockKillThreshold;
            case 2 -> data.dungeons >= settings.unlockDungeonClears;
            case 3 -> data.bosses >= settings.unlockBossKills;
            case 4 -> data.arenaWins >= settings.unlockArenaWins;
            case 5 -> data.worldBoss;
            default -> false;
        };
    }

    /** 检查并执行连续晋升（一次事件可能跨级满足多条件，逐级推进） */
    private void checkPromotion(Player player, PlayerMartialData data) {
        boolean promoted = false;
        while (data.realm < 5 && nextRealmUnlocked(data, data.realm + 1)) {
            int old = data.realm;
            data.realm++;
            data.dirty = true;
            announcePromotion(player, old, data.realm);
            promoted = true;
        }
        if (promoted) {
            applyHealth(player, data.realm);
            api.publish("martial.realm_up",
                    new EventData(player.getUniqueId(), realmId(data.realm), data.realm));
        }
    }

    /** 晋升播报：聊天消息 + 屏幕标题 */
    private void announcePromotion(Player player, int oldRealm, int newRealm) {
        String oldName = settings.realmDisplay[oldRealm];
        String newName = settings.realmDisplay[newRealm];
        String base = settings.message("realm-up")
                .replace("<old_realm>", oldName)
                .replace("<new_realm>", newName);
        player.sendMessage(Texts.parse(base));
        String bonus = settings.message("realm-bonus")
                .replace("<dmg>", trimPct(settings.meleeDamagePct[newRealm]))
                .replace("<red>", trimPct(settings.damageReductionPct[newRealm]))
                .replace("<hp>", String.valueOf(settings.maxHealthBonus[newRealm]));
        if (!bonus.isEmpty()) {
            player.sendMessage(Texts.parse(bonus));
        }
        player.showTitle(Title.title(
                Texts.parse("<gold>" + newName + "</gold>"),
                Texts.parse("<gray>武学境界提升</gray>"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
    }

    // ---------------- 查询 ----------------

    /** 当前境界（离线或未载入返回 0） */
    int realm(UUID playerId) {
        PlayerMartialData data = players.get(playerId);
        return data != null ? data.realm : 0;
    }

    /** 状态快照（面板展示用；无数据返回 null） */
    PlayerMartialData data(UUID playerId) {
        return players.get(playerId);
    }

    /** 境界 id（事件发布用：realm_0 .. realm_5） */
    String realmId(int realm) {
        return "realm_" + Math.max(0, Math.min(5, realm));
    }

    // ---------------- 加成 ----------------

    /** 近战伤害加成（%，0-100） */
    double meleeDamagePct(UUID playerId) {
        return settings.meleeDamagePct[realm(playerId)];
    }

    /** 受击减伤（%，0-100） */
    double damageReductionPct(UUID playerId) {
        return settings.damageReductionPct[realm(playerId)];
    }

    /** 应用境界生命加成（幂等：先移除旧修饰符再按当前境界写入） */
    private void applyHealth(Player player, int realm) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) {
            return;
        }
        removeHealthModifier(player);
        int bonus = settings.maxHealthBonus[realm];
        if (bonus <= 0) {
            return;
        }
        AttributeModifier modifier = new AttributeModifier(
                healthModifierKey, bonus, AttributeModifier.Operation.ADD_NUMBER);
        attr.addModifier(modifier);
    }

    private void removeHealthModifier(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) {
            return;
        }
        attr.removeModifier(healthModifierKey);
    }

    // ---------------- 持久化 ----------------

    /** 立即落库全部脏数据（主线程调用：退出 / 插件禁用） */
    void flushNow() {
        for (Map.Entry<UUID, PlayerMartialData> entry : players.entrySet()) {
            if (entry.getValue().dirty) {
                persist(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 释放全部状态（onDisable：先 flushNow 再调用） */
    void clearAll() {
        players.clear();
    }

    /** 行写入（upsert 命中复合主键即替换），DB 线程执行 */
    private void persist(UUID playerId, PlayerMartialData data) {
        api.rowStore().upsert(TABLE, Map.of(
                "player_id", playerId,
                "skill_id", "__realm__",
                "level", data.realm,
                "xp", 0), "player_id", "skill_id");
        upsertCounter(playerId, "__kills__", data.kills);
        upsertCounter(playerId, "__dungeons__", data.dungeons);
        upsertCounter(playerId, "__bosses__", data.bosses);
        upsertCounter(playerId, "__arena__", data.arenaWins);
    }

    private void upsertCounter(UUID playerId, String skillId, int value) {
        api.rowStore().upsert(TABLE, Map.of(
                "player_id", playerId,
                "skill_id", skillId,
                "level", 0,
                "xp", value), "player_id", "skill_id");
    }

    private static String trimPct(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}

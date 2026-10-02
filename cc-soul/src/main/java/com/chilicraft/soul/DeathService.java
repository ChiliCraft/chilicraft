package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡结算服务：掉 50% 物品（随机保留 50%）+ 掉 50% 灵魂（化为死亡点碎片）、
 * 死亡播报与临终遗言、最近死亡记录（葬礼凭吊用）。
 *
 * <p>结算五步（PlayerDeathEvent MONITOR 调用）：物品结算 → 灵魂损失与碎片
 * 生成 → 死因记录（内存 + DB）→ 播报（清原版死亡消息 + 临终遗言）→
 * 发布 soul.player_died。保留物品在死亡 → 重生之间暂存于 {@link #pendingKeeps}，
 * 退出不清（重进后重生仍归还）；临终遗言由 AsyncChat 异步线程写入，
 * 故使用并发容器。</p>
 */
final class DeathService {

    /** 附属表名（cc-core 白名单内） */
    private static final String TABLE = "cc_death_records";

    /** 单条死亡记录（内存仅保留每人最近一次） */
    record DeathRecord(String cause, String world, double x, double y, double z, long diedAt) {
    }

    /** 临终遗言快照（发言纯文本 + 时间戳） */
    record LastWords(String words, long at) {
    }

    private final JavaPlugin plugin;
    private final SoulSettings settings;
    private final ChiliCraftAPI api;
    private final RelicService relics;
    private final FragmentService fragments;
    private final Logger logger;

    /** 死亡保留待归还物品（死亡 → 重生之间暂存；退出不清，跨重启丢失可接受） */
    private final Map<UUID, List<ItemStack>> pendingKeeps = new HashMap<>();
    /** 每人最近一次死亡记录（葬礼凭吊 / 导航用；DB 为完整历史） */
    private final Map<UUID, DeathRecord> latestDeath = new HashMap<>();
    /** 临终遗言：AsyncChat 异步线程写入，必须并发容器 */
    private final Map<UUID, LastWords> lastWords = new ConcurrentHashMap<>();

    DeathService(JavaPlugin plugin, SoulSettings settings, ChiliCraftAPI api,
                 RelicService relics, FragmentService fragments, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.relics = relics;
        this.fragments = fragments;
        this.logger = logger;
    }

    // ---------------- 生命周期 ----------------

    /** 启动加载：全表读取，按玩家取 diedAt 最新一条回主线程填充缓存 */
    void load() {
        api.rowStore().select(TABLE, "").thenAccept(rows ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<UUID, DeathRecord> loaded = new HashMap<>();
                    for (Map<String, Object> row : rows) {
                        UUID player = parseUuid(row.get("player_id"));
                        String world = row.get("world") instanceof String s && !s.isEmpty() ? s : null;
                        if (player == null || world == null) {
                            continue;
                        }
                        String cause = row.get("cause") instanceof String s && !s.isEmpty() ? s : "UNKNOWN";
                        long diedAt = row.get("died_at") instanceof Number n ? n.longValue() : 0L;
                        double x = row.get("x") instanceof Number n ? n.doubleValue() : 0.0;
                        double y = row.get("y") instanceof Number n ? n.doubleValue() : 0.0;
                        double z = row.get("z") instanceof Number n ? n.doubleValue() : 0.0;
                        DeathRecord record = new DeathRecord(cause, world, x, y, z, diedAt);
                        DeathRecord prev = loaded.get(player);
                        if (prev == null || record.diedAt() > prev.diedAt()) {
                            loaded.put(player, record);
                        }
                    }
                    latestDeath.putAll(loaded);
                    logger.info("死亡记录已加载（{} 位玩家）", loaded.size());
                })).exceptionally(ex -> {
            logger.error("死亡记录加载失败：{}", ex.getMessage());
            return null;
        });
    }

    /** 关服兜底：未归还的保留物品掉到各自最近死亡点（世界未加载则丢弃并告警），清空全部缓存 */
    void clearAll() {
        for (Map.Entry<UUID, List<ItemStack>> entry : pendingKeeps.entrySet()) {
            DeathRecord record = latestDeath.get(entry.getKey());
            if (record == null) {
                logger.warn("玩家 {} 的保留物品无法归还（无死亡记录），已丢弃", entry.getKey());
                continue;
            }
            World world = Bukkit.getWorld(record.world());
            if (world == null) {
                logger.warn("玩家 {} 的保留物品无法归还（世界 {} 未加载），已丢弃",
                        entry.getKey(), record.world());
                continue;
            }
            Location loc = new Location(world, record.x(), record.y(), record.z());
            for (ItemStack item : entry.getValue()) {
                world.dropItemNaturally(loc, item);
            }
        }
        pendingKeeps.clear();
        latestDeath.clear();
        lastWords.clear();
    }

    // ---------------- 死亡结算 ----------------

    /** 死亡事件（MONITOR）：五步结算，见类注释 */
    void handleDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();

        // 1. 物品结算：遗物整组保护抽离 + 随机保留 itemKeepPct%
        settleDrops(player, event.getDrops());

        // 2. 灵魂损失 → 死亡点碎片
        int soul = api.getSoul(uuid);
        int lost = (int) Math.round(soul * settings.soulLossPct / 100.0);
        if (lost > 0) {
            api.addSoul(uuid, -lost, "cc-soul:death-loss");
            fragments.create(uuid, player.getLocation(), lost);
        }

        // 3. 死因记录：内存缓存 + DB（完整历史）
        String causeName = player.getLastDamageCause() != null
                ? player.getLastDamageCause().getCause().name() : "UNKNOWN";
        Location loc = player.getLocation();
        DeathRecord record = new DeathRecord(causeName,
                loc.getWorld() != null ? loc.getWorld().getName() : "world",
                loc.getX(), loc.getY(), loc.getZ(), System.currentTimeMillis());
        latestDeath.put(uuid, record);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("player_id", uuid);
        row.put("cause", record.cause());
        row.put("world", record.world());
        row.put("x", record.x());
        row.put("y", record.y());
        row.put("z", record.z());
        row.put("died_at", record.diedAt());
        api.rowStore().insert(TABLE, row).exceptionally(ex -> {
            logger.error("死亡记录落库失败：{}", ex.getMessage());
            return null;
        });

        // 4. 播报：清原版死亡消息 + 模板播报 + 临终遗言（有效期内）
        if (settings.deathBroadcast) {
            event.deathMessage(null);
            Bukkit.getServer().sendMessage(Texts.parse(settings.feedback("death-broadcast"),
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("cause", causeText(causeName))));
            LastWords words = lastWords.get(uuid);
            long maxAgeMs = settings.lastWordsMaxAgeMinutes * 60_000L;
            if (words != null && System.currentTimeMillis() - words.at() <= maxAgeMs) {
                Bukkit.getServer().sendMessage(Texts.parse(settings.feedback("last-words"),
                        Placeholder.unparsed("words", words.words())));
            }
            // 巡演联动：《不安灵魂收容所》收容播报（模板缺失则静默跳过）
            String sanctuary = settings.feedback("death-sanctuary");
            if (!sanctuary.isEmpty()) {
                Bukkit.getServer().sendMessage(Texts.parse(sanctuary,
                        Placeholder.unparsed("player", player.getName())));
            }
        }

        // 5. 发布死亡事件（附属联动：任务 / 成就等）
        api.publish("soul.player_died", new EventData(uuid, causeName, lost)
                .put("world", record.world())
                .put("x", record.x())
                .put("y", record.y())
                .put("z", record.z()));
    }

    /** 物品结算：先抽离受保护遗物（全服唯一，不参与掉落与随机保留），再随机保留 */
    private void settleDrops(Player player, List<ItemStack> drops) {
        List<ItemStack> keeps = new ArrayList<>();
        if (settings.protectRelics) {
            Iterator<ItemStack> it = drops.iterator();
            while (it.hasNext()) {
                ItemStack item = it.next();
                if (relics.isRelic(item)) {
                    keeps.add(item);
                    it.remove();
                }
            }
        }
        int keepCount = (int) Math.ceil(drops.size() * settings.itemKeepPct / 100.0);
        if (keepCount > 0) {
            Collections.shuffle(drops);
            keeps.addAll(drops.subList(0, keepCount));
            drops.subList(0, keepCount).clear();
        }
        if (!keeps.isEmpty()) {
            pendingKeeps.put(player.getUniqueId(), keeps);
        }
    }

    // ---------------- 重生归还 / 临终遗言 / 退出 ----------------

    /** 重生事件：归还死亡保留物品（背包溢出掉在重生点） */
    void handleRespawn(PlayerRespawnEvent event) {
        List<ItemStack> keeps = pendingKeeps.remove(event.getPlayer().getUniqueId());
        if (keeps == null || keeps.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        Location respawn = event.getRespawnLocation();
        for (ItemStack item : keeps) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack rest : overflow.values()) {
                World world = respawn.getWorld();
                if (world != null) {
                    world.dropItemNaturally(respawn, rest);
                }
            }
        }
    }

    /** 记录玩家最近发言（AsyncChat 异步线程调用；空文本忽略） */
    void noteChat(UUID playerId, Component message) {
        String plain = plainText(message).trim();
        if (plain.isEmpty()) {
            return;
        }
        lastWords.put(playerId, new LastWords(plain, System.currentTimeMillis()));
    }

    /** Component → 纯文本（paper-api 编译类路径无 plain 序列化器，自写递归拼接） */
    private static String plainText(Component component) {
        StringBuilder sb = new StringBuilder();
        appendPlain(sb, component);
        return sb.toString();
    }

    private static void appendPlain(StringBuilder sb, Component component) {
        if (component instanceof TextComponent text) {
            sb.append(text.content());
        }
        for (Component child : component.children()) {
            appendPlain(sb, child);
        }
    }

    /** 玩家退出：清临终遗言；pendingKeeps 保留（死亡未重生退出，重进后重生仍归还） */
    void handleQuit(Player player) {
        lastWords.remove(player.getUniqueId());
    }

    /** 最近一次死亡记录；null = 无记录（葬礼凭吊 / 命令展示用） */
    DeathRecord latestDeath(UUID playerId) {
        return latestDeath.get(playerId);
    }

    // ---------------- 死因展示 ----------------

    /** 死因英文名（EntityDamageEvent.DamageCause）→ 中文展示；未知原因回退原名 */
    static String causeText(String cause) {
        return switch (cause == null ? "UNKNOWN" : cause) {
            case "CONTACT" -> "接触伤害";
            case "ENTITY_ATTACK" -> "生物攻击";
            case "ENTITY_SWEEP_ATTACK" -> "横扫攻击";
            case "PROJECTILE" -> "弹射物";
            case "SUFFOCATION" -> "窒息";
            case "FALL" -> "摔落";
            case "FIRE" -> "火焰";
            case "FIRE_TICK" -> "燃烧";
            case "MELTING" -> "融化";
            case "LAVA" -> "岩浆";
            case "DROWNING" -> "溺水";
            case "BLOCK_EXPLOSION" -> "方块爆炸";
            case "ENTITY_EXPLOSION" -> "实体爆炸";
            case "VOID" -> "虚空";
            case "LIGHTNING" -> "雷击";
            case "SUICIDE" -> "自尽";
            case "STARVATION" -> "饥饿";
            case "POISON" -> "中毒";
            case "WITHER" -> "凋零";
            case "KILL" -> "击杀指令";
            case "HOT_FLOOR" -> "烫脚方块";
            case "FREEZE" -> "冰冻";
            case "SONIC_BOOM" -> "音波";
            case "MAGIC" -> "魔法";
            case "FALLING_BLOCK" -> "坠落方块";
            case "THORNS" -> "荆棘";
            case "DRAGON_BREATH" -> "龙息";
            case "CUSTOM" -> "未知力量";
            case "FLY_INTO_WALL" -> "撞墙";
            case "CRAMMING" -> "挤压";
            case "DRYOUT" -> "脱水";
            default -> cause == null ? "未知" : cause;
        };
    }

    private static UUID parseUuid(Object raw) {
        if (!(raw instanceof String s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.chilicraft.adventure;

import com.chilicraft.api.ChiliCraftAPI;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 地城实例化引擎：槽位分配 → 区域构建 → 传送入场 → 周期推进 → 分帧卸载。
 *
 * <p>专用空世界（world_cc_dungeon）按需创建：超平坦纯空气 + 禁自然刷怪，
 * 实例区域按槽位号沿 x 轴排开（slot × stride），数据完全隔离不串场。
 * 槽位满时队伍排队（queue-retry-seconds 节流重试）。</p>
 */
final class DungeonService implements DungeonInstance.MsgsTarget {

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final ChiliCraftAPI api;
    private final PartyService parties;
    private final DungeonContent content;
    private final Logger log;

    private Map<String, DungeonDefinition> registry = Map.of();
    private World world;
    private final DungeonInstance[] slots;
    private final Map<UUID, DungeonInstance> byPlayer = new HashMap<>();

    // ---------- 排队 ----------
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, String> queuedDungeon = new HashMap<>();
    private long nextQueueAt;

    DungeonService(JavaPlugin plugin, AdventureSettings settings, ChiliCraftAPI api,
                   PartyService parties, DungeonContent content, Logger log) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.parties = parties;
        this.content = content;
        this.log = log;
        this.slots = new DungeonInstance[settings.instanceSlots];
    }

    /** 重建地城注册表（onEnable 与 core.reload） */
    void reloadContent() {
        registry = content.reload();
    }

    List<String> listIds() {
        return List.copyOf(registry.keySet());
    }

    DungeonInstance instanceOf(UUID playerId) {
        return byPlayer.get(playerId);
    }

    // ---------------- 世界 ----------------

    /** 按需获取/创建专用空世界；失败返回 null（调用方提示） */
    World ensureWorld() {
        World w = Bukkit.getWorld(settings.dungeonWorldName);
        if (w != null) {
            world = w;
            return w;
        }
        WorldCreator wc = new WorldCreator(settings.dungeonWorldName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false);
        wc.generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}");
        w = Bukkit.createWorld(wc);
        if (w != null) {
            w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            w.setTime(6000);
            log.info(() -> "已创建地城专用世界 " + settings.dungeonWorldName);
        }
        world = w;
        return w;
    }

    // ---------------- 进出 ----------------

    /**
     * 发起进本。返回 null 表示成功；否则返回错误码（命令层翻译文案）。
     * 队伍由队长发起；槽位满时入队并返回 "queued"。
     */
    String start(UUID playerId, String dungeonId) {
        if (!settings.dungeonEnabled) {
            return "disabled";
        }
        DungeonDefinition def = registry.get(dungeonId);
        if (def == null) {
            return "unknown";
        }
        if (byPlayer.containsKey(playerId)) {
            return "in_dungeon";
        }
        if (queuedDungeon.containsKey(playerId)) {
            return "already_queued";
        }
        // 无队伍视为单人队；有队伍则队长统一发起
        List<UUID> members = parties.membersOf(playerId);
        if (members.size() > 1 && !parties.isLeader(playerId)) {
            return "not_leader";
        }
        World w = ensureWorld();
        if (w == null) {
            return "world_fail";
        }
        int slot = freeSlot();
        if (slot < 0) {
            queue.offer(playerId);
            queuedDungeon.put(playerId, dungeonId);
            return "queued";
        }
        return startAtSlot(playerId, members, def, slot);
    }

    private String startAtSlot(UUID leader, List<UUID> members, DungeonDefinition def, int slot) {
        Map<UUID, Location> returnLocs = new HashMap<>();
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                returnLocs.put(id, p.getLocation().clone());
            }
        }
        DungeonInstance inst = new DungeonInstance(plugin, settings, api, this,
                slot, def, world, members, returnLocs);
        Location entrance = inst.build();
        slots[slot] = inst;
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            byPlayer.put(id, inst);
            p.teleport(entrance);
            p.setFallDistance(0);
            if (def.mechanic == DungeonDefinition.Mechanic.UNDERWATER) {
                // 水下机制入本场：给足呼吸与夜视（时长覆盖整个时限）
                p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, def.timeLimitSec * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, def.timeLimitSec * 20, 0));
            }
        }
        broadcast(members, "dungeon.start",
                Placeholder.unparsed("dungeon", def.displayName),
                Placeholder.unparsed("seconds", String.valueOf(def.timeLimitSec)));
        log.info(() -> "地城 " + def.id + " 启动于槽位 " + slot + "（队长 " + leader + "）");
        return null;
    }

    private int freeSlot() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /** 主动退出（回到进本前位置）；排队中的退出只移出队列；全员离开触发失败结算 */
    void quit(UUID playerId) {
        // 排队中的离开：移出队列即可，无需结算
        queue.remove(playerId);
        queuedDungeon.remove(playerId);
        DungeonInstance inst = byPlayer.remove(playerId);
        if (inst == null) {
            return;
        }
        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
            Location back = inst.returnLocs.get(playerId);
            if (back != null) {
                p.teleport(back);
            }
            p.setFallDistance(0);
        }
        if (inst.onlineMembers() == 0) {
            inst.finish(false, "abandoned");
        }
    }

    // ---------------- 周期推进 ----------------

    /** 每秒：实例机制 tick + 分帧清理 + 全员离线兜底 + 排队重试 */
    void tick() {
        if (!settings.dungeonEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < slots.length; i++) {
            DungeonInstance inst = slots[i];
            if (inst == null) {
                continue;
            }
            try {
                if (inst.phase == DungeonInstance.Phase.RUNNING) {
                    if (inst.onlineMembers() == 0) {
                        inst.finish(false, "abandoned");
                    } else {
                        inst.tick();
                    }
                }
                if (inst.tickWipe()) {
                    slots[i] = null;
                }
            } catch (Throwable t) {
                int slotIndex = i;
                log.warning(() -> "地城实例槽位 " + slotIndex + " tick 异常：" + t);
            }
        }
        // 结算中的实例不再占用玩家映射
        Iterator<Map.Entry<UUID, DungeonInstance>> it = byPlayer.entrySet().iterator();
        while (it.hasNext()) {
            DungeonInstance inst = it.next().getValue();
            if (inst.phase != DungeonInstance.Phase.RUNNING) {
                it.remove();
            }
        }
        // 排队重试（节流）
        if (!queue.isEmpty() && now >= nextQueueAt) {
            nextQueueAt = now + settings.queueRetrySeconds * 1000L;
            UUID leader = queue.peek();
            String dungeonId = queuedDungeon.get(leader);
            DungeonDefinition def = dungeonId == null ? null : registry.get(dungeonId);
            if (def == null || byPlayer.containsKey(leader)) {
                queue.poll();
                queuedDungeon.remove(leader);
            } else {
                int slot = freeSlot();
                if (slot >= 0) {
                    queue.poll();
                    queuedDungeon.remove(leader);
                    startAtSlot(leader, parties.membersOf(leader), def, slot);
                }
            }
        }
    }

    /** 玩家离线：移出映射；实例全员离线则失败结算 */
    void handleQuit(UUID playerId) {
        DungeonInstance inst = byPlayer.remove(playerId);
        if (inst != null && inst.onlineMembers() == 0) {
            inst.finish(false, "abandoned");
        }
    }

    /** 右键交互入口；返回 true 表示事件已消费 */
    boolean handleInteract(UUID playerId, Block block) {
        DungeonInstance inst = byPlayer.get(playerId);
        return inst != null && inst.handleInteract(playerId, block);
    }

    // ---------------- 消息广播 ----------------

    @Override
    public void broadcast(List<UUID> members, String key, TagResolver... resolvers) {
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Msgs.send(plugin, settings, p, key, resolvers);
            }
        }
    }

    /** 排队中（命令层展示用） */
    boolean isQueued(UUID playerId) {
        return queuedDungeon.containsKey(playerId);
    }

    /** 排队目标地城 ID（GUI 状态行用；未排队返回 null） */
    String queuedDungeonId(UUID playerId) {
        return queuedDungeon.get(playerId);
    }

    /** 地城定义只读视图（GUI 名录展示用；不重载期间引用稳定） */
    Map<String, DungeonDefinition> definitions() {
        return registry;
    }
}

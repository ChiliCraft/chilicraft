package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 灵魂碎片服务：死亡灵魂损失化为死亡点碎片记录（纯记录，无实体），
 * 走近自动拾回，或由葬礼仪式按加成召回。
 *
 * <p>权威状态为内存缓存 {@link #byOwner}，落库异步跟随：create 先入缓存
 * 再 insert，回调以<strong>引用比较</strong>回填行 id——若回调时引用已不在
 * 缓存（拾取 / 过期先于回调），说明该行已无对应内存状态，跳过回填即可，
 * 后续删行以 rowId 定位（rowId 未回填前删除跳过，落库残留由下次加载的
 * 过期周期自然清理）。select 回调在 DB 线程，填缓存前必须切回主线程。</p>
 */
final class FragmentService {

    /** 附属表名（cc-core 白名单内） */
    private static final String TABLE = "cc_soul_fragments";

    /** 单片碎片；rowId 为 DB 行号，insert 在途时为 0 */
    record Fragment(long rowId, String world, double x, double y, double z, int amount, long expiresAt) {

        Fragment withRowId(long newRowId) {
            return new Fragment(newRowId, world, x, y, z, amount, expiresAt);
        }
    }

    private final JavaPlugin plugin;
    private final SoulSettings settings;
    private final ChiliCraftAPI api;
    private final Logger logger;

    /** 碎片缓存：玩家 -> 碎片列表（可变列表，拾取 / 过期 / 召回时原地增删） */
    private final Map<UUID, List<Fragment>> byOwner = new LinkedHashMap<>();

    FragmentService(JavaPlugin plugin, SoulSettings settings, ChiliCraftAPI api, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.logger = logger;
    }

    // ---------------- 生命周期 ----------------

    /** 启动加载：读全部未过期碎片行，回主线程填充缓存（保留 DB 行 id 供删行定位） */
    void load() {
        api.rowStore().select(TABLE, "expires_at > ?", System.currentTimeMillis()).thenAccept(rows ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Map<String, Object> row : rows) {
                        cacheRow(row);
                    }
                    logger.info("灵魂碎片已加载（{} 位玩家，共 {} 片）", byOwner.size(), total());
                })).exceptionally(ex -> {
            logger.error("灵魂碎片加载失败：{}", ex.getMessage());
            return null;
        });
    }

    /** 单行转缓存；脏行（UUID / 世界 / 数量非法）跳过 */
    private void cacheRow(Map<String, Object> row) {
        UUID owner = parseUuid(row.get("owner_id"));
        String world = row.get("world") instanceof String s && !s.isEmpty() ? s : null;
        int amount = row.get("amount") instanceof Number n ? n.intValue() : 0;
        if (owner == null || world == null || amount <= 0) {
            return;
        }
        // expires_at / died_at 为 epoch 毫秒，超出 Integer 范围，读取必须取 long
        long rowId = row.get("id") instanceof Number n ? n.longValue() : 0L;
        long expiresAt = row.get("expires_at") instanceof Number n ? n.longValue() : 0L;
        double x = row.get("x") instanceof Number n ? n.doubleValue() : 0.0;
        double y = row.get("y") instanceof Number n ? n.doubleValue() : 0.0;
        double z = row.get("z") instanceof Number n ? n.doubleValue() : 0.0;
        byOwner.computeIfAbsent(owner, k -> new ArrayList<>())
                .add(new Fragment(rowId, world, x, y, z, amount, expiresAt));
    }

    /**
     * 关服 / 重载清理：仅清内存缓存，DB 行保留——重启后 load 恢复，
     * 碎片是跨会话的持久资产，与遗物登记同等对待。
     */
    void clearAll() {
        byOwner.clear();
    }

    // ---------------- 生成 ----------------

    /** 死亡结算生成碎片：缓存先入（rowId=0），insert 回调引用比较回填 */
    void create(UUID owner, Location location, int amount) {
        if (amount <= 0 || location.getWorld() == null) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + settings.fragmentExpireHours * 3600_000L;
        Fragment fragment = new Fragment(0, location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), amount, expiresAt);
        byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(fragment);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("owner_id", owner);
        row.put("world", fragment.world());
        row.put("x", fragment.x());
        row.put("y", fragment.y());
        row.put("z", fragment.z());
        row.put("amount", amount);
        row.put("expires_at", expiresAt);
        api.rowStore().insert(TABLE, row).thenAccept(newId ->
                        Bukkit.getScheduler().runTask(plugin, () -> backfill(owner, fragment, newId)))
                .exceptionally(ex -> {
                    logger.error("碎片落库失败（owner={}）：{}", owner, ex.getMessage());
                    return null;
                });
    }

    /** insert 回调回填行 id：引用比较定位，找不到说明已被拾取 / 过期，安全跳过 */
    private void backfill(UUID owner, Fragment fragment, int rowId) {
        if (rowId <= 0) {
            return;
        }
        List<Fragment> list = byOwner.get(owner);
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == fragment) {
                list.set(i, fragment.withRowId(rowId));
                return;
            }
        }
    }

    // ---------------- 周期任务 ----------------

    /**
     * 自动拾回周期：对每位在线玩家，收集其身边（同世界、拾回半径内）全部碎片
     * 合并计入灵魂；葬礼中的玩家跳过（由仪式按召回率统一结算，避免双份拾取）。
     */
    void tickPickup(RelicService relics, Set<UUID> funeralActive) {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (funeralActive.contains(uuid)) {
                continue;
            }
            List<Fragment> list = byOwner.get(uuid);
            if (list == null || list.isEmpty()) {
                continue;
            }
            // 引魂灯遗物扩大拾回半径
            double radius = settings.fragmentPickupRadius
                    + relics.effectBonus(player, RelicEffect.FRAGMENT_RADIUS);
            double radiusSq = radius * radius;
            World world = player.getWorld();
            int collected = 0;
            List<Fragment> hit = new ArrayList<>();
            for (Fragment fragment : list) {
                if (!fragment.world().equals(world.getName()) || fragment.expiresAt() <= now) {
                    continue;
                }
                double dx = fragment.x() - player.getLocation().getX();
                double dy = fragment.y() - player.getLocation().getY();
                double dz = fragment.z() - player.getLocation().getZ();
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    collected += fragment.amount();
                    hit.add(fragment);
                }
            }
            if (hit.isEmpty()) {
                continue;
            }
            list.removeAll(hit);
            for (Fragment fragment : hit) {
                deleteRow(fragment);
            }
            api.addSoul(uuid, collected, "cc-soul:fragment-picked");
            player.sendMessage(Texts.parse(settings.feedback("fragment-picked"),
                    Placeholder.unparsed("amount", String.valueOf(collected))));
        }
    }

    /** 过期清理周期：到期碎片移出缓存并删行（不可拾回） */
    void tickExpire() {
        long now = System.currentTimeMillis();
        List<UUID> empty = null;
        for (Map.Entry<UUID, List<Fragment>> entry : byOwner.entrySet()) {
            List<Fragment> list = entry.getValue();
            if (list.isEmpty()) {
                continue;
            }
            Iterator<Fragment> it = list.iterator();
            while (it.hasNext()) {
                Fragment fragment = it.next();
                if (fragment.expiresAt() <= now) {
                    it.remove();
                    deleteRow(fragment);
                }
            }
            if (list.isEmpty()) {
                if (empty == null) {
                    empty = new ArrayList<>();
                }
                empty.add(entry.getKey());
            }
        }
        if (empty != null) {
            // entrySet 迭代结束后统一删键，避免并发修改
            for (UUID uuid : empty) {
                byOwner.remove(uuid);
            }
        }
    }

    // ---------------- 查询与葬礼召回 ----------------

    /** 玩家同世界最近一片碎片；null = 身边没有（命令导航用） */
    Fragment nearest(UUID owner, Location location) {
        List<Fragment> list = byOwner.get(owner);
        if (list == null || location.getWorld() == null) {
            return null;
        }
        Fragment best = null;
        double bestSq = Double.MAX_VALUE;
        for (Fragment fragment : list) {
            if (!fragment.world().equals(location.getWorld().getName())) {
                continue;
            }
            double dx = fragment.x() - location.getX();
            double dy = fragment.y() - location.getY();
            double dz = fragment.z() - location.getZ();
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = fragment;
            }
        }
        return best;
    }

    /** 本人待拾碎片片数（跨世界合计，GUI 状态头用） */
    int countOf(UUID owner) {
        List<Fragment> list = byOwner.get(owner);
        return list != null ? list.size() : 0;
    }

    /** 最近一片碎片导航反馈（/soul fragments 与 GUI 碎片按钮共用；碎片查询仅同世界，距离直线计算） */
    void sendNav(Player player) {
        Fragment f = nearest(player.getUniqueId(), player.getLocation());
        if (f == null) {
            player.sendMessage(Texts.parse(settings.feedback("fragments-none")));
            return;
        }
        Location spot = new Location(player.getWorld(), f.x(), f.y(), f.z());
        int distance = (int) Math.round(player.getLocation().distance(spot));
        player.sendMessage(Texts.parse(settings.feedback("fragments-nav"),
                Placeholder.unparsed("x", String.valueOf((int) Math.floor(f.x()))),
                Placeholder.unparsed("y", String.valueOf((int) Math.floor(f.y()))),
                Placeholder.unparsed("z", String.valueOf((int) Math.floor(f.z()))),
                Placeholder.unparsed("distance", String.valueOf(distance)),
                Placeholder.unparsed("world", f.world())));
    }

    /**
     * 葬礼召回：吸收仪式点周围半径内本人全部碎片并删行，返回灵魂总量。
     * 实际召回量由调用方按召回率折算（吸收全额，折算外部分散佚）。
     */
    int absorb(UUID owner, Location center, double radius) {
        List<Fragment> list = byOwner.get(owner);
        if (list == null || list.isEmpty() || center.getWorld() == null) {
            return 0;
        }
        double radiusSq = radius * radius;
        World world = center.getWorld();
        int total = 0;
        Iterator<Fragment> it = list.iterator();
        while (it.hasNext()) {
            Fragment fragment = it.next();
            if (!fragment.world().equals(world.getName())) {
                continue;
            }
            double dx = fragment.x() - center.getX();
            double dy = fragment.y() - center.getY();
            double dz = fragment.z() - center.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                total += fragment.amount();
                it.remove();
                deleteRow(fragment);
            }
        }
        return total;
    }

    /** 全服碎片总片数（缓存口径，日志 / 图鉴用） */
    int total() {
        int count = 0;
        for (List<Fragment> list : byOwner.values()) {
            count += list.size();
        }
        return count;
    }

    // ---------------- 内部 ----------------

    /** 删行；rowId<=0 说明 insert 在途（引用比较回填未完成），跳过——残留行由过期周期兜底 */
    private void deleteRow(Fragment fragment) {
        if (fragment.rowId() > 0) {
            api.rowStore().delete(TABLE, "id = ?", fragment.rowId());
        }
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

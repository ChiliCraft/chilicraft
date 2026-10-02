package com.chilicraft.demon;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import com.chilicraft.api.ParamKey;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Mob;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 统一 2 秒周期循环：饿意追踪 / 刷怪判定 / 母体召唤 / 白天清除。
 *
 * <p>单任务单句柄单权威循环，避免多任务松散协调；
 * 各业务独立 try-catch，一项失败不拖垮整轮。
 * 计数基准周期 2 秒：interval/2 换算实际触发周期。</p>
 */
final class DemonTickTask implements Runnable {

    /** 白天清除触发周期（15 周期 = 30 秒查一次） */
    private static final int CLEAR_EVERY_PERIODS = 15;
    /** 单玩家单次刷怪判定的落点尝试次数 */
    private static final int SPAWN_ATTEMPTS = 5;
    /** 周期基准（秒） */
    private static final double PERIOD_SECONDS = 2.0;

    private final Logger logger;
    private final DemonSettings settings;
    private final DemonManager manager;
    private final ChiliCraftAPI api;
    private final Random random = new Random();

    /** 周期计数器（只增不减，模运算触发各业务） */
    private long period;
    /** 当前处于饿意状态的玩家（false→true 转换时发布事件） */
    private final Set<UUID> hungryPlayers = new HashSet<>();

    DemonTickTask(Logger logger, DemonSettings settings, DemonManager manager, ChiliCraftAPI api) {
        this.logger = logger;
        this.settings = settings;
        this.manager = manager;
        this.api = api;
    }

    @Override
    public void run() {
        period++;
        try {
            hungerTick();
        } catch (Throwable t) {
            logger.warn("饿意追踪周期异常", t);
        }
        try {
            spawnTick();
        } catch (Throwable t) {
            logger.warn("刷怪判定周期异常", t);
        }
        try {
            summonTick();
        } catch (Throwable t) {
            logger.warn("母体召唤周期异常", t);
        }
        if (settings.daytimeClear && period % CLEAR_EVERY_PERIODS == 0) {
            try {
                daytimeTick();
            } catch (Throwable t) {
                logger.warn("白天清除周期异常", t);
            }
        }
    }

    // ---------------- 饿意追踪 ----------------

    /** 收集饿意玩家 → 状态机转换发布事件 → 饿魔索敌 / 加速 */
    private void hungerTick() {
        if (!settings.hungerEnabled) {
            if (!hungryPlayers.isEmpty()) {
                // 功能关闭：清空状态并解除全部加速（重载生效路径）
                for (DemonManager.Tracked entry : manager.snapshot()) {
                    manager.removeSpeedBoost(entry.entity());
                }
                hungryPlayers.clear();
            }
            return;
        }

        // 当前帧饿意玩家集合
        Set<UUID> current = new HashSet<>();
        for (Player player : onlinePlayers()) {
            if (isEligible(player) && !settings.inExcludedWorld(player.getWorld().getName())
                    && player.getFoodLevel() < settings.hungerThreshold) {
                current.add(player.getUniqueId());
                if (!hungryPlayers.contains(player.getUniqueId())) {
                    // false→true 转换：发布 demon.hunger_triggered（载荷 = 玩家 / 饱食度）
                    api.publish("demon.hunger_triggered",
                            new EventData(player.getUniqueId(), "hunger", player.getFoodLevel()));
                }
            }
        }
        hungryPlayers.retainAll(current);

        // 每只饿魔：半径内最近饿意玩家 → setTarget + 加速；否则解除
        for (DemonManager.Tracked entry : manager.snapshot()) {
            Mob entity = entry.entity();
            if (!entity.isValid()) {
                continue;
            }
            Player target = nearestHungry(entity);
            if (target != null) {
                entity.setTarget(target);
                manager.applySpeedBoost(entity);
            } else {
                entity.setTarget(null);
                manager.removeSpeedBoost(entity);
            }
        }
    }

    /** 饿意半径内最近的饿意玩家；无则 null */
    private Player nearestHungry(Mob entity) {
        Player best = null;
        double bestDistSq = (double) settings.hungerRadius * settings.hungerRadius;
        for (UUID id : hungryPlayers) {
            Player player = playerById(id);
            if (player == null || !player.isOnline() || !player.getWorld().equals(entity.getWorld())) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(entity.getLocation());
            if (distSq <= bestDistSq) {
                best = player;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    // ---------------- 刷怪判定 ----------------

    private void spawnTick() {
        if (!settings.spawnEnabled) {
            return;
        }
        long spawnEvery = Math.max(1, Math.round(settings.spawnIntervalSec / PERIOD_SECONDS));
        if (period % spawnEvery != 0) {
            return;
        }
        for (Player player : onlinePlayers()) {
            if (!isEligible(player) || !settings.isNight(player.getWorld().getTime())
                    || settings.inExcludedWorld(player.getWorld().getName())) {
                continue;
            }
            spawnAround(player);
        }
    }

    /** 单玩家刷怪判定：上限 → 概率 → 落点 → 群体生成 */
    private void spawnAround(Player player) {
        // 全服硬上限：任何玩家都不再触发新刷怪
        if (manager.total() >= settings.globalLimit) {
            return;
        }
        // 每玩家半径内上限
        if (countNear(player) >= settings.maxPerPlayer) {
            return;
        }
        // 模式刷怪倍率：cozy = 0.3 时 70% 判定直接跳过（.getParam 已含家园区加成）
        double rate = api.getParam(player.getUniqueId(), ParamKey.MOB_SPAWN_RATE);
        if (rate < 1.0 && random.nextDouble() >= rate) {
            return;
        }

        Location spot = findSpawnSpot(player);
        if (spot == null) {
            return;
        }

        // 小饿魔群：落点 ±2 格散布
        int group = settings.impGroupMin + random.nextInt(settings.impGroupMax - settings.impGroupMin + 1);
        for (int i = 0; i < group; i++) {
            if (manager.total() >= settings.globalLimit) {
                return;
            }
            Location spread = spot.clone().add(
                    (random.nextDouble() - 0.5) * 4, 0, (random.nextDouble() - 0.5) * 4);
            manager.spawn(DemonType.IMP, spread, null);
        }
        // 猎手：概率附带
        if (random.nextDouble() < settings.hunterChance && manager.total() < settings.globalLimit) {
            manager.spawn(DemonType.HUNTER, spot.clone().add(
                    (random.nextDouble() - 0.5) * 4, 0, (random.nextDouble() - 0.5) * 4), null);
        }
        // 母体：小概率附带
        if (random.nextDouble() < settings.motherChance && manager.total() < settings.globalLimit) {
            manager.spawn(DemonType.MOTHER, spot, null);
        }
    }

    /** 统计玩家刷怪半径内的饿魔数（判定是否已达每玩家上限） */
    private int countNear(Player player) {
        double rSq = (double) settings.radiusMax * settings.radiusMax;
        int n = 0;
        for (DemonManager.Tracked entry : manager.snapshot()) {
            if (entry.entity().isValid()
                    && entry.entity().getWorld().equals(player.getWorld())
                    && entry.entity().getLocation().distanceSquared(player.getLocation()) <= rSq) {
                n++;
            }
        }
        return n;
    }

    /**
     * 找落点：随机角度 + 距离 ∈ [radiusMin, radiusMax]，从玩家 Y+4 向下扫到 Y-8，
     * 要求「脚下实心 + 3 格空气」（铁傀儡 2.7 格高，2 格会窒息）。
     * 返回居中坐标；找不到返回 null。
     */
    private Location findSpawnSpot(Player player) {
        World world = player.getWorld();
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = settings.radiusMin + random.nextDouble() * (settings.radiusMax - settings.radiusMin);
            int x = player.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = player.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            // 只在已加载区块找落点：绝不主动 load 区块（性能红线）
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int baseY = player.getLocation().getBlockY();
            for (int dy = 4; dy >= -8; dy--) {
                int y = baseY + dy;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    break;
                }
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                Block above = world.getBlockAt(x, y + 2, z);
                Block ground = world.getBlockAt(x, y - 1, z);
                if (feet.isEmpty() && head.isEmpty() && above.isEmpty()
                        && ground.getType().isSolid()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    // ---------------- 母体召唤 ----------------

    private void summonTick() {
        long summonEvery = Math.max(1, Math.round(settings.motherSummonIntervalSec / PERIOD_SECONDS));
        if (settings.motherSummonIntervalSec <= 0 || period % summonEvery != 0) {
            return;
        }
        for (DemonManager.Tracked entry : manager.snapshot()) {
            if (entry.type() != DemonType.MOTHER || !entry.entity().isValid()) {
                continue;
            }
            Mob mother = entry.entity();
            if (!settings.isNight(mother.getWorld().getTime())) {
                continue;
            }
            if (manager.countSummons(mother) >= settings.maxSummons) {
                continue;
            }
            int count = settings.summonMin
                    + random.nextInt(settings.summonMax - settings.summonMin + 1);
            for (int i = 0; i < count; i++) {
                if (manager.countSummons(mother) >= settings.maxSummons
                        || manager.total() >= settings.globalLimit) {
                    break;
                }
                Location spot = mother.getLocation().add(
                        (random.nextDouble() - 0.5) * 4, 0, (random.nextDouble() - 0.5) * 4);
                manager.spawn(DemonType.IMP, spot, mother.getUniqueId());
            }
        }
    }

    // ---------------- 白天清除 ----------------

    /** 非夜间世界内的饿魔全部移除（防止残留到日出） */
    private void daytimeTick() {
        for (DemonManager.Tracked entry : manager.snapshot()) {
            Mob entity = entry.entity();
            if (entity.isValid() && !settings.isNight(entity.getWorld().getTime())) {
                entity.remove();
                manager.untrack(entity);
            }
        }
    }

    // ---------------- 通用辅助 ----------------

    private Iterable<? extends Player> onlinePlayers() {
        return org.bukkit.Bukkit.getOnlinePlayers();
    }

    private Player playerById(UUID id) {
        return org.bukkit.Bukkit.getPlayer(id);
    }

    /** 生存/冒险模式才参与饿意与刷怪（创造/观战豁免） */
    private static boolean isEligible(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }
}

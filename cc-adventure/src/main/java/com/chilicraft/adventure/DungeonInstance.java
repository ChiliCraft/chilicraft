package com.chilicraft.adventure;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 单个地城实例：独占世界中的一个槽位区域（slot × stride 隔离，互不串场）。
 *
 * <p>构建优先走 jar 内置结构模板（structures/&lt;template&gt;.nbt），
 * 模板缺失或放置失败回退程序生成封闭房间——内容文件未就绪不应阻断玩法。
 * 机制道具（锅 / 祭坛 / 界门 / 终点板）始终由代码定点放置，
 * 模板只负责外观外壳，保证机制状态机坐标可控。</p>
 *
 * <p>生命周期：RUNNING（tick 推进机制与波次）→ WIPEOUT（分帧清方块）→ 释放槽位。
 * 结算发布 adventure.dungeon_clear（逐队员），martial 订阅解锁素青纱境界。</p>
 */
final class DungeonInstance {

    enum Phase { RUNNING, WIPEOUT }

    /** 消息广播回调（服务注入，避免实例反向依赖服务） */
    interface MsgsTarget {
        void broadcast(List<UUID> members, String key, TagResolver... resolvers);
    }

    final int slot;
    final DungeonDefinition def;
    final World world;
    final Location origin;                       // 区域西南下角（地面方块层）
    final Map<UUID, Location> returnLocs = new HashMap<>();
    final List<UUID> members;
    final JavaPlugin plugin;
    final AdventureSettings settings;
    final ChiliCraftAPI api;
    final MsgsTarget msgs;

    Phase phase = Phase.RUNNING;

    // ---------- 机制关键点（整数坐标，与方块 getLocation 对齐） ----------
    final Location cauldronLoc;      // 守锅：大锅
    final Location altarLoc;         // 献祭：祭坛
    final Location endPlateLoc;      // 承重：终点板
    final Location gateLocA;         // 双界：界门甲
    final Location gateLocB;         // 双界：界门乙
    Location entrance;               // 玩家入场点

    int remainingSec;
    int wavesRemaining;
    int cauldronHp;
    int offered;
    int collapseRow;                 // 承重已塌陷到的 z 排
    long nextWaveAt;
    long nextCollapseAt;
    boolean gateA;
    boolean gateB;

    final List<Entity> spawned = new ArrayList<>();   // 本实例生成的怪物
    private final Random random = new Random();

    // ---------- 分帧清理 ----------
    private int wipeCursor;          // 已清理列数（x*sizeZ + z）

    DungeonInstance(JavaPlugin plugin, AdventureSettings settings, ChiliCraftAPI api,
                    MsgsTarget msgs, int slot, DungeonDefinition def, World world,
                    List<UUID> members, Map<UUID, Location> returnLocs) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.msgs = msgs;
        this.slot = slot;
        this.def = def;
        this.world = world;
        this.members = new ArrayList<>(members);
        this.returnLocs.putAll(returnLocs);
        this.origin = new Location(world, slot * settings.slotStride, settings.baseY, 0);
        int cx = def.sizeX / 2;
        int cz = def.sizeZ / 2;
        this.cauldronLoc = origin.clone().add(cx, 1, cz);
        this.altarLoc = origin.clone().add(cx, 1, cz);
        this.endPlateLoc = origin.clone().add(cx, 1, def.sizeZ - 3);
        this.gateLocA = origin.clone().add(3, 1, cz);
        this.gateLocB = origin.clone().add(def.sizeX - 3, 1, cz);
        this.remainingSec = def.timeLimitSec;
        this.wavesRemaining = def.waves;
        this.cauldronHp = 30 + def.waves * 10;   // 锅耐久随波次增长
        this.nextCollapseAt = System.currentTimeMillis() + 5_000L;
    }

    // ---------------- 构建 ----------------

    /** 搭建区域（模板或程序生成）+ 定点机制道具；返回入场点 */
    Location build() {
        boolean useProgrammatic = def.template.isBlank() || !placeTemplate(def.template);
        if (useProgrammatic) {
            buildProgrammatic();
        }
        placeMechanicProps();
        entrance = origin.clone().add(def.sizeX / 2 + 0.5, 1.2, 2.5);
        return entrance;
    }

    /** 尝试从 jar 内 structures/<name>.nbt 放置模板；失败返回 false（走程序生成） */
    private boolean placeTemplate(String name) {
        try {
            File file = new File(plugin.getDataFolder(), "structures/" + name + ".nbt");
            if (!file.isFile()) {
                if (plugin.getResource("structures/" + name + ".nbt") != null) {
                    plugin.saveResource("structures/" + name + ".nbt", false);
                } else {
                    return false;
                }
            }
            StructureManager sm = Bukkit.getStructureManager();
            Structure structure = sm.loadStructure(file);
            if (structure == null) {
                return false;
            }
            structure.place(origin, true, org.bukkit.block.structure.StructureRotation.NONE,
                    org.bukkit.block.structure.Mirror.NONE, 0, 1.0f, new Random());
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning(() -> "地城模板 " + name + " 放置失败，回退程序生成：" + e.getMessage());
            return false;
        }
    }

    /** 程序生成封闭房间（石头外壳 + 萤石照明）；承重铺独木桥、水下灌水 */
    private void buildProgrammatic() {
        int sx = def.sizeX;
        int sy = def.sizeY;
        int sz = def.sizeZ;
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        boolean bear = def.mechanic == DungeonDefinition.Mechanic.BEAR_WEIGHT;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                boolean edge = x == 0 || x == sx - 1 || z == 0 || z == sz - 1;
                for (int y = 0; y <= sy; y++) {
                    // 承重房不铺内部地板（桥下深渊），其余房间 y=0 实心
                    boolean shell = edge || y == sy || (y == 0 && !bear);
                    Block b = world.getBlockAt(ox + x, oy + y, oz + z);
                    b.setType(shell ? Material.STONE : Material.AIR, false);
                }
            }
        }
        // 顶面嵌萤石照明
        for (int x = 2; x < sx - 1; x += 6) {
            for (int z = 2; z < sz - 1; z += 6) {
                world.getBlockAt(ox + x, oy + sy, oz + z).setType(Material.GLOWSTONE, false);
            }
        }
        if (bear) {
            // 桥面：入口到终点，宽 3
            for (int z = 1; z <= sz - 3; z++) {
                for (int x = sx / 2 - 1; x <= sx / 2 + 1; x++) {
                    world.getBlockAt(ox + x, oy, oz + z).setType(Material.STONE, false);
                }
            }
        }
        if (def.mechanic == DungeonDefinition.Mechanic.UNDERWATER) {
            // 房间内部灌水（留顶层气室供呼吸缓冲）
            for (int x = 1; x < sx - 1; x++) {
                for (int z = 1; z < sz - 1; z++) {
                    for (int y = 1; y <= sy - 2; y++) {
                        world.getBlockAt(ox + x, oy + y, oz + z).setType(Material.WATER, false);
                    }
                }
            }
        }
    }

    /** 机制道具定点放置（模板情况同样执行，保证状态机坐标可控） */
    private void placeMechanicProps() {
        switch (def.mechanic) {
            case GUARD_CAULDRON -> setBlock(cauldronLoc, Material.CAULDRON);
            case SACRIFICE -> setBlock(altarLoc, Material.LECTERN);
            case BEAR_WEIGHT -> setBlock(endPlateLoc, Material.GOLD_BLOCK);
            case DUAL_REALM -> {
                setBlock(gateLocA, Material.ENCHANTING_TABLE);
                setBlock(gateLocB, Material.ENCHANTING_TABLE);
            }
            case UNDERWATER -> {
                // 无定点道具；水下呼吸效果在入场时发放
            }
        }
    }

    private void setBlock(Location loc, Material material) {
        world.getBlockAt(loc).setType(material, false);
    }

    // ---------------- 周期推进（每秒） ----------------

    void tick() {
        if (phase != Phase.RUNNING) {
            return;
        }
        remainingSec--;
        if (remainingSec <= 0) {
            finish(false, "timeout");
            return;
        }
        spawned.removeIf(e -> !e.isValid() || e.isDead());
        long now = System.currentTimeMillis();
        switch (def.mechanic) {
            case GUARD_CAULDRON -> tickGuardCauldron(now);
            case SACRIFICE, DUAL_REALM -> {
                // 交互驱动；时限兜底在上方
            }
            case BEAR_WEIGHT -> tickBearWeight(now);
            case UNDERWATER -> tickWaves(now);
        }
    }

    /** 守锅：怪物贴锅扣耐久 + 波次推进 */
    private void tickGuardCauldron(long now) {
        int bx = cauldronLoc.getBlockX();
        int by = cauldronLoc.getBlockY();
        int bz = cauldronLoc.getBlockZ();
        for (Entity e : spawned) {
            Location loc = e.getLocation();
            if (Math.abs(loc.getBlockX() - bx) <= 5
                    && Math.abs(loc.getBlockY() - by) <= 3
                    && Math.abs(loc.getBlockZ() - bz) <= 5) {
                cauldronHp--;
            }
        }
        if (cauldronHp <= 0) {
            finish(false, "cauldron_broken");
            return;
        }
        tickWaves(now);
    }

    /** 波次推进：清光后延时刷下一波；全部波次清空即通关 */
    private void tickWaves(long now) {
        if (!spawned.isEmpty()) {
            return;
        }
        if (wavesRemaining > 0) {
            if (now < nextWaveAt) {
                return;
            }
            spawnWave();
            return;
        }
        finish(true, null);
    }

    /** 承重：逐排塌陷 + 终点板判定 + 坠落回起点 */
    private void tickBearWeight(long now) {
        if (now >= nextCollapseAt && collapseRow < def.sizeZ - 3) {
            int z = origin.getBlockZ() + collapseRow;
            for (int x = 0; x < def.sizeX; x++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block b = world.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + dy, z);
                    if (b.getType() != Material.AIR) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
            collapseRow++;
            nextCollapseAt = now + 5_000L;
        }
        double ex = endPlateLoc.getX() + 0.5;
        double ez = endPlateLoc.getZ() + 0.5;
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            Location loc = p.getLocation();
            if (Math.abs(loc.getX() - ex) < 1.5 && Math.abs(loc.getZ() - ez) < 1.5
                    && Math.abs(loc.getY() - endPlateLoc.getY()) < 2) {
                finish(true, null);
                return;
            }
            if (loc.getY() < origin.getY() - 8) {
                p.teleport(entrance);   // 坠落者送回入口
                p.setFallDistance(0);
            }
        }
    }

    /** 生成一波怪物：房间内随机空位（距玩家保持距离） */
    private void spawnWave() {
        EntityType type = def.mobEntityType();
        int placed = 0;
        for (int attempt = 0; attempt < def.waveSize * 10 && placed < def.waveSize; attempt++) {
            int x = origin.getBlockX() + 2 + random.nextInt(Math.max(1, def.sizeX - 4));
            int z = origin.getBlockZ() + 2 + random.nextInt(Math.max(1, def.sizeZ - 4));
            int y = origin.getBlockY() + (def.mechanic == DungeonDefinition.Mechanic.UNDERWATER ? 2 : 1);
            Location spot = new Location(world, x + 0.5, y, z + 0.5);
            if (!isSafeSpawn(spot)) {
                continue;
            }
            Entity entity = world.spawnEntity(spot, type);
            if (entity instanceof Mob mob) {
                mob.setPersistent(false);   // 卸载时随区块清理，不留孤儿实体
            }
            entity.getPersistentDataContainer().set(Keys.DUNGEON_ID, PersistentDataType.STRING, def.id);
            spawned.add(entity);
            placed++;
        }
        nextWaveAt = System.currentTimeMillis() + 3_000L;   // 清光后 3 秒刷下一波
        broadcast("dungeon.wave",
                Placeholder.unparsed("wave", String.valueOf(def.waves - wavesRemaining)),
                Placeholder.unparsed("total", String.valueOf(def.waves)));
        wavesRemaining--;
    }

    private boolean isSafeSpawn(Location loc) {
        Block feet = world.getBlockAt(loc);
        if (feet.getType() != Material.AIR && feet.getType() != Material.WATER) {
            return false;
        }
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(world) && p.getLocation().distanceSquared(loc) < 16) {
                return false;
            }
        }
        return true;
    }

    // ---------------- 交互 ----------------

    /** 玩家右键机制道具；返回 true 表示事件已消费 */
    boolean handleInteract(UUID playerId, Block block) {
        if (phase != Phase.RUNNING || !members.contains(playerId)) {
            return false;
        }
        Location hit = block.getLocation();
        switch (def.mechanic) {
            case SACRIFICE -> {
                if (hit.equals(altarLoc)) {
                    handleOffer(playerId);
                    return true;
                }
            }
            case DUAL_REALM -> {
                if (hit.equals(gateLocA)) {
                    handleGate(playerId, 'A');
                    return true;
                }
                if (hit.equals(gateLocB)) {
                    handleGate(playerId, 'B');
                    return true;
                }
            }
            default -> {
                // GUARD_CAULDRON / BEAR_WEIGHT / UNDERWATER 无交互点
            }
        }
        return false;
    }

    private void handleOffer(UUID playerId) {
        Player p = Bukkit.getPlayer(playerId);
        if (p == null) {
            return;
        }
        if (offered >= def.offerTarget) {
            return;
        }
        // 手持可提交物品则扣除一件计入献祭
        for (String mat : def.offerItems) {
            try {
                Material m = Material.valueOf(mat);
                if (p.getInventory().getItemInMainHand().getType() == m) {
                    var stack = p.getInventory().getItemInMainHand();
                    stack.setAmount(stack.getAmount() - 1);
                    offered++;
                    if (offered >= def.offerTarget) {
                        finish(true, null);
                    } else {
                        broadcast("dungeon.offer",
                                Placeholder.unparsed("player", p.getName()),
                                Placeholder.unparsed("count", String.valueOf(offered)),
                                Placeholder.unparsed("target", String.valueOf(def.offerTarget)));
                    }
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // 物品名非法由装载器兜底，跳过
            }
        }
    }

    private void handleGate(UUID playerId, char gate) {
        boolean changed;
        if (gate == 'A') {
            changed = !gateA;
            gateA = true;
        } else {
            changed = !gateB;
            gateB = true;
        }
        if (changed) {
            broadcast("dungeon.gate", Placeholder.unparsed("gate", String.valueOf(gate)));
        }
        if (gateA && gateB) {
            finish(true, null);
        }
    }

    // ---------------- 结算与清理 ----------------

    /** 结算：通关逐队员发奖并发布事件；随后进入分帧清理 */
    void finish(boolean success, String failReason) {
        if (phase != Phase.RUNNING) {
            return;
        }
        phase = Phase.WIPEOUT;
        if (success) {
            broadcast("dungeon.cleared",
                    Placeholder.unparsed("dungeon", def.displayName),
                    Placeholder.unparsed("soul", String.valueOf(def.rewardSoul)));
        } else {
            broadcast("dungeon.failed",
                    Placeholder.unparsed("dungeon", def.displayName),
                    Placeholder.unparsed("reason", failReason == null ? "unknown" : failReason));
        }
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Location back = returnLocs.get(id);
            if (back != null) {
                p.teleport(back);
            }
            p.setFallDistance(0);
            if (success) {
                api.addSoul(id, def.rewardSoul, "dungeon_clear:" + def.id);
                api.publish("adventure.dungeon_clear", new EventData(id, def.id, def.rewardSoul));
            }
        }
        // 实体立即清；方块分帧清（防主线程卡顿）
        for (Entity e : spawned) {
            e.remove();
        }
        spawned.clear();
        clearRegionEntities();
    }

    /** 清理区域内遗留实体（掉落物 / 箭矢等） */
    private void clearRegionEntities() {
        Location center = origin.clone().add(def.sizeX / 2.0, def.sizeY / 2.0, def.sizeZ / 2.0);
        for (Entity e : world.getNearbyEntities(center, def.sizeX / 2.0 + 1, def.sizeY / 2.0 + 1, def.sizeZ / 2.0 + 1)) {
            e.remove();
        }
    }

    /** 每秒推进分帧清方块；返回 true 表示清理完毕（槽位可释放） */
    boolean tickWipe() {
        if (phase != Phase.WIPEOUT) {
            return false;
        }
        int budget = settings.wipeBudgetPerTick;
        int total = def.sizeX * def.sizeZ;
        while (budget > 0 && wipeCursor < total) {
            int x = wipeCursor / def.sizeZ;
            int z = wipeCursor % def.sizeZ;
            for (int y = 0; y <= def.sizeY; y++) {
                Block b = world.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                if (b.getType() != Material.AIR) {
                    b.setType(Material.AIR, false);
                    budget--;
                }
            }
            wipeCursor++;
        }
        return wipeCursor >= total;
    }

    /** 实例中仍在世界的成员数（全员离开即结束） */
    int onlineMembers() {
        int n = 0;
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                n++;
            }
        }
        return n;
    }

    void broadcast(String key, TagResolver... resolvers) {
        msgs.broadcast(members, key, resolvers);
    }
}

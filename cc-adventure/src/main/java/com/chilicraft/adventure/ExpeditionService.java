package com.chilicraft.adventure;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 远征引擎：5 层塔式推图，逐层按权重随机生成房间（第 5 层固定 Boss 房）。
 *
 * <p>独立空世界（world_cc_expedition），每支队伍独占一条「航道」沿 x 轴排开，
 * 层与层再沿 x 排开（layer × 64），互不干扰。房间为 31×12×31 封闭石头房，
 * 构建前同步清区域（约 1.1 万方块一次性，可接受）防跨局残留。</p>
 *
 * <p>奖励节律：每层通关逐队员发放灵魂 + 全队 roll 一次祝福/诅咒
 * （每获得 N 个祝福强制 1 个诅咒，规格 2:1）；通关追加奖励并落库
 * cc_expedition_runs、发布 adventure.expedition_result（逐队员）。</p>
 */
final class ExpeditionService implements DungeonInstance.MsgsTarget {

    enum RoomType { COMBAT, ELITE, CHEST, TRAP, REST, SHOP, EVENT, PUZZLE, BOSS }

    /** 结算类型：clear=走完第 5 层；quit=主动/全员离线退出 */
    static final String RESULT_CLEAR = "clear";
    static final String RESULT_QUIT = "quit";

    // 房间尺寸（含外壳）：31×12×31 ≈ 1.1 万方块
    private static final int ROOM_X = 31;
    private static final int ROOM_Y = 12;
    private static final int ROOM_Z = 31;
    private static final int LAYER_STRIDE = 64;    // 层间距（x 方向）
    private static final int LANE_STRIDE = 1024;   // 队伍航道间距（x 方向）

    /** 一场远征的全部内存态 */
    private static final class Run {
        final UUID leader;
        final List<UUID> members;
        final Map<UUID, Location> returnLocs = new HashMap<>();
        final int lane;
        int layer = 1;
        int deepest = 0;
        int blessCounter = 0;          // 已连续获得的祝福数（强制诅咒节律用）
        int soulEarned = 0;
        RoomType currentRoom;
        Location roomOrigin;           // 当前房间西南下角（地面方块层）
        Location nextDoor;             // 出口门方块（CRYING_OBSIDIAN）
        Location chestLoc;             // CHEST：宝箱
        Location shopLoc;              // SHOP：商人讲台
        final List<Location> plates = new ArrayList<>();   // PUZZLE：压力板
        final List<Entity> spawned = new ArrayList<>();
        boolean roomCleared;
        boolean chestLooted;

        Run(UUID leader, List<UUID> members, int lane) {
            this.leader = leader;
            this.members = new ArrayList<>(members);
            this.lane = lane;
        }
    }

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final ChiliCraftAPI api;
    private final PartyService parties;
    private final Logger log;

    private World world;
    private final Run[] lanes;
    private final Map<UUID, Run> byPlayer = new HashMap<>();
    private final Random random = new Random();

    ExpeditionService(JavaPlugin plugin, AdventureSettings settings, ChiliCraftAPI api,
                      PartyService parties, Logger log) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.parties = parties;
        this.log = log;
        this.lanes = new Run[settings.expeditionMaxRuns];
    }

    // ---------------- 世界 ----------------

    /** 按需获取/创建远征专用空世界；失败返回 null */
    World ensureWorld() {
        World w = Bukkit.getWorld(settings.expeditionWorldName);
        if (w != null) {
            world = w;
            return w;
        }
        WorldCreator wc = new WorldCreator(settings.expeditionWorldName)
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
            log.info(() -> "已创建远征专用世界 " + settings.expeditionWorldName);
        }
        world = w;
        return w;
    }

    // ---------------- 进出 ----------------

    /** 发起远征。返回 null 表示成功；否则返回错误码。 */
    String start(UUID playerId) {
        if (!settings.expeditionEnabled) {
            return "disabled";
        }
        if (byPlayer.containsKey(playerId)) {
            return "in_expedition";
        }
        List<UUID> members = parties.membersOf(playerId);
        if (members.size() > 1 && !parties.isLeader(playerId)) {
            return "not_leader";
        }
        World w = ensureWorld();
        if (w == null) {
            return "world_fail";
        }
        int lane = freeLane();
        if (lane < 0) {
            return "busy";
        }
        Run run = new Run(playerId, members, lane);
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                run.returnLocs.put(id, p.getLocation().clone());
            }
        }
        lanes[lane] = run;
        for (UUID id : members) {
            byPlayer.put(id, run);
        }
        enterLayer(run, 1);
        broadcast(members, "expedition.start",
                Placeholder.unparsed("layers", String.valueOf(settings.expeditionLayers)));
        log.info(() -> "远征启动：航道 " + lane + "（队长 " + playerId + "）");
        return null;
    }

    private int freeLane() {
        for (int i = 0; i < lanes.length; i++) {
            if (lanes[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /** 主动退出（回到进图前位置并按 quit 结算） */
    void quit(UUID playerId) {
        Run run = byPlayer.remove(playerId);
        if (run == null) {
            return;
        }
        finish(run, RESULT_QUIT);
    }

    // ---------------- 房间构建 ----------------

    /** 进入某层：roll 房间类型 → 清区域 → 建房 → 传送 → roll 效果 */
    private void enterLayer(Run run, int layer) {
        run.layer = layer;
        run.deepest = Math.max(run.deepest, layer);
        run.currentRoom = layer >= settings.expeditionLayers ? RoomType.BOSS : rollRoom();
        run.roomCleared = false;
        run.chestLooted = false;
        run.spawned.clear();
        run.plates.clear();
        int baseX = run.lane * LANE_STRIDE + (layer - 1) * LAYER_STRIDE;
        run.roomOrigin = new Location(world, baseX, settings.baseY, 0);
        buildRoom(run);
        Location entrance = run.roomOrigin.clone().add(ROOM_X / 2 + 0.5, 1.2, 2.5);
        for (UUID id : run.members) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            p.teleport(entrance);
            p.setFallDistance(0);
        }
        rollReward(run);
        broadcast(run.members, "expedition.layer",
                Placeholder.unparsed("layer", String.valueOf(layer)),
                Placeholder.unparsed("room", roomName(run.currentRoom)));
    }

    /** 1–4 层按权重随机（排除 BOSS 与零权重）；空池兜底 COMBAT */
    private RoomType rollRoom() {
        List<Map.Entry<String, Integer>> pool = new ArrayList<>();
        int total = 0;
        for (var e : settings.roomWeights.entrySet()) {
            if (e.getValue() <= 0 || e.getKey().equalsIgnoreCase("boss")) {
                continue;
            }
            pool.add(e);
            total += e.getValue();
        }
        if (pool.isEmpty()) {
            return RoomType.COMBAT;
        }
        int r = random.nextInt(total);
        for (var e : pool) {
            r -= e.getValue();
            if (r < 0) {
                try {
                    return RoomType.valueOf(e.getKey().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    return RoomType.COMBAT;   // 未知房间名按战斗房处理
                }
            }
        }
        return RoomType.COMBAT;
    }

    private String roomName(RoomType type) {
        return switch (type) {
            case COMBAT -> "战斗";
            case ELITE -> "精英";
            case CHEST -> "宝箱";
            case TRAP -> "陷阱";
            case REST -> "休整";
            case SHOP -> "商店";
            case EVENT -> "奇遇";
            case PUZZLE -> "机关";
            case BOSS -> "首领";
        };
    }

    /** 清区域 → 建外壳 → 按类型填充内容 → 预置出口门（通关后点亮） */
    private void buildRoom(Run run) {
        int ox = run.roomOrigin.getBlockX();
        int oy = run.roomOrigin.getBlockY();
        int oz = run.roomOrigin.getBlockZ();
        // 先同步清区域，防重启后撞旧残留
        for (int x = 0; x < ROOM_X; x++) {
            for (int z = 0; z < ROOM_Z; z++) {
                for (int y = 0; y <= ROOM_Y; y++) {
                    world.getBlockAt(ox + x, oy + y, oz + z).setType(Material.AIR, false);
                }
            }
        }
        // 外壳：地板 + 天花 + 四壁，顶面嵌萤石
        for (int x = 0; x < ROOM_X; x++) {
            for (int z = 0; z < ROOM_Z; z++) {
                boolean edge = x == 0 || x == ROOM_X - 1 || z == 0 || z == ROOM_Z - 1;
                for (int y = 0; y <= ROOM_Y; y++) {
                    boolean shell = edge || y == 0 || y == ROOM_Y;
                    Material mat = shell ? Material.STONE : Material.AIR;
                    world.getBlockAt(ox + x, oy + y, oz + z).setType(mat, false);
                }
            }
        }
        for (int x = 2; x < ROOM_X - 1; x += 6) {
            for (int z = 2; z < ROOM_Z - 1; z += 6) {
                world.getBlockAt(ox + x, oy + ROOM_Y, oz + z).setType(Material.GLOWSTONE, false);
            }
        }
        // 出口门（对面中轴）：清怪后点亮，右键进下一层
        run.nextDoor = new Location(world, ox + ROOM_X / 2, oy + 1, oz + ROOM_Z - 3);
        fillRoom(run);
    }

    /** 按房间类型填充内容 */
    private void fillRoom(Run run) {
        switch (run.currentRoom) {
            case COMBAT -> spawnPack(run, 4 + random.nextInt(3), 1.0, 0);
            case ELITE -> spawnPack(run, 2 + random.nextInt(2), 1.5, 0);
            case BOSS -> spawnPack(run, 1, 3.0, 4);
            case CHEST -> {
                run.chestLoc = run.roomOrigin.clone().add(ROOM_X / 2, 1, ROOM_Z / 2);
                setBlock(run.chestLoc, Material.CHEST);
            }
            case TRAP -> placeTraps(run);
            case REST -> restTeam(run);
            case SHOP -> {
                run.shopLoc = run.roomOrigin.clone().add(ROOM_X / 2, 1, ROOM_Z / 2);
                setBlock(run.shopLoc, Material.LECTERN);
                completeRoom(run);   // 逛店是可选行为，进门即视为通过（同陷阱房口径）
            }
            case EVENT -> {
                completeRoom(run);
                rollReward(run);     // 奇遇：在本层基础 roll 之外追加一次（rollReward 注释口径）
            }
            case PUZZLE -> placePlates(run);
        }
    }

    /** 生成一组怪物：类型随机、血量倍率与攻击加成按房间档位 */
    private void spawnPack(Run run, int count, double hpMult, int atkBonus) {
        List<EntityType> pool = List.of(EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.SPIDER, EntityType.DROWNED);
        int ox = run.roomOrigin.getBlockX();
        int oy = run.roomOrigin.getBlockY();
        int oz = run.roomOrigin.getBlockZ();
        for (int i = 0; i < count; i++) {
            Entity entity = null;
            for (int attempt = 0; attempt < 20 && entity == null; attempt++) {
                int x = ox + 3 + random.nextInt(ROOM_X - 6);
                int z = oz + 6 + random.nextInt(ROOM_Z - 10);   // 远离入口
                Location spot = new Location(world, x + 0.5, oy + 1, z + 0.5);
                if (!isSafeSpawn(run, spot)) {
                    continue;
                }
                entity = world.spawnEntity(spot, pool.get(random.nextInt(pool.size())));
            }
            if (entity instanceof Mob mob) {
                mob.setPersistent(false);
                mob.getPersistentDataContainer().set(Keys.EXPEDITION_ID, PersistentDataType.STRING, "run" + run.lane);
                if (hpMult != 1.0) {
                    var attr = mob.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) {
                        attr.setBaseValue(attr.getBaseValue() * hpMult);
                        mob.setHealth(attr.getValue());
                    }
                }
                if (atkBonus != 0) {
                    var atk = mob.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
                    if (atk != null) {
                        atk.setBaseValue(atk.getBaseValue() + atkBonus);
                    }
                }
                run.spawned.add(mob);
            } else if (entity != null) {
                run.spawned.add(entity);
            }
        }
    }

    private boolean isSafeSpawn(Run run, Location loc) {
        Block feet = world.getBlockAt(loc);
        if (feet.getType() != Material.AIR) {
            return false;
        }
        for (UUID id : run.members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(world) && p.getLocation().distanceSquared(loc) < 100) {
                return false;
            }
        }
        return true;
    }

    /** 陷阱房：随机埋 TNT + 压力板（原版机制自动引爆，纯视觉震慑） */
    private void placeTraps(Run run) {
        int ox = run.roomOrigin.getBlockX();
        int oy = run.roomOrigin.getBlockY();
        int oz = run.roomOrigin.getBlockZ();
        for (int i = 0; i < 5; i++) {
            int x = ox + 4 + random.nextInt(ROOM_X - 8);
            int z = oz + 6 + random.nextInt(ROOM_Z - 10);
            world.getBlockAt(x, oy, z).setType(Material.TNT, false);
            world.getBlockAt(x, oy + 1, z).setType(Material.STONE_PRESSURE_PLATE, false);
        }
        run.roomCleared = true;   // 陷阱房无怪，进门即视为清空
        lightDoor(run);
    }

    /** 休整房：全员回满血 + 再生效果 */
    private void restTeam(Run run) {
        for (UUID id : run.members) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            var maxHealth = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHealth != null) {
                p.setHealth(Math.min(maxHealth.getValue(), p.getHealth() + maxHealth.getValue() * 0.5));
            }
            p.setFoodLevel(20);
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 30, 1));
        }
        completeRoom(run);
    }

    /** 机关房：4 块金压力板，全员同时踩上即清空 */
    private void placePlates(Run run) {
        int ox = run.roomOrigin.getBlockX();
        int oy = run.roomOrigin.getBlockY();
        int oz = run.roomOrigin.getBlockZ();
        int cx = ox + ROOM_X / 2;
        int cz = oz + ROOM_Z / 2;
        int[][] offsets = {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
        for (int[] off : offsets) {
            Location plate = new Location(world, cx + off[0], oy + 1, cz + off[1]);
            world.getBlockAt(plate).setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, false);
            run.plates.add(plate);
        }
    }

    /** 完成房间并只广播一次，供无阻塞房和交互房使用。 */
    private void completeRoom(Run run) {
        if (run.roomCleared) {
            return;
        }
        run.roomCleared = true;
        lightDoor(run);
        broadcast(run.members, "expedition.cleared",
                Placeholder.unparsed("layer", String.valueOf(run.layer)));
    }

    /** 点亮出口门（清怪/无怪房进图即点） */
    private void lightDoor(Run run) {
        if (run.nextDoor != null) {
            setBlock(run.nextDoor, Material.CRYING_OBSIDIAN);
        }
    }

    private void setBlock(Location loc, Material material) {
        world.getBlockAt(loc).setType(material, false);
    }

    // ---------------- 祝福与诅咒 ----------------

    /**
     * 每 enterLayer/奇遇 roll 一次：连续获得 N 个祝福后强制 1 个诅咒（规格 2:1），
     * 否则祝福/诅咒各半。池条目格式「类型:强度:名称」，解析失败跳过。
     */
    private void rollReward(Run run) {
        boolean bless;
        if (run.blessCounter >= settings.forceCurseAfterBlessings) {
            bless = false;
            run.blessCounter = 0;
        } else {
            bless = random.nextBoolean();
            if (bless) {
                run.blessCounter++;
            }
        }
        List<String> pool = bless ? settings.blessingEffects : settings.curseEffects;
        if (pool.isEmpty()) {
            return;
        }
        String entry = pool.get(random.nextInt(pool.size()));
        applyPooledEffect(run, entry);
        broadcast(run.members, bless ? "expedition.bless" : "expedition.curse",
                Placeholder.unparsed("effect", effectName(entry)));
    }

    /** 解析并施加池条目给全员；格式非法静默跳过（配置问题不崩玩法） */
    private void applyPooledEffect(Run run, String entry) {
        String[] parts = entry.split(":");
        if (parts.length < 2) {
            return;
        }
        PotionEffectType type = Registry.EFFECT.get(
                NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
        if (type == null) {
            log.warning(() -> "远征效果类型非法：" + parts[0]);
            return;
        }
        int amplifier;
        try {
            amplifier = Integer.parseInt(parts[1].trim()) - 1;
        } catch (NumberFormatException e) {
            return;
        }
        amplifier = Math.max(0, Math.min(amplifier, 4));
        for (UUID id : run.members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.addPotionEffect(new PotionEffect(type, settings.effectDurationTicks, amplifier));
            }
        }
    }

    private String effectName(String entry) {
        String[] parts = entry.split(":");
        return parts.length >= 3 ? parts[2].trim() : parts[0].trim();
    }

    // ---------------- 交互 ----------------

    /** 右键交互：出口门进下一层 / 宝箱 / 商店；返回 true 表示事件已消费 */
    boolean handleInteract(UUID playerId, Block block) {
        Run run = byPlayer.get(playerId);
        if (run == null) {
            return false;
        }
        Location hit = block.getLocation();
        if (hit.equals(run.nextDoor)) {
            return handleDoor(playerId, run);
        }
        if (hit.equals(run.chestLoc)) {
            return handleChest(run);
        }
        if (hit.equals(run.shopLoc)) {
            return handleShop(playerId, run);
        }
        return false;
    }

    /** 出口门：Boss 层通关结算；其余层进下一层并逐队员发层奖 */
    private boolean handleDoor(UUID playerId, Run run) {
        if (!run.roomCleared) {
            Msgs.send(plugin, settings, Bukkit.getPlayer(playerId), "expedition.door-locked");
            return true;
        }
        if (run.layer >= settings.expeditionLayers) {
            finish(run, RESULT_CLEAR);
            return true;
        }
        for (UUID id : run.members) {
            api.addSoul(id, settings.expeditionSoulPerLayer, "expedition_layer");
            run.soulEarned += settings.expeditionSoulPerLayer;
        }
        enterLayer(run, run.layer + 1);
        return true;
    }

    /** 宝箱：一次性灵魂（层奖 × 2），开完消失 */
    private boolean handleChest(Run run) {
        if (run.chestLooted) {
            return true;
        }
        run.chestLooted = true;
        setBlock(run.chestLoc, Material.AIR);
        completeRoom(run);
        int amount = settings.expeditionSoulPerLayer * 2;
        for (UUID id : run.members) {
            api.addSoul(id, amount, "expedition_chest");
            run.soulEarned += amount;
        }
        broadcast(run.members, "expedition.chest",
                Placeholder.unparsed("soul", String.valueOf(amount)));
        return true;
    }

    /** 商店：花 10 灵魂随机换一个祝福效果 */
    private boolean handleShop(UUID playerId, Run run) {
        Player p = Bukkit.getPlayer(playerId);
        if (p == null) {
            return true;
        }
        if (!api.spendSoul(playerId, 10, "expedition_shop")) {
            Msgs.send(plugin, settings, p, "expedition.shop-poor");
            return true;
        }
        if (settings.blessingEffects.isEmpty()) {
            return true;
        }
        String entry = settings.blessingEffects.get(random.nextInt(settings.blessingEffects.size()));
        applyPooledEffectTo(run, entry, playerId);
        Msgs.send(plugin, settings, p, "expedition.shop-bought",
                Placeholder.unparsed("effect", effectName(entry)));
        return true;
    }

    /** 单人施加池条目（商店用，只作用于买家） */
    private void applyPooledEffectTo(Run run, String entry, UUID only) {
        String[] parts = entry.split(":");
        if (parts.length < 2) {
            return;
        }
        PotionEffectType type = Registry.EFFECT.get(
                NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
        if (type == null) {
            return;
        }
        int amplifier;
        try {
            amplifier = Math.max(0, Math.min(Integer.parseInt(parts[1].trim()) - 1, 4));
        } catch (NumberFormatException e) {
            return;
        }
        Player p = Bukkit.getPlayer(only);
        if (p != null && p.isOnline()) {
            p.addPotionEffect(new PotionEffect(type, settings.effectDurationTicks, amplifier));
        }
    }

    // ---------------- 周期推进（每秒） ----------------

    void tick() {
        if (!settings.expeditionEnabled) {
            return;
        }
        for (int i = 0; i < lanes.length; i++) {
            Run run = lanes[i];
            if (run == null) {
                continue;
            }
            try {
                // 全员离线兜底
                boolean anyOnline = false;
                for (UUID id : run.members) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        anyOnline = true;
                        break;
                    }
                }
                if (!anyOnline) {
                    finish(run, RESULT_QUIT);
                    continue;
                }
                // 只有战斗类房间以怪物清空作为完成条件，交互房由各自状态迁移完成。
                run.spawned.removeIf(e -> !e.isValid() || e.isDead());
                if (!run.roomCleared && (run.currentRoom == RoomType.COMBAT
                        || run.currentRoom == RoomType.ELITE
                        || run.currentRoom == RoomType.BOSS) && run.spawned.isEmpty()) {
                    completeRoom(run);
                }
                // 机关房：全员同时踩板
                if (run.currentRoom == RoomType.PUZZLE && run.plates.size() == 4) {
                    int pressed = 0;
                    for (UUID id : run.members) {
                        Player p = Bukkit.getPlayer(id);
                        if (p == null) {
                            continue;
                        }
                        Location loc = p.getLocation();
                        for (Location plate : run.plates) {
                            if (Math.abs(loc.getX() - (plate.getX() + 0.5)) < 0.7
                                    && Math.abs(loc.getZ() - (plate.getZ() + 0.5)) < 0.7
                                    && Math.abs(loc.getY() - plate.getY()) < 1.2) {
                                pressed++;
                                break;
                            }
                        }
                    }
                    if (!run.roomCleared && pressed >= Math.min(4, run.members.size())) {
                        completeRoom(run);
                        broadcast(run.members, "expedition.puzzle");
                    }
                }
            } catch (Throwable t) {
                int laneIndex = i;
                log.warning(() -> "远征航道 " + laneIndex + " tick 异常：" + t);
            }
        }
    }

    // ---------------- 结算与清理 ----------------

    /** 结算：传送回 + 通关追加奖 + 落库 + 发布事件 + 清场 */
    private void finish(Run run, String result) {
        lanes[run.lane] = null;
        for (UUID id : run.members) {
            byPlayer.remove(id);
        }
        for (UUID id : run.members) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            Location back = run.returnLocs.get(id);
            if (back != null) {
                p.teleport(back);
            }
            p.setFallDistance(0);
            if (RESULT_CLEAR.equals(result)) {
                api.addSoul(id, settings.expeditionClearBonus, "expedition_clear");
                run.soulEarned += settings.expeditionClearBonus;
            }
        }
        if (RESULT_CLEAR.equals(result)) {
            broadcast(run.members, "expedition.done",
                    Placeholder.unparsed("soul", String.valueOf(settings.expeditionClearBonus)));
        } else {
            broadcast(run.members, "expedition.quit");
        }
        // 清理本航道全部层区域方块与实体
        clearLane(run);
        persist(run, result);
    }

    /** 清空该航道占用过的所有层区域（一次性，远征结束才发生） */
    private void clearLane(Run run) {
        int baseX = run.lane * LANE_STRIDE;
        for (int layer = 1; layer <= settings.expeditionLayers; layer++) {
            int ox = baseX + (layer - 1) * LAYER_STRIDE;
            int oy = settings.baseY;
            for (int x = 0; x < ROOM_X; x++) {
                for (int z = 0; z < ROOM_Z; z++) {
                    for (int y = 0; y <= ROOM_Y; y++) {
                        Block b = world.getBlockAt(ox + x, oy + y, 0 + z);
                        if (b.getType() != Material.AIR) {
                            b.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
        Location center = new Location(world, baseX + settings.expeditionLayers * LAYER_STRIDE / 2.0,
                settings.baseY + ROOM_Y / 2.0, ROOM_Z / 2.0);
        for (Entity e : world.getNearbyEntities(center, settings.expeditionLayers * LAYER_STRIDE / 2.0 + 1,
                ROOM_Y, ROOM_Z / 2.0 + 1)) {
            e.remove();
        }
    }

    /** 落库 cc_expedition_runs + 发布 adventure.expedition_result（逐队员） */
    private void persist(Run run, String result) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < run.members.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(run.members.get(i)).append('"');
        }
        json.append(']');
        Map<String, Object> row = new HashMap<>();
        row.put("team_json", json.toString());
        row.put("deepest_layer", run.deepest);
        row.put("result", result);
        row.put("ended_at", System.currentTimeMillis());
        api.rowStore().insert("cc_expedition_runs", row).exceptionally(t -> {
            log.warning(() -> "远征记录落库失败：" + t);
            return null;
        });
        for (UUID id : run.members) {
            api.publish("adventure.expedition_result", new EventData(id, "expedition", run.deepest)
                    .put("layer", run.deepest)
                    .put("result", result)
                    .put("soul", run.soulEarned));
        }
    }

    // ---------------- 杂项 ----------------

    /** 玩家离线：移出映射；全员离线时由 tick 兜底结算 */
    void handleQuit(UUID playerId) {
        byPlayer.remove(playerId);
    }

    boolean inExpedition(UUID playerId) {
        return byPlayer.containsKey(playerId);
    }

    /** 当前层数（GUI 状态行用；未在远征返回 0） */
    int currentLayer(UUID playerId) {
        Run run = byPlayer.get(playerId);
        return run == null ? 0 : run.layer;
    }

    /** 本场已结算灵魂（GUI 状态行用；未在远征返回 0） */
    int soulEarned(UUID playerId) {
        Run run = byPlayer.get(playerId);
        return run == null ? 0 : run.soulEarned;
    }

    @Override
    public void broadcast(List<UUID> members, String key, TagResolver... resolvers) {
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                Msgs.send(plugin, settings, p, key, resolvers);
            }
        }
    }
}

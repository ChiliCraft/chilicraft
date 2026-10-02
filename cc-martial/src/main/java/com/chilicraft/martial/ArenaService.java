package com.chilicraft.martial;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.title.Title;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日擂台服务：定时开放报名 → 单败淘汰 → 冠军结算。
 *
 * <p>流程：开打前 {@code signup-minutes} 分钟进入 SIGNUP 并每分钟广播提醒；
 * 到点报名不足 2 人则取消；否则全员快照随身物品（赛后归还）、统一铁套铁剑，
 * 逐场捉对厮杀。胜负由 {@link ArenaListener}（死亡/退赛）回调判定；
 * 每场对战胜利计一次 {@link RealmService#recordArenaWin}（绣花蹄进度），
 * 败者获「下等马」安慰礼（灵魂，可配置），冠军额外获得灵魂 + 可选 Vault
 * 金钱与「演」夺冠演出，并发布 martial.arena_win 事件。</p>
 *
 * <p>线程契约：全部方法仅在主线程调用（调度器/事件回调内）。</p>
 */
final class ArenaService {

    /** 擂台阶段：未开启 / 报名中 / 淘汰赛进行中 */
    private enum Phase { IDLE, SIGNUP, RUNNING }

    /**
     * 参战者快照：报名时记录身份与归还点，开打时快照随身物品。
     * 赛后（重生/离线保存前/关服）完整还原。
     */
    static final class Fighter {
        final UUID id;
        final String name;
        Location returnLoc;
        ItemStack[] inventory;   // 主背包 36 格
        ItemStack[] armor;       // 头/胸/腿/脚
        ItemStack offhand;

        Fighter(Player player) {
            this.id = player.getUniqueId();
            this.name = player.getName();
            this.returnLoc = player.getLocation().clone();
        }

        /** 开打时重新快照（报名后玩家可能整理背包或移动位置） */
        void snapshot(Player player) {
            PlayerInventory inv = player.getInventory();
            this.returnLoc = player.getLocation().clone();
            ItemStack[] storage = inv.getStorageContents();
            this.inventory = new ItemStack[storage.length];
            for (int i = 0; i < storage.length; i++) {
                this.inventory[i] = storage[i] == null ? null : storage[i].clone();
            }
            ItemStack[] raw = inv.getArmorContents();
            this.armor = new ItemStack[raw.length];
            for (int i = 0; i < raw.length; i++) {
                this.armor[i] = raw[i] == null ? null : raw[i].clone();
            }
            ItemStack hand = inv.getItemInOffHand();
            this.offhand = hand == null ? null : hand.clone();
        }
    }

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final ChiliCraftAPI api;
    private final RealmService realms;
    private final org.slf4j.Logger logger;

    /** Vault 经济适配（插件在场且 integration.vault 开启时可用，缺失降级） */
    private final Economy economy;

    private Phase phase = Phase.IDLE;
    /** 当前窗口的开打时刻（epoch millis），SIGNUP 阶段有效 */
    private long startMillis;
    private final List<Fighter> signupList = new ArrayList<>();
    private final List<Fighter> currentRound = new ArrayList<>();
    private final List<Fighter> nextRound = new ArrayList<>();
    /** 全程追踪参战者快照（含败者，重生时归还） */
    private final Map<UUID, Fighter> fighters = new HashMap<>();
    /** 等待重生归还快照的败者 */
    private final Map<UUID, Fighter> pendingRespawn = new HashMap<>();
    /** 离线淘汰者的快照（上线时归还） */
    private final Map<UUID, Fighter> pendingJoin = new HashMap<>();
    private Fighter fighterA;
    private Fighter fighterB;

    private BukkitTask minuteTask;
    private BukkitTask nextMatchTask;

    ArenaService(JavaPlugin plugin, MartialSettings settings, ChiliCraftAPI api,
                 org.slf4j.Logger logger, RealmService realms) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.logger = logger;
        this.realms = realms;
        this.economy = probeEconomy(plugin);
    }

    // ================= 生命周期 =================

    /** 启动分钟级调度（比对当日开擂时刻） */
    void startTasks() {
        if (minuteTask != null) {
            return;
        }
        minuteTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tickMinute, 20L, 1200L);
    }

    /** 停止全部计划任务（重启窗口/对局推进任务一并取消） */
    void stopTasks() {
        if (minuteTask != null) {
            minuteTask.cancel();
            minuteTask = null;
        }
        if (nextMatchTask != null) {
            nextMatchTask.cancel();
            nextMatchTask = null;
        }
    }

    /** 插件禁用：中断比赛并尽力归还全部快照（onDisable 时背包改动随玩家数据持久化） */
    void shutdown() {
        stopTasks();
        // 待重生败者：死亡状态下的背包改动同样会随玩家数据保存，直接还原即可
        for (Fighter f : pendingRespawn.values()) {
            Player player = Bukkit.getPlayer(f.id);
            if (player != null && player.isOnline() && f.inventory != null) {
                restoreKit(f, player);
            }
        }
        for (Fighter f : fighters.values()) {
            Player player = Bukkit.getPlayer(f.id);
            if (player != null && player.isOnline() && f.inventory != null) {
                restoreKit(f, player);
            }
        }
        phase = Phase.IDLE;
        signupList.clear();
        currentRound.clear();
        nextRound.clear();
        fighters.clear();
        pendingRespawn.clear();
        pendingJoin.clear();
        fighterA = null;
        fighterB = null;
    }

    // ================= 对外查询 =================

    /** 玩家是否正在擂台对局中 */
    boolean isFighting(UUID playerId) {
        return phase == Phase.RUNNING && fighterA != null && fighterB != null
                && (fighterA.id.equals(playerId) || fighterB.id.equals(playerId));
    }

    /** 玩家是否被本届赛程追踪（含待重生/离线待归还）——死亡事件兜底清掉落用 */
    boolean isTracked(UUID playerId) {
        return fighters.containsKey(playerId) || pendingRespawn.containsKey(playerId)
                || pendingJoin.containsKey(playerId);
    }

    /** 擂台是否禁止该玩家施展武学技能（allow-skills=false 且对局中） */
    boolean skillBlocked(Player player) {
        return !settings.arenaAllowSkills && isFighting(player.getUniqueId());
    }

    /** 当前是否处于报名窗口（GUI 报名按钮可用性判定） */
    boolean signupOpen() {
        return phase == Phase.SIGNUP;
    }

    /** 当前是否处于淘汰赛进行中（GUI 状态行展示） */
    boolean running() {
        return phase == Phase.RUNNING;
    }

    /** 距开打的剩余秒数（仅 SIGNUP 阶段有意义，其余时刻返回 0） */
    long signupRemainSeconds() {
        return Math.max(0, (startMillis - System.currentTimeMillis()) / 1000);
    }

    /** 当前报名人数（GUI 状态行展示） */
    int signupCount() {
        return signupList.size();
    }

    /** 当前对阵描述（"A vs B"），无对局时返回 null */
    String currentFight() {
        return fighterA != null && fighterB != null
                ? fighterA.name + " vs " + fighterB.name : null;
    }

    /** 玩家是否已报名本届（GUI 报名/取消按钮切换判定） */
    boolean signedUp(UUID playerId) {
        for (Fighter f : signupList) {
            if (f.id.equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    /** 水平距离是否越出擂台半径 */
    boolean outOfBounds(Player player) {
        World world = plugin.getServer().getWorld(settings.arenaWorld);
        if (world == null || !player.getWorld().equals(world)) {
            return false;
        }
        double dx = player.getLocation().getX() - (settings.arenaCenterX + 0.5);
        double dz = player.getLocation().getZ() - (settings.arenaCenterZ + 0.5);
        return dx * dx + dz * dz > settings.arenaRadius * settings.arenaRadius;
    }

    /** 越界拉回：沿来向压回半径内 1 格，保留 Y 与视角 */
    void pullBack(Player player) {
        double cx = settings.arenaCenterX + 0.5;
        double cz = settings.arenaCenterZ + 0.5;
        double dx = player.getLocation().getX() - cx;
        double dz = player.getLocation().getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            return;
        }
        double keep = settings.arenaRadius - 1;
        Location target = new Location(player.getWorld(),
                cx + dx / len * keep, player.getLocation().getY(), cz + dz / len * keep,
                player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(target);
    }

    // ================= 玩家操作 =================

    /** /martial arena join：报名 */
    void join(Player player) {
        if (!settings.arenaEnabled || phase != Phase.SIGNUP) {
            send(player, "arena-not-open",
                    Map.of("times", String.join(" / ", settings.arenaTimes)));
            return;
        }
        UUID id = player.getUniqueId();
        for (Fighter f : signupList) {
            if (f.id.equals(id)) {
                send(player, "arena-already", Map.of());
                return;
            }
        }
        signupList.add(new Fighter(player));
        send(player, "arena-joined", Map.of("count", String.valueOf(signupList.size())));
    }

    /** /martial arena quit：取消报名（对局中不支持弃赛，走死亡/退赛判定） */
    void quitArena(Player player) {
        UUID id = player.getUniqueId();
        boolean removed = signupList.removeIf(f -> f.id.equals(id));
        removed |= currentRound.removeIf(f -> f.id.equals(id));
        removed |= nextRound.removeIf(f -> f.id.equals(id));
        if (!removed) {
            send(player, "arena-not-joined", Map.of());
            return;
        }
        send(player, "arena-quit", Map.of());
    }

    /** /martial arena：状态面板 */
    void sendStatus(Player player) {
        send(player, "arena-status-header", Map.of());
        String times = String.join(" / ", settings.arenaTimes);
        switch (phase) {
            case SIGNUP -> send(player, "arena-status-signup", Map.of(
                    "seconds", String.valueOf(Math.max(0, (startMillis - System.currentTimeMillis()) / 1000)),
                    "count", String.valueOf(signupList.size()),
                    "times", times));
            case RUNNING -> {
                String fight = fighterA != null && fighterB != null
                        ? fighterA.name + " vs " + fighterB.name
                        : "-";
                send(player, "arena-status-running", Map.of(
                        "count", String.valueOf(currentRound.size() + nextRound.size()
                                + (fighterA != null ? 2 : 0)),
                        "fight", fight));
            }
            default -> send(player, "arena-status-idle", Map.of(
                    "times", times,
                    "signup", String.valueOf(settings.arenaSignupMinutes)));
        }
        String extra = "";
        if (settings.championMoney > 0 && settings.integrationVault && economy != null) {
            extra = " 与 " + economy.format(settings.championMoney);
        }
        send(player, "arena-status-reward", Map.of(
                "souls", String.valueOf(settings.championSouls),
                "extra", extra));
    }

    /** 擂台禁技提示（HandbookListener 施展前调用） */
    void notifySkillBlocked(Player player) {
        send(player, "arena-skills-blocked", Map.of());
    }

    // ================= 回调（ArenaListener 转发） =================

    /** 对局死亡：清掉落与经验（物品在快照中），对手判胜 */
    void handleDeath(Player dead) {
        if (fighterA != null && fighterA.id.equals(dead.getUniqueId())) {
            Fighter deadF = fighterA;
            fighterA = null;
            Player winner = Bukkit.getPlayer(fighterB.id);
            if (winner != null && !winner.isDead()) {
                finishMatch(winner, dead);
            } else {
                // 对手同样阵亡/离线：双方淘汰且各自归还快照
                eliminateWithoutWin(deadF);
                eliminateWithoutWin(fighterB);
                fighterB = null;
                scheduleNextMatch();
            }
            return;
        }
        if (fighterB != null && fighterB.id.equals(dead.getUniqueId())) {
            Fighter deadF = fighterB;
            fighterB = null;
            Player winner = Bukkit.getPlayer(fighterA.id);
            if (winner != null && !winner.isDead()) {
                finishMatch(winner, dead);
            } else {
                eliminateWithoutWin(deadF);
                eliminateWithoutWin(fighterA);
                fighterA = null;
                scheduleNextMatch();
            }
        }
    }

    /** 重生：延迟 1t 归还快照物品并传送回开打前位置 */
    void handleRespawn(Player player) {
        Fighter f = pendingRespawn.remove(player.getUniqueId());
        if (f == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            restoreKit(f, player);
            player.teleport(f.returnLoc);
            send(player, "arena-eliminated", Map.of());
        });
    }

    /** 退赛：报名期移除；对局中判负并当场归还快照（退出时背包改动随玩家数据保存） */
    void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        if (phase == Phase.SIGNUP) {
            signupList.removeIf(f -> f.id.equals(id));
            return;
        }
        if (phase != Phase.RUNNING) {
            return;
        }
        if (fighterA != null && fighterA.id.equals(id)) {
            Fighter loser = fighterA;
            fighterA = null;
            Player winner = Bukkit.getPlayer(fighterB.id);
            if (winner != null && !winner.isDead()) {
                finishMatch(winner, player);
            } else {
                // 对手同样阵亡/离线：归还其快照后推进
                eliminateWithoutWin(fighterB);
                fighterB = null;
                scheduleNextMatch();
            }
            restoreQuitKit(loser, player);
            return;
        }
        if (fighterB != null && fighterB.id.equals(id)) {
            Fighter loser = fighterB;
            fighterB = null;
            Player winner = Bukkit.getPlayer(fighterA.id);
            if (winner != null && !winner.isDead()) {
                finishMatch(winner, player);
            } else {
                eliminateWithoutWin(fighterA);
                fighterA = null;
                scheduleNextMatch();
            }
            restoreQuitKit(loser, player);
            return;
        }
        // 等待下一轮的参战者退出：当场归还快照（背包改动随玩家数据保存）
        currentRound.removeIf(f -> f.id.equals(id));
        nextRound.removeIf(f -> f.id.equals(id));
        Fighter waiting = pendingRespawn.remove(id);
        if (waiting != null) {
            // 死亡未重生即退服：快照转入上线归还队列（登录后经重生事件归还）
            pendingJoin.put(id, waiting);
            return;
        }
        Fighter f = fighters.remove(id);
        if (f != null && f.inventory != null) {
            restoreKit(f, player);
        }
    }

    // ================= 调度与赛程 =================

    /** 分钟心跳：比对当日各开擂时刻，进入报名窗口 / 到点开赛 */
    private void tickMinute() {
        if (!settings.arenaEnabled || phase == Phase.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String timeText : settings.arenaTimes) {
            long start = parseTimeMillis(timeText);
            if (start < 0) {
                continue;
            }
            long announce = start - settings.arenaSignupMinutes * 60_000L;
            if (phase == Phase.SIGNUP) {
                // 已在窗口内：只跟踪当前窗口
                if (start == startMillis) {
                    if (now >= start) {
                        startTournament();
                    } else {
                        broadcast("arena-announce", Map.of(
                                "time", timeText,
                                "left", String.valueOf(Math.max(0, (start - now) / 1000))));
                    }
                    return;
                }
                continue;
            }
            if (announce <= now && now < start) {
                phase = Phase.SIGNUP;
                startMillis = start;
                signupList.clear();
                logger.info("擂台报名已开放（{} 开打）", timeText);
                broadcast("arena-announce", Map.of(
                        "time", timeText,
                        "left", String.valueOf((start - now) / 1000)));
                return;
            }
        }
    }

    /** 到点开赛：报名不足取消；否则全员快照 + 洗牌 + 逐场推进 */
    private void startTournament() {
        phase = Phase.IDLE;
        List<Fighter> roster = new ArrayList<>(signupList);
        signupList.clear();
        List<Fighter> fresh = new ArrayList<>();
        for (Fighter f : roster) {
            Player player = Bukkit.getPlayer(f.id);
            if (player != null && player.isOnline()) {
                f.snapshot(player);
                fresh.add(f);
                fighters.put(f.id, f);
            }
        }
        if (fresh.size() < 2) {
            fighters.clear();
            broadcast("arena-cancelled", Map.of());
            return;
        }
        phase = Phase.RUNNING;
        Collections.shuffle(fresh);
        currentRound.clear();
        currentRound.addAll(fresh);
        nextRound.clear();
        broadcast("arena-start", Map.of("count", String.valueOf(fresh.size())));
        runNextMatch();
    }

    /** 取下一对开战；当前轮打完则滚动下一轮；只剩一人即冠军 */
    private void runNextMatch() {
        nextMatchTask = null;
        if (phase != Phase.RUNNING) {
            return;
        }
        if (currentRound.isEmpty()) {
            if (nextRound.size() == 1) {
                declareChampion(nextRound.remove(0));
                return;
            }
            if (nextRound.isEmpty()) {
                // 无胜者（双方同时阵亡/离线耗尽）：收场
                resetTournament();
                return;
            }
            currentRound.addAll(nextRound);
            nextRound.clear();
        }
        if (currentRound.size() == 1) {
            declareChampion(currentRound.remove(0));
            return;
        }
        fighterA = currentRound.remove(0);
        fighterB = currentRound.remove(0);
        Player a = Bukkit.getPlayer(fighterA.id);
        Player b = Bukkit.getPlayer(fighterB.id);
        if (a == null || b == null) {
            // 一方离线：在场者自动晋级（防御性分支，正常离线已在 handleQuit 判定）
            Player winner = a != null ? a : b;
            Fighter winnerF = a != null ? fighterA : fighterB;
            Fighter loserF = a != null ? fighterB : fighterA;
            fighterA = null;
            fighterB = null;
            if (winner != null) {
                broadcast("arena-win-match",
                        Map.of("winner", winnerF.name, "loser", loserF.name));
                realms.recordArenaWin(winnerF.id);
                nextRound.add(winnerF);
                // 胜者保留在 fighters 中（冠军结算/后续淘汰时归还快照）
                eliminateWithoutWin(loserF);
            } else {
                // 双方均离线：双方淘汰并各自归还
                eliminateWithoutWin(winnerF);
                eliminateWithoutWin(loserF);
            }
            scheduleNextMatch();
            return;
        }
        a.teleport(arenaSpawn(settings.arenaRadius - 2));
        b.teleport(arenaSpawn(-(settings.arenaRadius - 2)));
        prepareFighter(a);
        prepareFighter(b);
        broadcast("arena-match", Map.of("a", a.getName(), "b", b.getName()));
    }

    /** 一场结束：胜者晋级下一轮 + 记一次完成擂台（绣花蹄进度） */
    private void finishMatch(Player winner, Player loser) {
        fighterA = null;
        fighterB = null;
        Fighter winnerF = fighters.get(winner.getUniqueId());
        Fighter loserF = fighters.remove(loser.getUniqueId());
        broadcast("arena-win-match",
                Map.of("winner", winner.getName(), "loser", loser.getName()));
        grantConsolation(loserF);
        realms.recordArenaWin(winner.getUniqueId());
        if (winnerF != null) {
            nextRound.add(winnerF);
        }
        if (loserF != null && loser.isOnline()) {
            pendingRespawn.put(loserF.id, loserF);
        }
        scheduleNextMatch();
    }

    /** 巡演联动：败者安慰礼（下等马），灵魂数 0 或快照缺失时跳过 */
    private void grantConsolation(Fighter loserF) {
        if (loserF == null || settings.loserConsolationSouls <= 0) {
            return;
        }
        api.addSoul(loserF.id, settings.loserConsolationSouls, "arena_loser_consolation");
        Player player = Bukkit.getPlayer(loserF.id);
        if (player != null && player.isOnline()) {
            send(player, "arena-loser-consolation",
                    Map.of("souls", String.valueOf(settings.loserConsolationSouls)));
        }
    }

    /** 延迟 5s 推进下一场（留出播报与重生动画时间；旧句柄覆盖取消） */
    private void scheduleNextMatch() {
        if (nextMatchTask != null) {
            nextMatchTask.cancel();
        }
        nextMatchTask = plugin.getServer().getScheduler()
                .runTaskLater(plugin, this::runNextMatch, 100L);
    }

    /** 冠军结算：归还快照 → 灵魂 + 可选 Vault 金钱 → 发布 martial.arena_win → 播报 */
    private void declareChampion(Fighter champion) {
        phase = Phase.IDLE;
        fighters.remove(champion.id);
        Player player = Bukkit.getPlayer(champion.id);
        if (player != null && player.isOnline()) {
            restoreKit(champion, player);
        }
        if (settings.championSouls > 0) {
            api.addSoul(champion.id, settings.championSouls, "arena_champion");
        }
        if (settings.championMoney > 0 && settings.integrationVault && economy != null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(champion.id);
            economy.depositPlayer(offline, settings.championMoney);
        }
        // 巡演联动：「演」夺冠演出——冠军标题 + 全服音效（champion-show 开关控制）
        if (settings.championShow && player != null && player.isOnline()) {
            player.showTitle(Title.title(
                    Texts.parse("<gold><bold>演</bold></gold>"),
                    Texts.parse("<yellow>" + champion.name + " 即为今日之演</yellow>")));
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }
        api.publish("martial.arena_win", new EventData(champion.id, "arena", 1));
        broadcast("arena-champion", Map.of(
                "champion", champion.name,
                "souls", String.valueOf(settings.championSouls)));
        logger.info("擂台冠军：{}（+{} 灵魂）", champion.name, settings.championSouls);
        resetTournament();
    }

    /** 比赛异常收敛：清空全部名单，回到 IDLE（待重生归还队列保留，由重生/退赛事件闭环消费） */
    private void resetTournament() {
        phase = Phase.IDLE;
        currentRound.clear();
        nextRound.clear();
        fighters.clear();
        fighterA = null;
        fighterB = null;
    }

    // ================= 装备与位置 =================

    /** 清空背包、发放统一 kit、恢复满状态 */
    private void prepareFighter(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(emptyArmor());
        inv.setItemInOffHand(new ItemStack(Material.AIR));
        List<Material> armorMats = settings.arenaKitArmor;
        if (!armorMats.isEmpty()) {
            inv.setHelmet(new ItemStack(armorMats.get(0)));
            if (armorMats.size() > 1) {
                inv.setChestplate(new ItemStack(armorMats.get(1)));
            }
            if (armorMats.size() > 2) {
                inv.setLeggings(new ItemStack(armorMats.get(2)));
            }
            if (armorMats.size() > 3) {
                inv.setBoots(new ItemStack(armorMats.get(3)));
            }
        }
        inv.setItemInMainHand(new ItemStack(settings.arenaKitWeapon));
        AttributeInstance maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        send(player, "arena-teleport", Map.of());
        send(player, "arena-kit", Map.of());
    }

    /** 归还快照（只还原背包，不传送——传送仅重生路径执行） */
    private void restoreKit(Fighter f, Player player) {
        PlayerInventory inv = player.getInventory();
        inv.setContents(f.inventory != null ? f.inventory : new ItemStack[0]);
        inv.setArmorContents(f.armor != null ? f.armor : emptyArmor());
        inv.setItemInOffHand(f.offhand != null ? f.offhand : new ItemStack(Material.AIR));
    }

    /** 退赛者当场归还（PlayerQuitEvent 内背包改动会随玩家数据保存） */
    private void restoreQuitKit(Fighter f, Player player) {
        if (f == null) {
            return;
        }
        fighters.remove(f.id);
        pendingRespawn.remove(f.id);
        if (f.inventory != null) {
            restoreKit(f, player);
        }
    }

    /**
     * 无胜者淘汰（同归于尽/对手离线）：按被淘汰者当前状态归还快照。
     * 在线存活 → 当场归还；在线死亡 → 待重生归还；离线 → 上线归还。
     */
    private void eliminateWithoutWin(Fighter f) {
        if (f == null) {
            return;
        }
        grantConsolation(f);
        fighters.remove(f.id);
        if (f.inventory == null) {
            return;
        }
        Player player = Bukkit.getPlayer(f.id);
        if (player == null) {
            pendingJoin.put(f.id, f);
        } else if (player.isDead()) {
            pendingRespawn.put(f.id, f);
        } else {
            restoreKit(f, player);
        }
    }

    /** 上线：离线淘汰者若仍处死亡状态，转回待重生归还队列；否则延迟 1t 直接归还 */
    void handleJoin(Player player) {
        Fighter f = pendingJoin.remove(player.getUniqueId());
        if (f == null || f.inventory == null) {
            return;
        }
        if (player.isDead()) {
            pendingRespawn.put(f.id, f);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                restoreKit(f, player);
                send(player, "arena-eliminated", Map.of());
            }
        });
    }

    /** 空护甲数组（显式 AIR，避免 null 元素歧义） */
    private static ItemStack[] emptyArmor() {
        return new ItemStack[]{
                new ItemStack(Material.AIR), new ItemStack(Material.AIR),
                new ItemStack(Material.AIR), new ItemStack(Material.AIR)};
    }

    /** 擂台出生点：中心 X 偏移 offset，取地表安全高度 */
    private Location arenaSpawn(double offset) {
        World world = plugin.getServer().getWorld(settings.arenaWorld);
        if (world == null) {
            world = plugin.getServer().getWorlds().get(0);
        }
        int x = settings.arenaCenterX + (int) Math.round(offset);
        int z = settings.arenaCenterZ;
        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    /** Vault 经济适配探测（插件未装或服务未注册返回 null，发放时自动降级） */
    private static Economy probeEconomy(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return null;
        }
        RegisteredServiceProvider<Economy> registration =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return registration != null ? registration.getProvider() : null;
    }

    /** 解析 HH:mm 为今日时刻 epoch millis；非法格式返回 -1 */
    private static long parseTimeMillis(String hhmm) {
        String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return LocalDate.now().atTime(hour, minute)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ================= 发送工具 =================

    /** 全服广播（含控制台） */
    private void broadcast(String key, Map<String, String> placeholders) {
        String template = settings.message(key);
        if (template.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            template = template.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(Texts.parse(template));
        }
        plugin.getServer().getConsoleSender().sendMessage(Texts.parse(template));
    }

    /** 单人提示 */
    private void send(Player player, String key, Map<String, String> placeholders) {
        String template = settings.message(key);
        if (template.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            template = template.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        player.sendMessage(Texts.parse(template));
    }
}

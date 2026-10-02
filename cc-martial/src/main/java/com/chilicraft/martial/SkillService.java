package com.chilicraft.martial;

import com.chilicraft.api.ChiliCraftAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流派技能服务：拜师 / 技能习得与装备 / 施展 / 被动结算 / 武学经验。
 *
 * <p>技能数据全部来自 {@link SkillContent}（skills.yml 数据驱动）；
 * 持久化复用 cc_skills 表（键空间编码，见 {@link PlayerSkillData} 注释），
 * 技能进度随退出/禁用落库，流派/槽位/选中在变更时即时落库。</p>
 *
 * <p>线程契约：公开方法仅主线程调用；RowStore 回调在 DB 线程，
 * 回写游戏状态前统一 runTask 回主线程。</p>
 */
final class SkillService {

    private static final String TABLE = "cc_skills";
    /** 等级缩放系数：数值参数 × (1 + 0.1 × (level-1)) */
    private static final double LEVEL_SCALE = 0.1;
    /** 全局冷却键 */
    private static final String GLOBAL_COOLDOWN = "__global__";
    /** 吸血上限（%，被动 + buff 叠加后封顶） */
    private static final double MAX_LIFESTEAL_PCT = 100.0;

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final ChiliCraftAPI api;
    private final Logger logger;
    private final RealmService realms;
    private final SkillContent content;

    /** 手册物品标记（PDC） */
    private final NamespacedKey handbookKey;

    /** 在线玩家数据（键恒为 UUID，退出/禁用时移除） */
    private final Map<UUID, PlayerSkillData> players = new HashMap<>();
    /** 冷却：playerId -> (冷却键 -> 到期 millis) */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    // ---------------- buff 状态 ----------------
    /** 下次近战增强（STRIKE 附加伤害 / LIFESTEAL_STRIKE 吸血，一次性消耗） */
    private final Map<UUID, StrikeBuff> strikeBuffs = new HashMap<>();
    /** 护盾（absorption，到期回收） */
    private final Map<UUID, ShieldBuff> shieldBuffs = new HashMap<>();

    private BukkitTask heartbeatTask;

    SkillService(JavaPlugin plugin, MartialSettings settings, ChiliCraftAPI api,
                 Logger logger, RealmService realms) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.logger = logger;
        this.realms = realms;
        this.content = new SkillContent(plugin, logger);
        this.handbookKey = new NamespacedKey(plugin, "martial_handbook");
    }

    // ================= 内容 =================

    /** 重建内容注册表（onEnable 与 core.reload 调用） */
    void reloadContent() {
        content.load();
    }

    SkillContent content() {
        return content;
    }

    /** 玩家技能数据快照（命令面板展示用；未载入返回 null） */
    PlayerSkillData data(UUID playerId) {
        return players.get(playerId);
    }

    // ================= 生命周期 =================

    /** 玩家加入：异步载库 → 主线程填充缓存 */
    void load(UUID playerId) {
        api.rowStore().select(TABLE, "player_id = ?", playerId).thenAccept(rows -> {
            PlayerSkillData data = new PlayerSkillData();
            for (Map<String, Object> row : rows) {
                String rowKey = String.valueOf(row.get("skill_id"));
                int level = row.get("level") instanceof Number n ? n.intValue() : 0;
                int xp = row.get("xp") instanceof Number n ? n.intValue() : 0;
                if (rowKey.startsWith("__school__:")) {
                    data.school = rowKey.substring(10);
                } else if (rowKey.startsWith("__selected__:")) {
                    data.selected = rowKey.substring(12);
                } else if (isSlotRow(rowKey)) {
                    data.slots[rowKey.charAt(6) - '0'] = rowKey.substring(10);
                } else if (!rowKey.startsWith("__")) {
                    // 技能进度行（school:skillId）
                    data.skills.put(rowKey, new SkillProgress(level, xp));
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.getServer().getPlayer(playerId) == null) {
                    return; // 载库期间退出：丢弃，等下次登录再载
                }
                players.put(playerId, data);
            });
        }).exceptionally(ex -> {
            logger.warn("载入技能档案失败 {}：{}", playerId, ex.toString());
            return null;
        });
    }

    /** 玩家退出：进度落库 + 释放全部会话状态 */
    void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        PlayerSkillData data = players.remove(id);
        if (data != null && data.dirty) {
            persistProgress(id, data);
        }
        cooldowns.remove(id);
        strikeBuffs.remove(id);
        shieldBuffs.remove(id);
    }

    // ================= 拜师 =================

    /** 拜入流派（一次性；转派需 admin resetschool）；成功即发放武品与武学手册 */
    void joinSchool(Player player, String schoolId) {
        PlayerSkillData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        SkillContent.SchoolDef school = content.schools.get(schoolId);
        if (school == null) {
            send(player, "school-unknown", Map.of("school", schoolId));
            return;
        }
        if (data.school != null) {
            send(player, "school-switch-denied", Map.of());
            return;
        }
        data.school = schoolId;
        api.rowStore().upsert(TABLE, Map.of(
                "player_id", player.getUniqueId(),
                "skill_id", "__school__:" + schoolId,
                "level", 0, "xp", 0), "player_id", "skill_id");
        giveSchoolWeapon(player, school);
        giveHandbook(player);
        send(player, "school-joined", Map.of("school", school.display));
        send(player, "handbook-received", Map.of());
    }

    /** 发放流派武品（MM 物品优先，联动缺失回退原版武器） */
    private void giveSchoolWeapon(Player player, SkillContent.SchoolDef school) {
        ItemStack item = null;
        if (MythicAdapter.available(plugin, settings) && !school.mythicWeapon.isEmpty()) {
            item = MythicAdapter.mythicItem(school.mythicWeapon);
        }
        if (item == null) {
            item = new ItemStack(school.vanillaWeapon);
        }
        giveItem(player, item);
    }

    /** admin 重置流派：清除流派归属、全部技能进度与装备（转派唯一途径） */
    void resetSchool(Player target) {
        PlayerSkillData data = players.get(target.getUniqueId());
        if (data == null) {
            return;
        }
        UUID id = target.getUniqueId();
        if (data.school != null) {
            deleteRow(id, "__school__:" + data.school);
        }
        if (data.selected != null) {
            deleteRow(id, "__selected__:" + data.selected);
        }
        for (int i = 0; i < 5; i++) {
            if (data.slots[i] != null) {
                deleteRow(id, "__slot" + i + "__:" + data.slots[i]);
            }
        }
        for (String key : data.skills.keySet()) {
            deleteRow(id, key);
        }
        data.school = null;
        data.selected = null;
        for (int i = 0; i < 5; i++) {
            data.slots[i] = null;
        }
        data.skills.clear();
        data.dirty = false;
    }

    // ================= 技能操作 =================

    /** 领悟技能（校验拜师 / 流派 / 境界） */
    void learn(Player player, String input) {
        PlayerSkillData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        SkillContent.SkillDef def = resolveSkill(data, input);
        if (def == null) {
            send(player, "skill-unknown", Map.of("skill", input));
            return;
        }
        if (data.school == null) {
            send(player, "school-need-join", Map.of());
            return;
        }
        if (!def.school.equals(data.school)) {
            send(player, "skill-wrong-school", Map.of());
            return;
        }
        if (data.skills.containsKey(def.key)) {
            send(player, "skill-already-learned", Map.of());
            return;
        }
        int realm = realms.realm(player.getUniqueId());
        if (realm < def.requiredRealm) {
            send(player, "skill-need-realm",
                    Map.of("realm", settings.realmDisplay[def.requiredRealm]));
            return;
        }
        data.skills.put(def.key, new SkillProgress(1, 0));
        data.dirty = true;
        send(player, "skill-learned", Map.of("skill", def.display, "level", "1"));
    }

    /** 装备主动技能到空槽（被动无需装备） */
    void equip(Player player, String input) {
        PlayerSkillData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        SkillContent.SkillDef def = resolveSkill(data, input);
        if (def == null) {
            send(player, "skill-unknown", Map.of("skill", input));
            return;
        }
        if (def.isPassive()) {
            send(player, "skill-passive-hint", Map.of());
            return;
        }
        if (!data.skills.containsKey(def.key)) {
            send(player, "skill-locked", Map.of());
            return;
        }
        int slotLimit = settings.skillSlots[realms.realm(player.getUniqueId())];
        for (int i = 0; i < slotLimit; i++) {
            if (def.key.equals(data.slots[i])) {
                send(player, "skill-already-equipped", Map.of());
                return;
            }
        }
        for (int i = 0; i < slotLimit; i++) {
            if (data.slots[i] == null) {
                data.slots[i] = def.key;
                upsertSlot(player.getUniqueId(), i, def.key);
                if (data.selected == null) {
                    data.selected = def.key;
                    upsertSelected(player.getUniqueId(), null, def.key);
                }
                send(player, "skill-equip", Map.of(
                        "skill", def.display,
                        "slot", String.valueOf(i + 1),
                        "slots", String.valueOf(slotLimit)));
                return;
            }
        }
        send(player, "skill-slots-full", Map.of("slots", String.valueOf(slotLimit)));
    }

    /** 卸下已装备技能 */
    void unequip(Player player, String input) {
        PlayerSkillData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        SkillContent.SkillDef def = resolveSkill(data, input);
        if (def == null) {
            send(player, "skill-unknown", Map.of("skill", input));
            return;
        }
        for (int i = 0; i < 5; i++) {
            if (def.key.equals(data.slots[i])) {
                data.slots[i] = null;
                deleteRow(player.getUniqueId(), "__slot" + i + "__:" + def.key);
                if (def.key.equals(data.selected)) {
                    data.selected = null;
                    deleteRow(player.getUniqueId(), "__selected__:" + def.key);
                }
                send(player, "skill-unequip", Map.of("skill", def.display));
                return;
            }
        }
        send(player, "skill-not-equipped", Map.of());
    }

    /** 选定施展技能（必须是已装备的主动技能） */
    void select(Player player, String input) {
        PlayerSkillData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        SkillContent.SkillDef def = resolveSkill(data, input);
        if (def == null) {
            send(player, "skill-unknown", Map.of("skill", input));
            return;
        }
        if (def.isPassive()) {
            send(player, "skill-passive-hint", Map.of());
            return;
        }
        boolean equipped = false;
        for (String slot : data.slots) {
            if (def.key.equals(slot)) {
                equipped = true;
                break;
            }
        }
        if (!equipped) {
            send(player, "skill-need-equip", Map.of("skill", def.display));
            return;
        }
        String old = data.selected;
        data.selected = def.key;
        upsertSelected(player.getUniqueId(), old, def.key);
        send(player, "skill-selected", Map.of("skill", def.display));
    }

    /** 潜行+右键手册：在已装备主动技能中循环切换 */
    void cycleSelected(Player player) {
        PlayerSkillData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        int slotLimit = settings.skillSlots[realms.realm(player.getUniqueId())];
        List<String> equipped = new ArrayList<>();
        for (int i = 0; i < slotLimit; i++) {
            String key = data.slots[i];
            if (key != null && content.skills.containsKey(key)) {
                equipped.add(key);
            }
        }
        if (equipped.isEmpty()) {
            send(player, "skill-not-active", Map.of());
            return;
        }
        int index = data.selected != null ? equipped.indexOf(data.selected) : -1;
        String next = equipped.get((index + 1) % equipped.size());
        SkillContent.SkillDef def = content.skills.get(next);
        if (next.equals(data.selected)) {
            // 仅一个已装备技能：切换无意义，直接反馈
            send(player, "skill-selected", Map.of("skill", def != null ? def.display : next));
            return;
        }
        String old = data.selected;
        data.selected = next;
        upsertSelected(player.getUniqueId(), old, next);
        send(player, "skill-selected", Map.of("skill", def != null ? def.display : next));
    }

    // ================= 施展 =================

    /** 施展当前选中技能（手册右键）；冷却检查 → 类型分发 → 进冷却 → 反馈 */
    void cast(Player player) {
        UUID id = player.getUniqueId();
        PlayerSkillData data = players.get(id);
        if (data == null || data.selected == null) {
            send(player, "skill-not-active", Map.of());
            return;
        }
        SkillContent.SkillDef def = content.skills.get(data.selected);
        SkillProgress progress = data.skills.get(data.selected);
        if (def == null || def.isPassive() || progress == null) {
            send(player, "skill-not-active", Map.of());
            return;
        }
        // 冷却（全局冷却静默拦截，技能冷却显式提示）
        long now = System.currentTimeMillis();
        Map<String, Long> cds = cooldowns.computeIfAbsent(id, k -> new HashMap<>());
        Long globalEnd = cds.get(GLOBAL_COOLDOWN);
        if (globalEnd != null && globalEnd > now) {
            return;
        }
        Long skillEnd = cds.get(def.key);
        if (skillEnd != null && skillEnd > now) {
            send(player, "skill-cooldown", Map.of(
                    "skill", def.display,
                    "left", String.valueOf((skillEnd - now + 999) / 1000)));
            return;
        }
        // 类型分发
        boolean ok;
        double scale = 1.0 + LEVEL_SCALE * (progress.level - 1);
        switch (def.type) {
            case STRIKE -> {
                applyStrike(id, def.param("bonus-damage", 6.0) * scale,
                        (long) def.param("duration-ticks", 60) * 50L);
                ok = true;
            }
            case LIFESTEAL_STRIKE -> {
                applyLifestealStrike(id, def.param("pct", 50.0),
                        (long) def.param("duration-ticks", 60) * 50L);
                ok = true;
            }
            case SHIELD -> {
                applyShield(id, player, def.param("amount", 5.0) * scale,
                        (long) def.param("duration-ticks", 100) * 50L);
                ok = true;
            }
            default -> ok = SkillExecutor.execute(player, def, progress.level);
        }
        if (!ok) {
            return;
        }
        // 进冷却
        if (def.cooldown > 0) {
            cds.put(def.key, now + def.cooldown * 1000L);
        }
        if (settings.globalCooldown > 0) {
            cds.put(GLOBAL_COOLDOWN, now + (long) (settings.globalCooldown * 1000));
        }
        SkillExecutor.playFx(player, def);
        send(player, "skill-cast", Map.of("skill", def.display));
    }

    // ================= 武学经验 =================

    /**
     * 发放武学经验：分配给全部已习得技能（独立升级）。
     * 来源：击杀 xp-kill / Boss xp-boss / 地城 xp-dungeon。
     */
    void addXp(UUID playerId, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerSkillData data = players.get(playerId);
        if (data == null || data.skills.isEmpty()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        for (Map.Entry<String, SkillProgress> entry : data.skills.entrySet()) {
            SkillProgress progress = entry.getValue();
            progress.xp += amount;
            data.dirty = true;
            int[] curve = settings.passiveXpCurve;
            boolean leveled = false;
            while (progress.level < curve.length && progress.xp >= curve[progress.level]) {
                progress.xp -= curve[progress.level];
                progress.level++;
                leveled = true;
            }
            // 升级反馈仅对当前施展中的技能播报
            if (leveled && player != null && entry.getKey().equals(data.selected)) {
                SkillContent.SkillDef def = content.skills.get(entry.getKey());
                send(player, "skill-level-up", Map.of(
                        "skill", def != null ? def.display : entry.getKey(),
                        "level", String.valueOf(progress.level)));
            }
        }
    }

    // ================= 被动与 buff 查询（战斗结算调用） =================

    /**
     * 被动技能主参数值（等级缩放后）。
     * 生效条件：习得 且 所属流派 = 玩家当前流派；同类多个取最高。
     */
    double passiveValue(UUID playerId, SkillType type) {
        PlayerSkillData data = players.get(playerId);
        if (data == null || data.school == null) {
            return 0;
        }
        double best = 0;
        for (Map.Entry<String, SkillProgress> entry : data.skills.entrySet()) {
            SkillContent.SkillDef def = content.skills.get(entry.getKey());
            if (def == null || !def.isPassive() || def.type != type
                    || !def.school.equals(data.school)) {
                continue;
            }
            String paramKey = type.primaryParam();
            double value = def.param(paramKey, 0) * (1.0 + LEVEL_SCALE * (entry.getValue().level - 1));
            best = Math.max(best, value);
        }
        return best;
    }

    /** 暴击倍率（CRIT_CHANCE 的 multiplier，不随等级缩放；无被动返回 1） */
    double critMultiplier(UUID playerId) {
        PlayerSkillData data = players.get(playerId);
        if (data == null || data.school == null) {
            return 1.0;
        }
        double best = 1.0;
        for (Map.Entry<String, SkillProgress> entry : data.skills.entrySet()) {
            SkillContent.SkillDef def = content.skills.get(entry.getKey());
            if (def == null || def.type != SkillType.CRIT_CHANCE
                    || !def.school.equals(data.school)) {
                continue;
            }
            best = Math.max(best, def.param("multiplier", 1.5));
        }
        return best;
    }

    /** 取走「下次近战」附加伤害（一次性消耗，无则 0） */
    double consumeStrikeBonus(UUID playerId) {
        StrikeBuff buff = strikeBuffs.get(playerId);
        if (buff == null) {
            return 0;
        }
        double bonus = buff.bonusDamage;
        buff.bonusDamage = 0;
        if (buff.lifestealPct <= 0) {
            strikeBuffs.remove(playerId);
        }
        return bonus;
    }

    /** 取走「下次近战」吸血比例（一次性消耗，无则 0） */
    double consumeLifestealStrike(UUID playerId) {
        StrikeBuff buff = strikeBuffs.get(playerId);
        if (buff == null) {
            return 0;
        }
        double pct = buff.lifestealPct;
        buff.lifestealPct = 0;
        if (buff.bonusDamage <= 0) {
            strikeBuffs.remove(playerId);
        }
        return pct;
    }

    private void applyStrike(UUID playerId, double bonusDamage, long durationMillis) {
        StrikeBuff buff = strikeBuffs.computeIfAbsent(playerId, k -> new StrikeBuff());
        buff.bonusDamage = bonusDamage;
        buff.expireAt = System.currentTimeMillis() + durationMillis;
    }

    private void applyLifestealStrike(UUID playerId, double pct, long durationMillis) {
        StrikeBuff buff = strikeBuffs.computeIfAbsent(playerId, k -> new StrikeBuff());
        buff.lifestealPct = pct;
        buff.expireAt = System.currentTimeMillis() + durationMillis;
    }

    /** 护盾：absorption 取较大值（金苹果等来源不被覆盖），到期回收 */
    private void applyShield(UUID playerId, Player player, double amount, long durationMillis) {
        ShieldBuff buff = shieldBuffs.computeIfAbsent(playerId, k -> new ShieldBuff());
        buff.amount = Math.max(buff.amount, amount);
        buff.expireAt = System.currentTimeMillis() + durationMillis;
        player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), buff.amount));
    }

    // ================= 武学手册 =================

    /** 创建武学手册（PDC 标记，非手拼 NBT） */
    private ItemStack createHandbook() {
        ItemStack item = new ItemStack(settings.handbookMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Texts.parse("<gold><bold>武学手册</bold></gold>"));
            meta.lore(List.of(
                    Texts.parse("<gray>右键：施展选中技能</gray>"),
                    Texts.parse("<gray>潜行+右键：切换已装备技能</gray>")));
            meta.getPersistentDataContainer().set(handbookKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void giveHandbook(Player player) {
        giveItem(player, createHandbook());
    }

    /** 玩家背包中是否持有武学手册 */
    boolean hasHandbook(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            Byte flag = meta.getPersistentDataContainer().get(handbookKey, PersistentDataType.BYTE);
            if (flag != null && flag == 1) {
                return true;
            }
        }
        return false;
    }

    /** 补发武学手册（admin / 遗失补领） */
    void giveHandbookTo(Player player) {
        giveHandbook(player);
        send(player, "handbook-received", Map.of());
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack rest : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ================= 周期任务 =================

    /** 启动心跳（40t=2s：被动 REGEN/SPEED_BOOST + buff 到期回收） */
    void startTasks() {
        heartbeatTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::heartbeat, 40L, 40L);
    }

    void stopTasks() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        // 过期 buff 回收
        Iterator<Map.Entry<UUID, StrikeBuff>> strikeIt = strikeBuffs.entrySet().iterator();
        while (strikeIt.hasNext()) {
            if (strikeIt.next().getValue().expireAt <= now) {
                strikeIt.remove();
            }
        }
        Iterator<Map.Entry<UUID, ShieldBuff>> shieldIt = shieldBuffs.entrySet().iterator();
        while (shieldIt.hasNext()) {
            Map.Entry<UUID, ShieldBuff> entry = shieldIt.next();
            if (entry.getValue().expireAt <= now) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()
                        && Math.abs(player.getAbsorptionAmount() - entry.getValue().amount) < 0.01) {
                    player.setAbsorptionAmount(0);
                }
                shieldIt.remove();
            }
        }
        // 被动：REGEN 治疗 + SPEED_BOOST 常驻刷新
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerSkillData data = players.get(player.getUniqueId());
            if (data == null || data.school == null) {
                continue;
            }
            double regen = passiveValue(player.getUniqueId(), SkillType.REGEN);
            if (regen > 0) {
                AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double max = attr != null ? attr.getValue() : 20.0;
                if (player.getHealth() > 0 && player.getHealth() < max) {
                    player.setHealth(Math.min(max, player.getHealth() + regen));
                }
            }
            double speedAmp = passiveValue(player.getUniqueId(), SkillType.SPEED_BOOST);
            if (speedAmp > 0) {
                // 时长覆盖下次心跳（80t > 40t），无粒子闪烁
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED, 80, (int) speedAmp, true, false, true));
            }
        }
    }

    // ================= 持久化 =================

    /** 立即落库全部脏数据（退出 / 禁用时调用；流派/槽位/选中已即时落库） */
    void flushNow() {
        for (Map.Entry<UUID, PlayerSkillData> entry : players.entrySet()) {
            if (entry.getValue().dirty) {
                persistProgress(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 释放全部状态（onDisable：先 stopTasks + flushNow 再调用） */
    void clearAll() {
        players.clear();
        cooldowns.clear();
        strikeBuffs.clear();
        shieldBuffs.clear();
    }

    /** 技能进度行落库（DB 线程执行） */
    private void persistProgress(UUID playerId, PlayerSkillData data) {
        for (Map.Entry<String, SkillProgress> entry : data.skills.entrySet()) {
            api.rowStore().upsert(TABLE, Map.of(
                    "player_id", playerId,
                    "skill_id", entry.getKey(),
                    "level", entry.getValue().level,
                    "xp", entry.getValue().xp), "player_id", "skill_id");
        }
    }

    private void upsertSlot(UUID playerId, int slot, String skillKey) {
        api.rowStore().upsert(TABLE, Map.of(
                "player_id", playerId,
                "skill_id", "__slot" + slot + "__:" + skillKey,
                "level", 0, "xp", 0), "player_id", "skill_id");
    }

    /** 选中技能落库（旧选中行为不同键时删除旧行） */
    private void upsertSelected(UUID playerId, String oldKey, String newKey) {
        if (oldKey != null && !oldKey.equals(newKey)) {
            deleteRow(playerId, "__selected__:" + oldKey);
        }
        api.rowStore().upsert(TABLE, Map.of(
                "player_id", playerId,
                "skill_id", "__selected__:" + newKey,
                "level", 0, "xp", 0), "player_id", "skill_id");
    }

    private void deleteRow(UUID playerId, String skillId) {
        api.rowStore().delete(TABLE, "player_id = ? AND skill_id = ?", playerId, skillId);
    }

    // ================= 内部工具 =================

    /** 输入解析：含 ":" 视为完整 key；否则在玩家当前流派内查找 */
    private SkillContent.SkillDef resolveSkill(PlayerSkillData data, String input) {
        if (input.contains(":")) {
            return content.skills.get(input);
        }
        if (data.school == null) {
            return null;
        }
        return content.skills.get(data.school + ":" + input);
    }

    /** 槽位行识别：__slotN__:school:skillId（N=0-4） */
    private static boolean isSlotRow(String rowKey) {
        return rowKey.length() > 10
                && rowKey.startsWith("__slot")
                && rowKey.charAt(6) >= '0' && rowKey.charAt(6) <= '4'
                && rowKey.charAt(7) == '_' && rowKey.charAt(8) == '_' && rowKey.charAt(9) == ':';
    }

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

    // ---------------- buff 载体 ----------------

    /** 下次近战增强（STRIKE 与 LIFESTEAL_STRIKE 共用，字段独立消耗） */
    private static final class StrikeBuff {
        double bonusDamage;
        double lifestealPct;
        long expireAt;
    }

    /** 护盾状态 */
    private static final class ShieldBuff {
        double amount;
        long expireAt;
    }
}

package com.chilicraft.martial;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 数值缓存：refresh() 从活配置重建全部字段（onEnable 与 core.reload 事件时调用）。
 * 字段包内可见，服务与周期任务直接读取，避免每周期穿透配置树。
 *
 * <p>流派/技能/词缀等内容定义（skills.yml）由 {@code SkillContent} 解析，
 * 不在本类职责内。</p>
 */
final class MartialSettings {

    // ---------- 境界 ----------
    /** 境界显示名（索引 0-5，恒为 6 项） */
    String[] realmDisplay = new String[6];
    /** 拙劲：累计击杀阈值 */
    int unlockKillThreshold;
    /** 素青纱：通关地城次数 */
    int unlockDungeonClears;
    /** 铜钱挂：击杀 Boss 次数 */
    int unlockBossKills;
    /** 绣花蹄：完成擂台次数 */
    int unlockArenaWins;
    /** 破天下：世界 Boss id 集合（adventure.boss_killed target 命中即达成） */
    Set<String> unlockWorldBossIds = Set.of();
    /** 每境界可装备主动技能数（索引=境界） */
    int[] skillSlots = new int[6];
    /** 每境界近战伤害加成（%，索引=境界） */
    double[] meleeDamagePct = new double[6];
    /** 每境界受击减伤（%，索引=境界） */
    double[] damageReductionPct = new double[6];
    /** 每境界额外最大生命（半心=1，索引=境界） */
    int[] maxHealthBonus = new int[6];

    // ---------- 擂台 ----------
    boolean arenaEnabled;
    /** 开擂时刻（HH:mm），报名窗口 = 开擂前 signupMinutes 分钟 */
    List<String> arenaTimes = List.of();
    int arenaSignupMinutes;
    String arenaWorld;
    int arenaCenterX;
    int arenaCenterZ;
    double arenaRadius;
    int championSouls;
    double championMoney;
    /** 巡演联动：败者安慰礼（下等马）灵魂数，0=关闭 */
    int loserConsolationSouls;
    /** 巡演联动：夺冠时播放「演」标题演出与全服音效 */
    boolean championShow;
    boolean arenaAllowSkills;
    /** 统一护甲（头/胸/腿/脚） */
    List<Material> arenaKitArmor = List.of();
    Material arenaKitWeapon;

    // ---------- 技能通用 ----------
    Material handbookMaterial;
    double globalCooldown;
    /** 被动升级经验曲线（索引=等级-1） */
    int[] passiveXpCurve = new int[0];
    /** 武学经验：击杀生物 */
    int skillXpKill;
    /** 武学经验：击杀 Boss */
    int skillXpBoss;
    /** 武学经验：通关地城 */
    int skillXpDungeon;

    // ---------- 词缀 ----------
    double affixDropRollChance;
    int affixMaxPerItem;

    // ---------- 外部联动开关（最终生效 = 开关 且 插件在场） ----------
    boolean integrationMythicMobs;
    boolean integrationPlaceholders;
    boolean integrationVault;

    // ---------- 消息模板（MiniMessage 由展示层解析） ----------
    Map<String, String> messageTemplates = Map.of();

    private final JavaPlugin plugin;
    private final Logger logger;

    MartialSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** 从活配置重建全部字段（主线程调用） */
    void refresh() {
        FileConfiguration cfg = plugin.getConfig();

        // ---------- 境界 ----------
        List<String> display = cfg.getStringList("realms.display");
        for (int i = 0; i < 6; i++) {
            realmDisplay[i] = i < display.size() ? display.get(i) : ("境界" + i);
        }
        unlockKillThreshold = Math.max(1, cfg.getInt("realms.unlock.kill-threshold", 50));
        unlockDungeonClears = Math.max(1, cfg.getInt("realms.unlock.dungeon-clears", 1));
        unlockBossKills = Math.max(1, cfg.getInt("realms.unlock.boss-kills", 1));
        unlockArenaWins = Math.max(1, cfg.getInt("realms.unlock.arena-wins", 1));
        unlockWorldBossIds = new HashSet<>(cfg.getStringList("realms.unlock.world-boss-ids"));
        skillSlots = readIntArray(cfg, "realms.skill-slots", new int[]{2, 3, 4, 4, 5, 5}, 6, "skill-slots");
        meleeDamagePct = readDoubleArray(cfg, "realms.bonuses.melee-damage-pct",
                new double[]{0, 5, 10, 15, 20, 30}, 6, "bonuses.melee-damage-pct");
        damageReductionPct = readDoubleArray(cfg, "realms.bonuses.damage-reduction-pct",
                new double[]{0, 0, 3, 6, 10, 15}, 6, "bonuses.damage-reduction-pct");
        maxHealthBonus = readIntArray(cfg, "realms.bonuses.max-health-bonus",
                new int[]{0, 0, 0, 2, 4, 6}, 6, "bonuses.max-health-bonus");

        // ---------- 擂台 ----------
        arenaEnabled = cfg.getBoolean("arena.enabled", true);
        List<String> times = cfg.getStringList("arena.times");
        arenaTimes = times.isEmpty() ? List.of("20:00", "22:00") : List.copyOf(times);
        arenaSignupMinutes = Math.max(1, cfg.getInt("arena.signup-minutes", 15));
        arenaWorld = cfg.getString("arena.world", "world");
        arenaCenterX = cfg.getInt("arena.center-x", 0);
        arenaCenterZ = cfg.getInt("arena.center-z", 0);
        arenaRadius = Math.max(4, cfg.getDouble("arena.radius", 16));
        championSouls = Math.max(0, cfg.getInt("arena.champion-souls", 5000));
        championMoney = Math.max(0, cfg.getDouble("arena.champion-money", 0));
        loserConsolationSouls = Math.max(0, cfg.getInt("arena.loser-consolation-souls", 100));
        championShow = cfg.getBoolean("arena.champion-show", true);
        arenaAllowSkills = cfg.getBoolean("arena.allow-skills", true);
        List<String> armor = cfg.getStringList("arena.kit.armor");
        List<Material> armorMaterials = new ArrayList<>();
        for (String name : armor) {
            Material mat = Material.matchMaterial(name);
            if (mat == null) {
                logger.warning("擂台护甲材质无效：" + name + "，已跳过");
                continue;
            }
            armorMaterials.add(mat);
        }
        arenaKitArmor = List.copyOf(armorMaterials);
        String weapon = cfg.getString("arena.kit.weapon", "IRON_SWORD");
        Material weaponMat = Material.matchMaterial(weapon);
        arenaKitWeapon = weaponMat != null ? weaponMat : Material.IRON_SWORD;

        // ---------- 技能通用 ----------
        String handbook = cfg.getString("skills.handbook-material", "BOOK");
        Material handbookMat = Material.matchMaterial(handbook);
        handbookMaterial = handbookMat != null ? handbookMat : Material.BOOK;
        globalCooldown = Math.max(0, cfg.getDouble("skills.global-cooldown", 1.0));
        passiveXpCurve = readIntArray(cfg, "skills.passive-xp-curve",
                new int[]{10, 25, 50, 90, 140}, 5, "passive-xp-curve");
        skillXpKill = Math.max(0, cfg.getInt("skills.xp-kill", 2));
        skillXpBoss = Math.max(0, cfg.getInt("skills.xp-boss", 10));
        skillXpDungeon = Math.max(0, cfg.getInt("skills.xp-dungeon", 25));

        // ---------- 词缀 ----------
        affixDropRollChance = clamp(cfg.getDouble("affixes.drop-roll-chance", 5.0), 0, 100);
        affixMaxPerItem = Math.max(1, cfg.getInt("affixes.max-per-item", 1));

        // ---------- 联动 ----------
        integrationMythicMobs = cfg.getBoolean("integration.mythicmobs", true);
        integrationPlaceholders = cfg.getBoolean("integration.placeholders", true);
        integrationVault = cfg.getBoolean("integration.vault", true);

        // ---------- 消息 ----------
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    messages.put(key, value);
                }
            }
        }
        messageTemplates = Map.copyOf(messages);

        logger.info("配置已加载：擂台=" + (arenaEnabled ? arenaTimes : "关闭")
                + " 击杀解锁=" + unlockKillThreshold
                + " 世界Boss=" + unlockWorldBossIds.size() + " 个");
    }

    /** 消息模板读取（缺失回退空串，展示层判空跳过） */
    String message(String key) {
        return messageTemplates.getOrDefault(key, "");
    }

    /** 消息模板读取（缺失回退给定默认值，GUI 按钮名/lore 等不可空白场景使用） */
    String messageOr(String key, String def) {
        String value = messageTemplates.get(key);
        return value != null ? value : def;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 读取 int 数组：长度不足用默认值补齐，超长截断 */
    private int[] readIntArray(FileConfiguration cfg, String path, int[] def, int length, String label) {
        List<Integer> raw = cfg.getIntegerList(path);
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            if (i < raw.size()) {
                out[i] = raw.get(i);
            } else if (i < def.length) {
                out[i] = def[i];
            }
        }
        if (raw.size() != length) {
            logger.warning("配置键 " + path + " 应为 " + length + " 项（实际 " + raw.size() + "），缺失项回退默认值");
        }
        return out;
    }

    /** 读取 double 数组：长度不足用默认值补齐，超长截断 */
    private double[] readDoubleArray(FileConfiguration cfg, String path, double[] def, int length, String label) {
        List<Double> raw = cfg.getDoubleList(path);
        double[] out = new double[length];
        for (int i = 0; i < length; i++) {
            if (i < raw.size()) {
                out[i] = raw.get(i);
            } else if (i < def.length) {
                out[i] = def[i];
            }
        }
        if (raw.size() != length) {
            logger.warning("配置键 " + path + " 应为 " + length + " 项（实际 " + raw.size() + "），缺失项回退默认值");
        }
        return out;
    }
}

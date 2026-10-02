package com.chilicraft.soul;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 数值缓存：refresh() 从活配置重建全部字段（onEnable 与 core.reload 事件时调用）。
 * 字段包内可见，服务与周期任务直接读取，避免每周期穿透配置树。
 */
final class SoulSettings {

    // ---------- 夜间判定（与 cc-survival / cc-demon 一致的原版时间刻区间） ----------
    long nightStart;
    long nightEnd;

    // ---------- 死亡结算 ----------
    /** 死亡随机保留物品百分比（0-100） */
    int itemKeepPct;
    /** 死亡灵魂损失百分比（0-100），化为死亡点碎片 */
    int soulLossPct;
    /** 遗物死亡不掉落 */
    boolean protectRelics;
    /** 死亡播报（附临终遗言） */
    boolean deathBroadcast;
    /** 临终遗言有效期（分钟） */
    int lastWordsMaxAgeMinutes;

    // ---------- 灵魂碎片 ----------
    /** 碎片过期时间（小时） */
    int fragmentExpireHours;
    /** 自动拾回半径（格） */
    double fragmentPickupRadius;

    // ---------- 葬礼 ----------
    int funeralCost;
    int funeralDurationSeconds;
    double funeralStandRadius;
    /** 召回基础比例（%） */
    double funeralRecoveryBasePct;
    double bonfireRadius;
    double bonfireBonusPct;
    Set<Material> bonfireMaterials;
    boolean bonfireRequireLit;
    double moonwellRadius;
    double moonwellBonusPct;
    Set<Material> moonwellMaterials;
    boolean moonwellRequireWater;
    double deathPointRadius;
    double deathPointBonusPct;
    /** 召回碎片吸收半径（格） */
    double funeralAbsorbRadius;
    boolean tombstoneEnabled;
    Material tombstoneMaterial;
    /** 供品（巡演联动）：仪式开始时上供旧眼镜，站桩时长按倍率缩短 */
    boolean offeringGlassesEnabled;
    Material offeringGlassesMaterial;
    double offeringGlassesDurationMultiplier;

    // ---------- 遗物 ----------
    int relicDefaultDurability;
    /** 灵韵批量落库间隔（秒） */
    int relicDbFlushIntervalSec;
    Map<String, RelicDefinition> relicDefinitions = Map.of();

    // ---------- 消息模板（messages / feedback 段原始字符串，MiniMessage 由展示层解析） ----------
    Map<String, String> messageTemplates = Map.of();
    Map<String, String> feedbackTemplates = Map.of();

    private final JavaPlugin plugin;
    private final Logger logger;

    SoulSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** 是否为夜间（原版时间刻在 [nightStart, nightEnd) 内） */
    boolean isNight(long worldTime) {
        return worldTime >= nightStart && worldTime < nightEnd;
    }

    /** 取 messages 段模板；缺失返回空串 */
    String message(String key) {
        return messageTemplates.getOrDefault(key, "");
    }

    /** 取 messages 段模板；缺失回退默认（GUI 按钮名不空白） */
    String messageOr(String key, String def) {
        String value = messageTemplates.get(key);
        return value != null ? value : def;
    }

    /** 取 feedback 段模板；缺失返回空串 */
    String feedback(String key) {
        return feedbackTemplates.getOrDefault(key, "");
    }

    /** 按 ID 取遗物定义；未知返回 null */
    RelicDefinition relic(String id) {
        return relicDefinitions.get(id);
    }

    /** 从活配置重建缓存；非法值回退默认并告警 */
    void refresh() {
        FileConfiguration c = plugin.getConfig();

        nightStart = c.getLong("night-start", 13042);
        nightEnd = c.getLong("night-end", 23458);

        itemKeepPct = (int) clamp(c.getDouble("death.item-keep-pct", 50), 0, 100);
        soulLossPct = (int) clamp(c.getDouble("death.soul-loss-pct", 50), 0, 100);
        protectRelics = c.getBoolean("death.protect-relics", true);
        deathBroadcast = c.getBoolean("death.broadcast", true);
        lastWordsMaxAgeMinutes = (int) positive(c, "death.last-words-max-age-minutes", 5);

        fragmentExpireHours = (int) positive(c, "fragment.expire-hours", 24);
        fragmentPickupRadius = positive(c, "fragment.pickup-radius", 4.0);

        funeralCost = (int) nonNegative(c, "funeral.cost", 10000);
        funeralDurationSeconds = (int) positive(c, "funeral.duration-seconds", 60);
        funeralStandRadius = positive(c, "funeral.stand-radius", 1.5);
        funeralRecoveryBasePct = clamp(c.getDouble("funeral.recovery-base-pct", 50), 0, 100);
        bonfireRadius = positive(c, "funeral.bonfire.radius", 5);
        bonfireBonusPct = clamp(c.getDouble("funeral.bonfire.bonus-pct", 10), 0, 100);
        bonfireMaterials = materialSet(c, "funeral.bonfire.materials",
                Set.of(Material.CAMPFIRE, Material.SOUL_CAMPFIRE));
        bonfireRequireLit = c.getBoolean("funeral.bonfire.require-lit", true);
        moonwellRadius = positive(c, "funeral.moonwell.radius", 5);
        moonwellBonusPct = clamp(c.getDouble("funeral.moonwell.bonus-pct", 25), 0, 100);
        moonwellMaterials = materialSet(c, "funeral.moonwell.materials",
                Set.of(Material.CAULDRON));
        moonwellRequireWater = c.getBoolean("funeral.moonwell.require-water", true);
        deathPointRadius = positive(c, "funeral.death-point.radius", 8);
        deathPointBonusPct = clamp(c.getDouble("funeral.death-point.bonus-pct", 50), 0, 100);
        funeralAbsorbRadius = positive(c, "funeral.absorb-radius", 16);
        tombstoneEnabled = c.getBoolean("funeral.tombstone.enabled", true);
        tombstoneMaterial = material(c.getString("funeral.tombstone.material"),
                Material.CHISELED_STONE_BRICKS);
        offeringGlassesEnabled = c.getBoolean("funeral.offerings.old-glasses.enabled", true);
        offeringGlassesMaterial = material(c.getString("funeral.offerings.old-glasses.material"),
                Material.SPYGLASS);
        offeringGlassesDurationMultiplier = clamp(
                c.getDouble("funeral.offerings.old-glasses.duration-multiplier", 0.5), 0.1, 1.0);

        relicDefaultDurability = (int) positive(c, "relics.default-durability", 100);
        relicDbFlushIntervalSec = (int) positive(c, "relics.db-flush-interval", 60);
        relicDefinitions = parseDefinitions(c);

        messageTemplates = loadSection(c, "messages");
        feedbackTemplates = loadSection(c, "feedback");
    }

    // ---------------- 解析辅助 ----------------

    /** 12 遗物内置默认（配置段缺失或全部非法时兜底，保证模块可运行） */
    private static final Map<String, RelicDefinition> DEFAULT_DEFINITIONS = buildDefaults();

    private static Map<String, RelicDefinition> buildDefaults() {
        Map<String, RelicDefinition> m = new LinkedHashMap<>();
        def(m, "umbrella", Material.GRAY_DYE, "<gray>五块钱的伞</gray>",
                "<gray>雨天里，至少灵魂是干的。</gray>", RelicEffect.RAIN_RESIST, 0.20);
        def(m, "soul-lantern", Material.SOUL_LANTERN, "<yellow>引魂灯</yellow>",
                "<gray>碎片循着灯光回到主人身边。</gray>", RelicEffect.FRAGMENT_RADIUS, 4.0);
        def(m, "rice-bowl", Material.BOWL, "<white>深夜食堂的饭碗</white>",
                "<gray>饱腹的灵魂不易被侵蚀。</gray>", RelicEffect.HUNGER_KEEP, 0.20);
        def(m, "moon-shard", Material.PRISMARINE_SHARD, "<aqua>月亮井的碎片</aqua>",
                "<gray>月光下，伤口缓慢愈合。</gray>", RelicEffect.NIGHT_REGEN, 1.0);
        def(m, "water-veil", Material.HEART_OF_THE_SEA, "<blue>水做的纱衣</blue>",
                "<gray>呼吸像潮水一样自然。</gray>", RelicEffect.WATER_BREATH, 1.0);
        def(m, "dream-feather", Material.FEATHER, "<white>半梦的羽毛</white>",
                "<gray>坠落也不过是半场梦。</gray>", RelicEffect.FALL_RESIST, 0.50);
        def(m, "cat-bell", Material.BELL, "<gold>小猫的铃铛</gold>",
                "<gray>饿魔厌恶的清脆声响。</gray>", RelicEffect.DEMON_WARD, 0.25);
        def(m, "earth-map", Material.COMPASS, "<green>地球的第一张地图</green>",
                "<gray>走得再远，也带着方向。</gray>", RelicEffect.SPEED, 0.10);
        def(m, "star-jar", Material.EXPERIENCE_BOTTLE, "<light_purple>群星的罐子</light_purple>",
                "<gray>夜空被装进了瓶子里。</gray>", RelicEffect.NIGHT_VISION, 1.0);
        def(m, "iron-heart", Material.CHAIN, "<gray>混入人类的铁心</gray>",
                "<gray>疼痛减轻了一分。</gray>", RelicEffect.GENERIC_RESIST, 0.10);
        def(m, "tea-cup", Material.GLASS_BOTTLE, "<white>食堂的旧茶杯</white>",
                "<gray>热茶下肚，命也回来一点。</gray>", RelicEffect.DRINK_HEAL, 0.20);
        def(m, "shadow-thread", Material.STRING, "<dark_gray>不安的灵魂之线</dark_gray>",
                "<gray>每一次收割都缠上一缕。</gray>", RelicEffect.KILL_SOUL, 0.10);
        return Map.copyOf(m);
    }

    private static void def(Map<String, RelicDefinition> m, String id, Material material,
                            String display, String lore, RelicEffect effect, double value) {
        m.put(id, new RelicDefinition(id, material, display, lore, effect, value, 100));
    }

    /** 解析 relics.definitions 段；单条 material/effect 非法则跳过该条并告警 */
    private Map<String, RelicDefinition> parseDefinitions(FileConfiguration c) {
        ConfigurationSection section = c.getConfigurationSection("relics.definitions");
        if (section == null || section.getKeys(false).isEmpty()) {
            return DEFAULT_DEFINITIONS;
        }
        Map<String, RelicDefinition> result = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            String path = "relics.definitions." + id;
            Material material = material(c.getString(path + ".material"), null);
            RelicEffect effect = RelicEffect.parse(c.getString(path + ".effect"));
            if (material == null || effect == null) {
                logger.warning(() -> "遗物定义 " + id + " 的 material/effect 非法，已跳过");
                continue;
            }
            String display = c.getString(path + ".display", "<gray>" + id + "</gray>");
            String lore = c.getString(path + ".lore", "");
            double value = c.getDouble(path + ".value", 1.0);
            int durability = (int) positive(c, path + ".durability", relicDefaultDurability);
            result.put(id, new RelicDefinition(id, material, display, lore, effect, value, durability));
        }
        return result.isEmpty() ? DEFAULT_DEFINITIONS : Map.copyOf(result);
    }

    /** 泛化读取 messages / feedback 两段键值模板 */
    private Map<String, String> loadSection(FileConfiguration c, String path) {
        ConfigurationSection section = c.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }

    /** 材质列表解析；列表为空或全部非法时回退默认集合 */
    private Set<Material> materialSet(FileConfiguration c, String path, Set<Material> def) {
        List<String> names = c.getStringList(path);
        if (names == null || names.isEmpty()) {
            return def;
        }
        Set<Material> result = new HashSet<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                result.add(material);
            } else {
                logger.warning(() -> "材质列表 " + path + " 含非法项: " + name + "，已忽略");
            }
        }
        return result.isEmpty() ? def : Set.copyOf(result);
    }

    private Material material(String name, Material def) {
        if (name == null || name.isEmpty()) {
            return def;
        }
        Material material = Material.matchMaterial(name);
        if (material == null) {
            logger.warning(() -> "材质非法: " + name + "，回退默认 " + def);
            return def;
        }
        return material;
    }

    private double positive(FileConfiguration c, String path, double def) {
        double v = c.getDouble(path, def);
        if (v <= 0) {
            warn(path, v, def);
            return def;
        }
        return v;
    }

    private double nonNegative(FileConfiguration c, String path, double def) {
        double v = c.getDouble(path, def);
        if (v < 0) {
            warn(path, v, def);
            return def;
        }
        return v;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void warn(String path, double value, double def) {
        logger.warning(() -> "配置键 " + path + " 非法值 " + value + "，回退默认 " + def);
    }
}

package com.chilicraft.martial;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skills.yml 词缀内容解析（数据驱动，可整包替换）。
 *
 * <p>词缀定义节结构：
 * <pre>
 * affixes:
 *   fengrui:
 *     display: "锋锐"
 *     tier: 1
 *     hook: ATTACK
 *     params:
 *       bonus-damage: 1.0
 *       potion: SLOW          # 字符串参数进 tags
 * </pre></p>
 *
 * <p>load() 在 onEnable 与 core.reload 时调用，重建内存注册表。
 * hook 非法或定义缺失关键键的单条词缀警告并跳过，不影响其余词缀。</p>
 */
final class AffixContent {

    // ---------------- 词缀定义 ----------------
    static final class AffixDef {
        final String id;
        /** 改名附加文本（物品名后追加 " [display]"，按 tier 着色） */
        final String display;
        /** 品阶 1-3（决定改名颜色：1 绿 / 2 蓝 / 3 金） */
        final int tier;
        /** 结算钩子类型 */
        final AffixHook hook;
        /** 数值参数（键名由结算代码约定，见 skills.yml 头部注释） */
        final Map<String, Double> params;
        /** 字符串参数（如 potion 药水名） */
        final Map<String, String> tags;

        AffixDef(String id, String display, int tier, AffixHook hook,
                 Map<String, Double> params, Map<String, String> tags) {
            this.id = id;
            this.display = display;
            this.tier = tier;
            this.hook = hook;
            this.params = params;
            this.tags = tags;
        }

        /** 数值参数读取（缺失回退默认值） */
        double param(String name, double def) {
            Double value = params.get(name);
            return value != null ? value : def;
        }

        /** 字符串参数读取（缺失返回默认值） */
        String tag(String name, String def) {
            String value = tags.get(name);
            return value != null ? value : def;
        }
    }

    // ---------------- 注册表 ----------------
    /** 全部词缀（id -> def，保序） */
    final Map<String, AffixDef> affixes = new LinkedHashMap<>();

    private final JavaPlugin plugin;
    private final Logger logger;

    AffixContent(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** 从数据目录重建注册表（主线程调用） */
    void load() {
        affixes.clear();

        File file = new File(plugin.getDataFolder(), "skills.yml");
        if (!file.exists()) {
            logger.warn("skills.yml 不存在，词缀内容为空");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = yaml.getConfigurationSection("affixes");
        if (root == null) {
            logger.info("词缀内容已加载：0 条词缀");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            AffixHook hook = AffixHook.parse(sec.getString("hook", ""));
            if (hook == null) {
                logger.warn("词缀 {} 的 hook 无效（{}），已跳过", id, sec.getString("hook"));
                continue;
            }
            Map<String, Double> params = new LinkedHashMap<>();
            Map<String, String> tags = new LinkedHashMap<>();
            ConfigurationSection paramSec = sec.getConfigurationSection("params");
            if (paramSec != null) {
                for (String key : paramSec.getKeys(false)) {
                    Object raw = paramSec.get(key);
                    if (raw instanceof Number n) {
                        params.put(key, n.doubleValue());
                    } else if (raw instanceof String s && !s.isEmpty()) {
                        tags.put(key, s);
                    }
                }
            }
            affixes.put(id, new AffixDef(
                    id,
                    sec.getString("display", id),
                    Math.max(1, Math.min(3, sec.getInt("tier", 1))),
                    hook,
                    params,
                    tags));
        }

        logger.info("词缀内容已加载：{} 条词缀", affixes.size());
    }
}

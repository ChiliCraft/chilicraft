package com.chilicraft.adventure;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * bosses.yml 内容装载器：整体重建世界 Boss 注册表（onEnable 与 core.reload 各调一次）。
 */
final class BossContent {

    private final File file;
    private final Logger log;
    private final Map<String, BossDefinition> bosses = new LinkedHashMap<>();

    BossContent(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "bosses.yml");
        this.log = log;
    }

    /** 重新装载；返回注册表（只读视图） */
    Map<String, BossDefinition> reload() {
        bosses.clear();
        if (!file.isFile()) {
            log.warning(() -> "bosses.yml 不存在，世界 Boss 注册表为空：" + file.getPath());
            return Map.copyOf(bosses);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bosses");
        if (root == null) {
            log.warning(() -> "bosses.yml 缺少 bosses 段，注册表为空");
            return Map.copyOf(bosses);
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            BossDefinition def = parse(id, s);
            if (def != null) {
                bosses.put(id, def);
            }
        }
        log.info(() -> "bosses.yml 已装载 " + bosses.size() + " 个世界 Boss 定义");
        return Map.copyOf(bosses);
    }

    private BossDefinition parse(String id, ConfigurationSection s) {
        if (!s.getBoolean("enabled", true)) {
            return null;
        }
        BossDefinition.TriggerType trigger = BossDefinition.parseTrigger(s.getString("trigger", "RANDOM"));
        if (trigger == null) {
            log.warning(() -> "Boss " + id + " 触发类型非法：" + s.getString("trigger") + "，跳过");
            return null;
        }
        int health = Math.max(20, s.getInt("health", 1000));
        int attack = Math.max(0, s.getInt("attack", 5));
        int param = Math.max(0, s.getInt("trigger-param", 50));
        int firstKillSoul = Math.max(0, s.getInt("first-kill-soul", 100));

        List<BossDefinition.Skill> skills = new ArrayList<>();
        ConfigurationSection sk = s.getConfigurationSection("skills");
        if (sk != null) {
            for (String key : sk.getKeys(false)) {
                ConfigurationSection e = sk.getConfigurationSection(key);
                if (e == null) {
                    continue;
                }
                String typeName = e.getString("type", "").toUpperCase(Locale.ROOT);
                BossDefinition.SkillType type;
                try {
                    type = BossDefinition.SkillType.valueOf(typeName);
                } catch (IllegalArgumentException ex) {
                    log.warning(() -> "Boss " + id + " 技能 " + key + " 类型非法：" + typeName + "，跳过");
                    continue;
                }
                skills.add(new BossDefinition.Skill(type,
                        e.getString("effect", "SLOW").toUpperCase(Locale.ROOT),
                        Math.max(0, e.getInt("amplifier", 0)),
                        Math.max(1, e.getInt("count", 2)),
                        Math.max(3, e.getInt("radius", 8)),
                        e.getString("summon", "ZOMBIE")));
            }
        }
        return new BossDefinition(id,
                s.getString("display", id),
                s.getString("entity", "ZOMBIE"),
                health, attack, trigger, param, firstKillSoul,
                s.getString("mythic-id", ""),
                List.copyOf(skills), true);
    }
}

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
 * dungeons.yml 内容装载器：整体重建地城注册表（onEnable 与 core.reload 各调一次）。
 *
 * <p>非法行跳过并告警；定义缺失的数值回退代码默认——内容文件被删键不应导致模块失效。</p>
 */
final class DungeonContent {

    private final File file;
    private final Logger log;
    private final Map<String, DungeonDefinition> dungeons = new LinkedHashMap<>();

    DungeonContent(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "dungeons.yml");
        this.log = log;
    }

    /** 重新装载；返回注册表（只读视图） */
    Map<String, DungeonDefinition> reload() {
        dungeons.clear();
        if (!file.isFile()) {
            log.warning(() -> "dungeons.yml 不存在，地城注册表为空：" + file.getPath());
            return Map.copyOf(dungeons);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("dungeons");
        if (root == null) {
            log.warning(() -> "dungeons.yml 缺少 dungeons 段，地城注册表为空");
            return Map.copyOf(dungeons);
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            DungeonDefinition def = parse(id, s);
            if (def != null) {
                dungeons.put(id, def);
            }
        }
        log.info(() -> "dungeons.yml 已装载 " + dungeons.size() + " 个地城定义");
        return Map.copyOf(dungeons);
    }

    private DungeonDefinition parse(String id, ConfigurationSection s) {
        String mechanicName = s.getString("mechanic", "GUARD_CAULDRON").toUpperCase(Locale.ROOT);
        DungeonDefinition.Mechanic mechanic;
        try {
            mechanic = DungeonDefinition.Mechanic.valueOf(mechanicName);
        } catch (IllegalArgumentException e) {
            log.warning(() -> "地城 " + id + " 机制类型非法：" + mechanicName + "，跳过");
            return null;
        }
        boolean enabled = s.getBoolean("enabled", true);
        if (!enabled) {
            return null;
        }
        int sizeX = clamp(s.getInt("size.x", 41), 9, 200);
        int sizeY = clamp(s.getInt("size.y", 12), 5, 80);
        int sizeZ = clamp(s.getInt("size.z", 41), 9, 200);
        int waves = clamp(s.getInt("waves", 3), 1, 20);
        int waveSize = clamp(s.getInt("wave-size", 4), 1, 30);
        int offerTarget = clamp(s.getInt("offer-target", 8), 1, 64);
        List<String> offerItems = new ArrayList<>();
        for (String m : s.getStringList("offer-items")) {
            offerItems.add(m.toUpperCase(Locale.ROOT));
        }
        int timeLimit = clamp(s.getInt("time-limit-seconds", 600), 30, 7200);
        int rewardSoul = clamp(s.getInt("reward-soul", 20), 0, 10_000);
        return new DungeonDefinition(id,
                s.getString("display", id),
                mechanic,
                sizeX, sizeY, sizeZ,
                s.getString("template", ""),
                waves, waveSize,
                s.getString("mob", "ZOMBIE"),
                offerTarget, offerItems,
                timeLimit, rewardSoul, enabled);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}

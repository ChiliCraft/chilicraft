package com.chilicraft.martial;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skills.yml 内容解析：流派定义 + 技能定义（数据驱动，可整包替换）。
 *
 * <p>load() 在 onEnable 与 core.reload 时调用，重建内存注册表。
 * 解析失败的单条内容警告并跳过，不影响其余内容。</p>
 */
final class SkillContent {

    // ---------------- 流派定义 ----------------
    static final class SchoolDef {
        final String id;
        final String display;
        /** MythicMobs 武品 id（联动关闭或缺失时回退 vanillaWeapon） */
        final String mythicWeapon;
        final Material vanillaWeapon;

        SchoolDef(String id, String display, String mythicWeapon, Material vanillaWeapon) {
            this.id = id;
            this.display = display;
            this.mythicWeapon = mythicWeapon;
            this.vanillaWeapon = vanillaWeapon;
        }
    }

    // ---------------- 技能定义 ----------------
    static final class SkillDef {
        /** 全局唯一 key：school:skillId */
        final String key;
        final String school;
        final String skillId;
        final String display;
        final SkillType type;
        /** 习得所需境界（0-5） */
        final int requiredRealm;
        /** 独立冷却（秒；0 = 无冷却） */
        final int cooldown;
        /** 数值参数（键名由结算代码约定，见 SkillType 注释） */
        final Map<String, Double> params;
        /** 原版音效名（空 = 无） */
        final String sound;
        /** 原版粒子名（空 = 无） */
        final String particle;
        /** MythicMobs 技能 id（可选：施展时交由 MM 播放特效，缺失回退 fx） */
        final String mythicSkill;

        SkillDef(String key, String school, String skillId, String display, SkillType type,
                 int requiredRealm, int cooldown, Map<String, Double> params,
                 String sound, String particle, String mythicSkill) {
            this.key = key;
            this.school = school;
            this.skillId = skillId;
            this.display = display;
            this.type = type;
            this.requiredRealm = requiredRealm;
            this.cooldown = cooldown;
            this.params = params;
            this.sound = sound;
            this.particle = particle;
            this.mythicSkill = mythicSkill;
        }

        boolean isPassive() {
            return type.isPassive();
        }

        /** 参数读取（缺失回退默认值） */
        double param(String name, double def) {
            Double value = params.get(name);
            return value != null ? value : def;
        }
    }

    // ---------------- 注册表 ----------------
    /** 流派定义（保序，id -> def） */
    final Map<String, SchoolDef> schools = new LinkedHashMap<>();
    /** 全部技能（key=school:skillId -> def） */
    final Map<String, SkillDef> skills = new LinkedHashMap<>();
    /** 按流派分组的技能（schoolId -> key -> def，保序） */
    final Map<String, Map<String, SkillDef>> skillsBySchool = new LinkedHashMap<>();

    private final JavaPlugin plugin;
    private final Logger logger;

    SkillContent(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** 从数据目录重建注册表（主线程调用） */
    void load() {
        schools.clear();
        skills.clear();
        skillsBySchool.clear();

        File file = new File(plugin.getDataFolder(), "skills.yml");
        if (!file.exists()) {
            logger.warn("skills.yml 不存在，武学内容为空");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection schoolSec = yaml.getConfigurationSection("schools");
        if (schoolSec != null) {
            for (String id : schoolSec.getKeys(false)) {
                ConfigurationSection sec = schoolSec.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                String display = sec.getString("display", id);
                String mythic = sec.getString("mythic-weapon", "");
                Material mat = Material.matchMaterial(sec.getString("vanilla-weapon", "IRON_SWORD"));
                if (mat == null) {
                    logger.warn("流派 {} 的 vanilla-weapon 材质无效，回退 IRON_SWORD", id);
                    mat = Material.IRON_SWORD;
                }
                schools.put(id, new SchoolDef(id, display, mythic, mat));
            }
        }

        ConfigurationSection skillSec = yaml.getConfigurationSection("skills");
        if (skillSec != null) {
            for (String schoolId : skillSec.getKeys(false)) {
                if (!schools.containsKey(schoolId)) {
                    logger.warn("技能组 {} 无对应流派定义，整组跳过", schoolId);
                    continue;
                }
                Map<String, SkillDef> group = skillsBySchool.computeIfAbsent(schoolId, k -> new LinkedHashMap<>());
                ConfigurationSection groupSec = skillSec.getConfigurationSection(schoolId);
                if (groupSec == null) {
                    continue;
                }
                for (String skillId : groupSec.getKeys(false)) {
                    ConfigurationSection sec = groupSec.getConfigurationSection(skillId);
                    if (sec == null) {
                        continue;
                    }
                    SkillType type = SkillType.parse(sec.getString("type", ""));
                    if (type == null) {
                        logger.warn("技能 {}:{} 的 type 无效（{}），已跳过", schoolId, skillId, sec.getString("type"));
                        continue;
                    }
                    Map<String, Double> params = new LinkedHashMap<>();
                    ConfigurationSection paramSec = sec.getConfigurationSection("params");
                    if (paramSec != null) {
                        for (String paramKey : paramSec.getKeys(false)) {
                            params.put(paramKey, paramSec.getDouble(paramKey));
                        }
                    }
                    String key = schoolId + ":" + skillId;
                    SkillDef def = new SkillDef(
                            key, schoolId, skillId,
                            sec.getString("display", skillId),
                            type,
                            Math.max(0, Math.min(5, sec.getInt("required-realm", 0))),
                            Math.max(0, sec.getInt("cooldown", 0)),
                            params,
                            sec.getString("fx.sound", ""),
                            sec.getString("fx.particle", ""),
                            sec.getString("mythic-skill", ""));
                    skills.put(key, def);
                    group.put(key, def);
                }
            }
        }

        logger.info("武学内容已加载：流派 {} 个，技能 {} 个", schools.size(), skills.size());
    }
}

package com.chilicraft.martial;

import io.papermc.paper.plugin.configuration.PluginMeta;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI 变量扩展（identifier = {@code chilimartial}）。
 *
 * <p>变量表（全部只读会话缓存，离线玩家返回空串）：</p>
 * <ul>
 *   <li>{@code %chilimartial_realm%} —— 境界显示名；{@code _realm_index%} —— 0-5</li>
 *   <li>{@code %chilimartial_kills% / _dungeons% / _bosses% / _arena_wins%} —— 境界计数</li>
 *   <li>{@code %chilimartial_school%} —— 流派显示名；{@code _school_id%}</li>
 *   <li>{@code %chilimartial_skill%} —— 选中技能显示名；{@code _skill_id% / _skill_level% / _skill_xp%}</li>
 *   <li>{@code %chilimartial_slots%} —— 已装备技能槽数（0-5）</li>
 *   <li>{@code %chilimartial_affixes%} —— 身上带词条物品数（每次请求遍历背包，勿用于逐 tick 刷新的 HUD）</li>
 * </ul>
 *
 * <p>类引用隔离：本类继承 PAPI 类型，仅在 PAPI 在场时才会被加载；
 * 主类只调用 {@link #register} / {@link #unregister} 静态方法并 catch Throwable。</p>
 */
final class MartialExpansion extends PlaceholderExpansion {

    private static MartialExpansion instance;

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final RealmService realms;
    private final SkillService skills;
    private final AffixService affixes;

    private MartialExpansion(JavaPlugin plugin, MartialSettings settings,
                             RealmService realms, SkillService skills, AffixService affixes) {
        this.plugin = plugin;
        this.settings = settings;
        this.realms = realms;
        this.skills = skills;
        this.affixes = affixes;
    }

    /** 注册（PAPI 在场且 integration.placeholders 开启时由主类调用）。
     *  命名避开父类 register()/unregister() 实例方法，防止静态方法遮蔽冲突 */
    static boolean install(JavaPlugin plugin, MartialSettings settings,
                           RealmService realms, SkillService skills, AffixService affixes) {
        if (instance != null) {
            return true;
        }
        instance = new MartialExpansion(plugin, settings, realms, skills, affixes);
        return instance.register();
    }

    /** 注销（onDisable 调用；未注册过为无害空操作） */
    static void remove() {
        if (instance != null) {
            instance.unregister();
            instance = null;
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chilimartial";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ChiliCraft";
    }

    @Override
    public @NotNull String getVersion() {
        PluginMeta meta = plugin.getPluginMeta();
        return meta != null ? meta.getVersion() : "1.0.0";
    }

    /** 核心重载后 expansion 仍有效（数据源为会话缓存，无需重建） */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer offline, @NotNull String params) {
        if (offline == null) {
            return "";
        }
        Player player = Bukkit.getPlayer(offline.getUniqueId());
        if (player == null || !player.isOnline()) {
            return "";
        }
        String key = params.toLowerCase();
        PlayerSkillData skillData = skills.data(player.getUniqueId());
        PlayerMartialData realmData = realms.data(player.getUniqueId());
        return switch (key) {
            case "realm" -> realmData != null
                    ? settings.realmDisplay[Math.max(0, Math.min(5, realmData.realm))]
                    : "";
            case "realm_index" -> realmData != null ? String.valueOf(realmData.realm) : "";
            case "kills" -> realmData != null ? String.valueOf(realmData.kills) : "";
            case "dungeons" -> realmData != null ? String.valueOf(realmData.dungeons) : "";
            case "bosses" -> realmData != null ? String.valueOf(realmData.bosses) : "";
            case "arena_wins" -> realmData != null ? String.valueOf(realmData.arenaWins) : "";
            case "school" -> schoolDisplay(skillData);
            case "school_id" -> skillData != null && skillData.school != null ? skillData.school : "";
            case "skill" -> selectedDisplay(skillData);
            case "skill_id" -> skillData != null && skillData.selected != null ? skillData.selected : "";
            case "skill_level" -> selectedProgress(skillData, true);
            case "skill_xp" -> selectedProgress(skillData, false);
            case "slots" -> {
                if (skillData == null) {
                    yield "";
                }
                int count = 0;
                for (String slot : skillData.slots) {
                    if (slot != null) {
                        count++;
                    }
                }
                yield String.valueOf(count);
            }
            case "affixes" -> String.valueOf(countAffixItems(player));
            default -> null; // 未知变量交还 PAPI（显示原样占位符）
        };
    }

    /** 流派显示名（未拜师返回空串） */
    private String schoolDisplay(PlayerSkillData data) {
        if (data == null || data.school == null) {
            return "";
        }
        SkillContent.SchoolDef def = skills.content().schools.get(data.school);
        return def != null ? def.display : "";
    }

    /** 选中技能显示名（未选返回空串） */
    private String selectedDisplay(PlayerSkillData data) {
        if (data == null || data.selected == null) {
            return "";
        }
        SkillContent.SkillDef def = skills.content().skills.get(data.selected);
        return def != null ? def.display : "";
    }

    /** 选中技能等级/经验（level=true 取等级；未选返回空串） */
    private String selectedProgress(PlayerSkillData data, boolean level) {
        if (data == null || data.selected == null) {
            return "";
        }
        SkillProgress progress = data.skills.get(data.selected);
        return progress != null ? String.valueOf(level ? progress.level : progress.xp) : "";
    }

    /** 身上带词条物品数（背包 + 装备 + 副手） */
    private int countAffixItems(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir() && !affixes.affixesOf(item).isEmpty()) {
                count++;
            }
        }
        return count;
    }
}

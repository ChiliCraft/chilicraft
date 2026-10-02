package com.chilicraft.core.integration;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.PlayerProfile;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * PlaceholderAPI 全局占位符（identifier = {@code chilicraft}）：
 * 只读暴露核心档案（cc_players）字段，供计分板 / 聊天格式 / 全息等外部插件取用。
 *
 * <p>变量表（离线或档案未载返回空串，未知变量交还 PAPI 显示原样）：</p>
 * <ul>
 *   <li>{@code %chilicraft_soul%} —— 灵魂余额</li>
 *   <li>{@code %chilicraft_mode%} —— 全局模式（adventure / cozy）</li>
 *   <li>{@code %chilicraft_realm%} —— 武学境界等级（0 = 未入门）</li>
 *   <li>{@code %chilicraft_profession%} —— 职业标识（空串 = 无职业）</li>
 *   <li>{@code %chilicraft_season_points%} —— 赛季积分</li>
 *   <li>{@code %chilicraft_home_set%} —— 是否已设置家园区锚点（true / false）</li>
 * </ul>
 *
 * <p>类引用隔离：本类继承 PAPI 类型，仅当主类确认 PAPI 在场且联动开启后
 * 才会触达 {@link #install}；PAPI 不在场时本类永不被类加载
 * （onDisable 的 {@link #remove} 同样 catch Throwable 兜底）。</p>
 *
 * <p>边界：只暴露核心档案字段；体温 / 负重等附属模块内部数值不在此列，
 * 后续由对应附属自建 expansion 暴露（保持核心与玩法模块零耦合）。</p>
 */
public final class ChiliCraftExpansion extends PlaceholderExpansion {

    private static ChiliCraftExpansion instance;

    private final ChiliCraftAPI api;
    private final String version;

    private ChiliCraftExpansion(ChiliCraftAPI api, String version) {
        this.api = api;
        this.version = version;
    }

    /** 注册（PAPI 在场且 integrations.placeholders 开启时由主类调用）。
     *  命名避开父类 register()/unregister() 实例方法，防止静态方法遮蔽冲突 */
    public static boolean install(ChiliCraftAPI api, String version) {
        if (instance != null) {
            return true;
        }
        instance = new ChiliCraftExpansion(api, version);
        return instance.register();
    }

    /** 注销（onDisable 调用；未注册过为无害空操作） */
    public static void remove() {
        if (instance != null) {
            instance.unregister();
            instance = null;
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chilicraft";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ChiliCraft";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    /** 核心常驻：PAPI 重载时保留占位符（数据源为实时档案查询，无需重建） */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer offline, @NotNull String params) {
        if (offline == null) {
            return "";
        }
        PlayerProfile profile = api.getProfile(offline.getUniqueId());
        if (profile == null) {
            return ""; // getProfile 仅保证在线玩家非 null
        }
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "soul" -> String.valueOf(profile.soul());
            case "mode" -> profile.mode().name().toLowerCase(Locale.ROOT);
            case "realm" -> String.valueOf(profile.martialRealm());
            case "profession" -> profile.profession();
            case "season_points" -> String.valueOf(profile.seasonPoints());
            case "home_set" -> String.valueOf(profile.isHomeSet());
            default -> null; // 未知变量交还 PAPI（显示原样占位符）
        };
    }
}

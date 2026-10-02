package com.chilicraft.season;

import io.papermc.paper.plugin.configuration.PluginMeta;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** PlaceholderAPI 季节日历只读变量扩展。 */
final class SeasonExpansion extends PlaceholderExpansion {

    private static SeasonExpansion instance;

    private final ChiliSeasonPlugin plugin;
    private final SeasonService service;
    private final SeasonSettings settings;

    private SeasonExpansion(ChiliSeasonPlugin plugin, SeasonService service, SeasonSettings settings) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
    }

    static boolean install(ChiliSeasonPlugin plugin, SeasonService service, SeasonSettings settings) {
        if (instance != null) {
            return true;
        }
        SeasonExpansion expansion = new SeasonExpansion(plugin, service, settings);
        if (!expansion.register()) {
            return false;
        }
        instance = expansion;
        return true;
    }

    static void remove() {
        if (instance != null) {
            instance.unregister();
            instance = null;
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ccseason";
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

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        SeasonService.CalendarState state = service.state();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "season" -> state.season().name().toLowerCase(Locale.ROOT);
            case "year" -> String.valueOf(state.year());
            case "day" -> String.valueOf(state.day());
            case "days_per_season" -> String.valueOf(settings.daysPerSeason);
            default -> null;
        };
    }
}

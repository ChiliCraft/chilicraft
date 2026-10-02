package com.chilicraft.season;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class SeasonService {

    enum Season {
        SPRING,
        SUMMER,
        AUTUMN,
        WINTER;

        static Season parse(String value) {
            Season parsed = parseOrNull(value);
            return parsed != null ? parsed : SPRING;
        }

        static Season parseOrNull(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    record CalendarState(int year, int day, Season season) {
    }

    private final ChiliSeasonPlugin plugin;
    private final ChiliCraftAPI api;
    private final SeasonSettings settings;
    private CalendarState state;
    private long lastWorldDay = -1;
    private long realAnchor = System.currentTimeMillis();

    SeasonService(ChiliSeasonPlugin plugin, ChiliCraftAPI api, SeasonSettings settings) {
        this.plugin = plugin;
        this.api = api;
        this.settings = settings;
        load();
    }

    CalendarState state() {
        return state;
    }

    void tick() {
        if (settings.requirePlayers && Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        if (settings.realMinutes > 0) {
            long periodMillis = settings.realMinutes * 60_000L;
            if (System.currentTimeMillis() - realAnchor >= periodMillis) {
                realAnchor = System.currentTimeMillis();
                nextDay("natural");
            }
            return;
        }
        if (!settings.followWorld) {
            return;
        }
        World world = Bukkit.getWorld(settings.overworld);
        if (world == null) {
            return;
        }
        long worldDay = world.getFullTime() / 24_000L;
        if (lastWorldDay < 0) {
            lastWorldDay = worldDay;
            return;
        }
        long delta = worldDay - lastWorldDay;
        if (delta > 0 && delta <= settings.maxCatchup) {
            lastWorldDay = worldDay;
            while (delta-- > 0) {
                nextDay("natural");
            }
        } else if (delta > settings.maxCatchup || delta < 0) {
            lastWorldDay = worldDay;
            plugin.getLogger().warning("主世界时间跳变过大或发生回退，已重置季节日历基线");
        }
    }

    /** 自然翻日的单一入口。 */
    void nextDay(String trigger) {
        int day = state.day() + 1;
        int year = state.year();
        Season season = state.season();
        boolean seasonChanged = false;
        if (day > settings.daysPerSeason) {
            day = 1;
            season = Season.values()[(season.ordinal() + 1) % Season.values().length];
            seasonChanged = true;
            if (season == Season.SPRING) {
                year++;
            }
        }
        state = new CalendarState(year, day, season);
        persist();
        publishDateEvents(seasonChanged, trigger);
    }

    void setDayFromCommand(int day) {
        int target = Math.max(1, Math.min(settings.daysPerSeason, day));
        state = new CalendarState(state.year(), target, state.season());
        persist();
        publishDateEvents(false, "command");
    }

    void setSeasonFromCommand(Season season) {
        boolean changed = season != state.season();
        state = new CalendarState(state.year(), state.day(), season);
        persist();
        publishDateEvents(changed, "command");
    }

    void advanceFromSleep(World world) {
        if (!settings.advanceSleep || !world.getName().equals(settings.overworld)) {
            return;
        }
        if (!settings.requirePlayers || !Bukkit.getOnlinePlayers().isEmpty()) {
            nextDay("natural");
        }
    }

    private void publishDateEvents(boolean seasonChanged, String trigger) {
        String season = state.season().name().toLowerCase(Locale.ROOT);
        if (seasonChanged) {
            api.publish("season.changed", new EventData(null, season, state.year())
                    .put("day", state.day())
                    .put("trigger", trigger));
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.refreshVisuals());
        }
        api.publish("season.day_changed", new EventData(null, season, state.year())
                .put("day", state.day()));

        String special = plugin.getConfig().getString(
                "special-days." + state.year() + "-" + state.day(), "");
        if (!special.isEmpty()) {
            api.publish("season.special_day", new EventData(null, special, state.year())
                    .put("season", season)
                    .put("day", state.day()));
        }
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "data/calendar.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int year = yaml.getInt("year", plugin.getConfig().getInt("calendar.start-year", 1));
        int day = yaml.getInt("day", plugin.getConfig().getInt("calendar.start-day", 1));
        Season season = Season.parse(yaml.getString(
                "season", plugin.getConfig().getString("calendar.start-season", "spring")));
        state = new CalendarState(
                Math.max(1, year),
                Math.max(1, Math.min(settings.daysPerSeason, day)),
                season
        );
    }

    private void persist() {
        File file = new File(plugin.getDataFolder(), "data/calendar.yml");
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("无法创建季节日历数据目录：" + parent);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("year", state.year());
        yaml.set("day", state.day());
        yaml.set("season", state.season().name().toLowerCase(Locale.ROOT));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("保存季节日历失败：" + e.getMessage());
        }
    }
}

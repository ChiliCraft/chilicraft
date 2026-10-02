package com.chilicraft.season;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class SeasonTask implements Runnable {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final ChiliSeasonPlugin plugin;
    private final SeasonSettings settings;
    private final SeasonService service;
    private final BossBar bar;
    private final Set<UUID> hidden;
    private int weatherTicks;

    SeasonTask(ChiliSeasonPlugin plugin, SeasonSettings settings,
               SeasonService service, Set<UUID> hidden) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
        this.hidden = hidden;
        this.bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
    }

    @Override
    public void run() {
        service.tick();
        weatherTicks++;
        if (settings.weatherEnabled && weatherTicks >= settings.mainPeriodSeconds) {
            weatherTicks = 0;
            applyWeather();
        }
        updateHud();
    }

    private void updateHud() {
        if (!settings.hudEnabled) {
            bar.removeAll();
            return;
        }
        SeasonService.CalendarState state = service.state();
        String season = state.season().name().toLowerCase(Locale.ROOT);
        String title = settings.hudTitle
                .replace("%year%", String.valueOf(state.year()))
                .replace("%season%", season)
                .replace("%day%", String.valueOf(state.day()));
        try {
            bar.setTitle(LEGACY.serialize(MINI.deserialize(title)));
        } catch (RuntimeException exception) {
            bar.setTitle(title);
        }
        bar.setColor(colorOf(state.season()));
        bar.setProgress(Math.min(1.0, Math.max(0.01,
                (double) state.day() / settings.daysPerSeason)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (hidden.contains(player.getUniqueId())) {
                    bar.removePlayer(player);
                } else {
                    bar.addPlayer(player);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("HUD 更新失败：" + exception.getMessage());
            }
        }
    }

    private BarColor colorOf(SeasonService.Season season) {
        BarColor fallback = switch (season) {
            case SPRING -> BarColor.PINK;
            case SUMMER -> BarColor.YELLOW;
            case AUTUMN -> BarColor.RED;
            case WINTER -> BarColor.BLUE;
        };
        String configured = settings.hudColors.get(season.name().toLowerCase(Locale.ROOT));
        if (configured == null) {
            return fallback;
        }
        try {
            return BarColor.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private void applyWeather() {
        for (World world : Bukkit.getWorlds()) {
            if (settings.weatherDisabledWorlds.contains(world.getName())) {
                continue;
            }
            Map<String, Double> weights = settings.weather.getOrDefault(
                    service.state().season().name().toLowerCase(Locale.ROOT), Map.of());
            double clear = weights.getOrDefault("clear", 1.0);
            double rain = weights.getOrDefault("rain", 0.0);
            double thunder = weights.getOrDefault("thunder", 0.0);
            double total = clear + rain + thunder;
            if (total <= 0.0) {
                continue;
            }
            double roll = ThreadLocalRandom.current().nextDouble(total);
            if (roll < clear) {
                world.setStorm(false);
                world.setThundering(false);
            } else if (roll < clear + rain) {
                world.setStorm(true);
                world.setThundering(false);
            } else {
                world.setStorm(true);
                world.setThundering(true);
            }
        }
    }

    void clear() {
        bar.removeAll();
    }
}

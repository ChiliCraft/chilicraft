package com.chilicraft.season;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ChiliSeasonPlugin extends JavaPlugin {

    private ChiliCraftAPI api;
    private SeasonSettings settings;
    private SeasonService service;
    private SeasonTask task;
    private BukkitTask mainTask;
    private BukkitTask migrationTask;
    private EventHandler reloadHandler;
    private final Set<UUID> hidden = new HashSet<>();
    private ProtocolVisualAdapter visual;
    private PapiAdapter papi;
    private SeasonCommand commandHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("无法创建 cc-season 数据目录，插件已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveResource("crops.yml", false);

        RegisteredServiceProvider<ChiliCraftAPI> registration =
                getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
        if (registration == null) {
            getLogger().severe("未找到 cc-core API，cc-season 已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = registration.getProvider();
        try {
            api.registerModuleConfig("cc-season", new LiveModuleConfig(this));
        } catch (IllegalStateException exception) {
            getLogger().severe("模块配置注册失败：" + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        settings = new SeasonSettings(this);
        reloadSettings();
        service = new SeasonService(this, api, settings);
        papi = new PapiAdapter(this, service, settings);
        visual = new ProtocolVisualAdapter(this, service, settings);

        getServer().getPluginManager().registerEvents(new SeasonListener(settings, service), this);
        PluginCommand command = getCommand("ccseason");
        if (command == null) {
            getLogger().severe("缺少 ccseason 命令声明，插件已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SeasonGui seasonGui = new SeasonGui(service, settings, hidden);
        commandHandler = new SeasonCommand(service, settings, hidden, seasonGui);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        try {
            api.registerMenuEntry(new ModuleMenuEntry("season", "季节生态", MenuCategory.WORLD_ECOLOGY,
                    Material.SUNFLOWER, 60, java.util.Set.of(SeasonCommand.USE_PERMISSION),
                    java.util.Set.of(SeasonCommand.ADMIN_PERMISSION, SeasonCommand.LEGACY_ADMIN_PERMISSION),
                    "查看季节状态、HUD、指南与时钟", () -> true, seasonGui::open,
                    p -> p.sendMessage("/cc season info|hud|guide|clock")));
            api.registerCommandRoute(new ModuleCommandRoute("season", java.util.Set.of("ccseason"),
                    java.util.Set.of(SeasonCommand.USE_PERMISSION),
                    java.util.Set.of(SeasonCommand.ADMIN_PERMISSION, SeasonCommand.LEGACY_ADMIN_PERMISSION),
                    commandHandler, commandHandler));
        } catch (RuntimeException exception) {
            api.unregisterMenuEntry("season");
            api.unregisterCommandRoute("season");
            getLogger().severe("统一入口注册失败：" + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        task = new SeasonTask(this, settings, service, hidden);
        mainTask = getServer().getScheduler().runTaskTimer(this, task, 20L, 20L);
        restartMigrationTask();

        reloadHandler = (eventName, data) -> {
            reloadSettings();
            restartMigrationTask();
            if (visual != null) {
                visual.reload();
            }
        };
        api.subscribe("core.reload", reloadHandler);
    }

    private void reloadSettings() {
        reloadConfig();
        settings.refresh();
        File cropsFile = new File(getDataFolder(), "crops.yml");
        settings.loadCrops(YamlConfiguration.loadConfiguration(cropsFile));
    }

    public void refreshVisuals() {
        if (visual != null) {
            visual.refresh();
        }
    }

    private void restartMigrationTask() {
        if (migrationTask != null) {
            migrationTask.cancel();
            migrationTask = null;
        }
        if (!settings.migrationEnabled) {
            return;
        }
        long migrationTicks = settings.migrationPeriod * 20L;
        migrationTask = getServer().getScheduler().runTaskTimer(
                this, new AnimalMigrationTask(this, settings), migrationTicks, migrationTicks);
    }

    @Override
    public void onDisable() {
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
        if (migrationTask != null) {
            migrationTask.cancel();
            migrationTask = null;
        }
        if (task != null) {
            task.clear();
        }
        if (api != null && reloadHandler != null) {
            api.unsubscribe("core.reload", reloadHandler);
        }
        if (api != null) {
            api.unregisterCommandRoute("season");
            api.unregisterMenuEntry("season");
        }
        if (visual != null) {
            visual.close();
        }
        if (papi != null) {
            papi.close();
        }
        hidden.clear();
        api = null;
        settings = null;
        service = null;
        task = null;
        reloadHandler = null;
        visual = null;
        papi = null;
    }
}

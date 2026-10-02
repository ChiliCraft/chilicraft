package com.chilicraft.adventure;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** cc-adventure 生命周期与运行时服务装配入口。 */
public final class ChiliAdventurePlugin extends JavaPlugin {
    private ChiliCraftAPI api;
    private AdventureSettings settings;
    private PartyService parties;
    private DungeonService dungeons;
    private ExpeditionService expeditions;
    private BossService bosses;
    private AdventureGui gui;
    private EventHandler reloadHandler;
    private EventHandler playerDiedHandler;
    private AdventureTask task;
    private AdventureCommand commandHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveContentFile("dungeons.yml");
        saveContentFile("bosses.yml");

        RegisteredServiceProvider<ChiliCraftAPI> registration =
                getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
        api = registration == null ? null : registration.getProvider();
        if (api == null) {
            getLogger().severe("未检测到 cc-core 服务（ChiliCraftAPI），cc-adventure 自动禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Keys.init(this);
        settings = new AdventureSettings(this, getLogger());
        settings.refresh();
        try {
            api.registerModuleConfig("cc-adventure", new LiveModuleConfig(this));
        } catch (IllegalStateException ignored) {
        }

        PartyService partyService = new PartyService(this, settings);
        parties = partyService;
        DungeonContent dungeonContent = new DungeonContent(getDataFolder(), getLogger());
        BossContent bossContent = new BossContent(getDataFolder(), getLogger());
        dungeons = new DungeonService(this, settings, api, parties, dungeonContent, getLogger());
        expeditions = new ExpeditionService(this, settings, api, parties, getLogger());
        bosses = new BossService(this, settings, api, bossContent, getLogger());
        dungeons.reloadContent();
        bosses.reloadContent();

        getServer().getPluginManager().registerEvents(
                new AdventureListener(parties, dungeons, expeditions, bosses), this);
        gui = new AdventureGui(this, settings, parties, dungeons, expeditions, bosses);
        PluginCommand command = getCommand("adventure");
        if (command == null) {
            getLogger().severe("缺少 adventure 命令声明，插件已禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        commandHandler = new AdventureCommand(parties, dungeons, expeditions, bosses, gui);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        try {
            api.registerMenuEntry(new ModuleMenuEntry("adventure", "冒险线", MenuCategory.SURVIVAL_ADVENTURE,
                    Material.IRON_SWORD, 30, java.util.Set.of(AdventureCommand.USE_PERMISSION, AdventureCommand.LEGACY_PERMISSION),
                    java.util.Set.of(AdventureCommand.ADMIN_PERMISSION), "组队、地城、远征与世界 Boss",
                    () -> true, gui::openMain, p -> p.sendMessage("/cc adventure party|dungeon|expedition|boss|status")));
            api.registerCommandRoute(new ModuleCommandRoute("adventure", java.util.Set.of(),
                    java.util.Set.of(AdventureCommand.USE_PERMISSION, AdventureCommand.LEGACY_PERMISSION),
                    java.util.Set.of(AdventureCommand.ADMIN_PERMISSION), commandHandler, commandHandler));
        } catch (RuntimeException exception) {
            api.unregisterMenuEntry("adventure");
            api.unregisterCommandRoute("adventure");
            getLogger().severe("统一入口注册失败：" + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        task = new AdventureTask(dungeons, expeditions, bosses);
        task.runTaskTimer(this, 20L, 20L);
        reloadHandler = (eventName, data) -> getServer().getScheduler().runTask(this, this::handleCoreReload);
        playerDiedHandler = (eventName, data) -> bosses.handlePlayerDied(data);
        api.subscribe("core.reload", reloadHandler);
        api.subscribe("soul.player_died", playerDiedHandler);
        getLogger().info("cc-adventure 已启用");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (api != null && reloadHandler != null) api.unsubscribe("core.reload", reloadHandler);
        if (api != null && playerDiedHandler != null) api.unsubscribe("soul.player_died", playerDiedHandler);
        if (api != null) {
            api.unregisterCommandRoute("adventure");
            api.unregisterMenuEntry("adventure");
        }
        if (bosses != null) bosses.shutdown();
        task = null;
        gui = null;
        bosses = null;
        expeditions = null;
        dungeons = null;
        parties = null;
        reloadHandler = null;
        playerDiedHandler = null;
        settings = null;
        api = null;
    }

    private void handleCoreReload() {
        reloadConfig();
        if (settings != null) settings.refresh();
        if (dungeons != null) dungeons.reloadContent();
        if (bosses != null) bosses.reloadContent();
    }

    private void saveContentFile(String name) {
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.isFile() && getResource(name) != null) saveResource(name, false);
    }
}

package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import org.bukkit.Material;

import java.util.Set;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * cc-soul 主类：灵魂与死亡（死亡结算 / 临终遗言 / 灵魂碎片 / 葬礼仪式 / 12 遗物）。
 *
 * <p>启用流程：取核心 API → 注册模块配置 → 按依赖序装配四服务
 * （relics → fragments → deaths → funeral）→ 异步载库 → 注册监听与命令 →
 * 启动 1 秒统一循环 → 订阅 core.reload。
 * 禁用时先停任务，再冲正遗物灵韵、清空各服务内存态，最后退订事件总线。</p>
 */
public final class ChiliSoulPlugin extends JavaPlugin {

    private ChiliCraftAPI api;
    private SoulSettings settings;
    private RelicService relics;
    private FragmentService fragments;
    private DeathService deaths;
    private FuneralService funeral;
    private SoulGui gui;
    private SoulTickTask tickTask;
    private BukkitTask task;
    private EventHandler reloadHandler;
    private SoulCommand commandHandler;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();

        // 首次运行生成默认配置文件（缺失时 getConfig 返回空配置，管理员将无从调整参数）
        saveDefaultConfig();

        // 硬依赖核心：通过服务管理器取 API，取不到说明核心未启用
        RegisteredServiceProvider<ChiliCraftAPI> registration =
                getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
        if (registration == null) {
            log.error("未找到 cc-core 提供的 ChiliCraftAPI 服务，cc-soul 已自行禁用（请确认核心已安装并启用）");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = registration.getProvider();

        settings = new SoulSettings(this);
        settings.refresh();

        try {
            api.registerModuleConfig("cc-soul", new LiveModuleConfig(this));
        } catch (IllegalStateException e) {
            log.error("模块配置注册失败：{}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 装配顺序即依赖顺序：deaths 依赖 relics+fragments，funeral 依赖前两者与 deaths
        relics = new RelicService(this, settings, api, log);
        fragments = new FragmentService(this, settings, api, log);
        deaths = new DeathService(this, settings, api, relics, fragments, log);
        funeral = new FuneralService(this, settings, api, relics, fragments, deaths, log);

        // GUI 门面：复用 cc-core GuiHolder/GuiListener，本模块零监听器
        gui = new SoulGui(settings, api, relics, fragments, deaths, funeral);

        // 异步载库：回调经 runTask 回主线程填充内存缓存（enable 不被 DB 阻塞）
        relics.load();
        fragments.load();
        deaths.load();

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(new SoulListener(api, relics, deaths, funeral), this);

        PluginCommand command = getCommand("soul");
        if (command == null) {
            log.error("缺少 soul 命令声明，cc-soul 已禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        commandHandler = new SoulCommand(settings, relics, fragments, deaths, funeral, gui,
                () -> {
                    reloadConfig();
                    settings.refresh();
                });
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        try {
            api.registerMenuEntry(new ModuleMenuEntry("soul", "灵魂", MenuCategory.PROGRESSION,
                    Material.SOUL_LANTERN, 10, Set.of("chilicraft.soul.use", "chilicraft.soul.user"),
                    Set.of("chilicraft.soul.admin"), "灵魂、死亡与遗物", () -> true,
                    gui::openMain, player -> commandHandler.execute(player, new String[]{"help"})));
            api.registerCommandRoute(new ModuleCommandRoute("soul", Set.of(),
                    Set.of("chilicraft.soul.use", "chilicraft.soul.user"), Set.of("chilicraft.soul.admin"),
                    commandHandler::execute, commandHandler::complete));
        } catch (RuntimeException e) {
            api.unregisterMenuEntry("soul");
            api.unregisterCommandRoute("soul");
            log.error("统一入口注册失败，cc-soul 已禁用：{}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tickTask = new SoulTickTask(log, settings, relics, fragments, funeral);
        // 1 秒统一循环（延迟 20 ticks 启动，避开服务器启动尖峰）
        task = getServer().getScheduler().runTaskTimer(this, tickTask, 20L, 20L);

        // 核心重载配置时发布 core.reload：自行读盘 + 重建缓存，数字改动 /cc reload 即时生效
        reloadHandler = (eventName, data) -> {
            reloadConfig();
            settings.refresh();
        };
        api.subscribe("core.reload", reloadHandler);

        log.info("cc-soul 已启用（遗物定义={} 灵韵落库间隔={}s）",
                settings.relicDefinitions.size(), settings.relicDbFlushIntervalSec);
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // 事件总线持订阅者强引用，附属禁用必须退订
        if (api != null && reloadHandler != null) {
            api.unsubscribe("core.reload", reloadHandler);
        }
        if (api != null) {
            api.unregisterCommandRoute("soul");
            api.unregisterMenuEntry("soul");
        }
        reloadHandler = null;
        if (relics != null) {
            relics.flushNow();
            relics.clearAll();
        }
        if (fragments != null) {
            fragments.clearAll();
        }
        if (deaths != null) {
            deaths.clearAll();
        }
        if (funeral != null) {
            funeral.clearAll();
        }
        gui = null;
        api = null;
        settings = null;
        relics = null;
        fragments = null;
        deaths = null;
        funeral = null;
        tickTask = null;
    }
}

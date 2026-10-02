package com.chilicraft.demon;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * cc-demon 主类：夜晚饿魔（饿魔刷怪 / 饿意追踪 / 击杀掉落 / 母体召唤）。
 *
 * <p>启用流程：取核心 API → 注册模块配置 → 装配物品与管理器 → 注册监听与命令 →
 * 启动 2 秒统一循环 → 订阅 core.reload 与 street.world_enter（方街安全区联动：
 * 入街瞬间清除玩家周围存量饿魔，持续不刷怪由 spawn.excluded-worlds 保证）。</p>
 * 刷怪强度实时乘核心模式参数 MOB_SPAWN_RATE（getParam 每次穿透），
 * 模式切换与家园区加成即刻生效；配置数字变更经 core.reload 自行重读并重建缓存。</p>
 */
public final class ChiliDemonPlugin extends JavaPlugin {

    private ChiliCraftAPI api;
    private DemonSettings settings;
    private DemonItems items;
    private DemonManager manager;
    private DemonGui gui;
    private DemonStatusGui statusGui;
    private DemonCommand command;
    private DemonTickTask tickTask;
    private BukkitTask task;
    private EventHandler reloadHandler;
    private EventHandler streetHandler;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();

        // 首次运行生成默认配置文件（缺失时 getConfig 返回空配置，管理员将无从调整参数）
        saveDefaultConfig();

        // 硬依赖核心：通过服务管理器取 API，取不到说明核心未启用
        RegisteredServiceProvider<ChiliCraftAPI> registration =
                getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
        if (registration == null) {
            log.error("未找到 cc-core 提供的 ChiliCraftAPI 服务，cc-demon 已自行禁用（请确认核心已安装并启用）");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = registration.getProvider();

        settings = new DemonSettings(this);
        settings.refresh();

        try {
            api.registerModuleConfig("cc-demon", new LiveModuleConfig(this));
        } catch (IllegalStateException e) {
            log.error("模块配置注册失败：{}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        items = new DemonItems(this, settings);
        manager = new DemonManager(this, settings);
        // GUI 门面：复用 cc-core GuiHolder/GuiListener，本模块零监听器
        gui = new DemonGui(settings, manager);
        statusGui = new DemonStatusGui(settings, manager);
        command = new DemonCommand(settings, manager, gui, statusGui, () -> {
            reloadConfig();
            settings.refresh();
        });
        try {
            api.registerMenuEntry(new ModuleMenuEntry("demon", "饿魔", MenuCategory.WORLD_ECOLOGY,
                    org.bukkit.Material.SPAWNER, 20, java.util.Set.of("chilicraft.demon.use"),
                    java.util.Set.of("chilicraft.demon.admin"), "饿魔状态", () -> true,
                    statusGui::open, player -> player.sendMessage(settings.messageOr("help", "查看饿魔状态"))));
            api.registerCommandRoute(new ModuleCommandRoute("demon", java.util.Set.of(),
                    java.util.Set.of("chilicraft.demon.use"), java.util.Set.of("chilicraft.demon.admin"),
                    command::execute, command::complete));
        } catch (RuntimeException e) {
            api.unregisterMenuEntry("demon");
            api.unregisterCommandRoute("demon");
            log.error("统一入口注册失败，cc-demon 已禁用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(new DemonListener(settings, items, manager, api), this);

        // 核心重载配置时发布 core.reload：自行读盘 + 重建缓存，数字改动 /cc reload 即时生效
        reloadHandler = (eventName, data) -> {
            reloadConfig();
            settings.refresh();
        };
        api.subscribe("core.reload", reloadHandler);

        // 巡演联动：方街安全区——入街瞬间清除玩家周围刷怪半径内的存量饿魔
        //（持续不刷怪 / 不索敌由 spawn.excluded-worlds 保证，此处只清存量）
        streetHandler = (eventName, data) -> {
            UUID pid = data.playerId();
            if (pid == null) {
                return;
            }
            Player player = Bukkit.getPlayer(pid);
            if (player != null && player.isOnline()) {
                int removed = manager.clearNear(player, settings.radiusMax);
                if (removed > 0) {
                    log.info("玩家 {} 进入方街，已清除周围 {} 只饿魔", player.getName(), removed);
                }
            }
        };
        api.subscribe("street.world_enter", streetHandler);

        PluginCommand pluginCommand = getCommand("demon");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        tickTask = new DemonTickTask(log, settings, manager, api);
        // 2 秒统一循环（延迟 40 ticks 启动，避开服务器启动尖峰）
        task = getServer().getScheduler().runTaskTimer(this, tickTask, 40L, 40L);

        // MythicMobs 接管提示（规格 v1.1）：开关开启且插件在场时，配置了 MM ID 的饿魔类型由 MM 生成
        if (settings.integrationMythicMobs && plugins.isPluginEnabled("MythicMobs")) {
            log.info("检测到 MythicMobs：已配置 MM 内部 ID 的饿魔类型，技能与行为交由 MythicMobs 接管");
        }

        log.info("cc-demon 已启用（刷怪={} 饿意追踪={} 白天清除={}）",
                settings.spawnEnabled, settings.hungerEnabled, settings.daytimeClear);
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
        if (api != null && streetHandler != null) {
            api.unsubscribe("street.world_enter", streetHandler);
        }
        if (api != null) {
            api.unregisterCommandRoute("demon");
            api.unregisterMenuEntry("demon");
        }
        // 移除全部存活的饿魔，防止插件卸载后属性异常生物残留世界
        if (manager != null) {
            manager.clearAll();
        }
        api = null;
        settings = null;
        items = null;
        gui = null;
        statusGui = null;
        command = null;
        manager = null;
        tickTask = null;
        reloadHandler = null;
        streetHandler = null;
    }
}

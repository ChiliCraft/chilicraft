package com.chilicraft.survival;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * cc-survival 主类：生存压力附属（饥饿 / 体温 / 负重 / 耐久）。
 *
 * <p>启用流程：取核心 API → 注册模块配置 → 装配服务与监听器 →
 * 启动 1 秒统一压力循环 → 订阅 core.reload 与 street.world_enter（巡演联动：入街提示）。
 * 强度实时读取核心模式参数（getParam 每次穿透），模式切换即刻生效；
 * 配置数字变更经 core.reload 事件自行 reloadConfig 并重建数值缓存。</p>
 */
public final class ChiliSurvivalPlugin extends JavaPlugin {

    private ChiliCraftAPI api;
    private SurvivalSettings settings;
    private TemperatureService temperature;
    private WeightService weight;
    private PressureTask pressureTask;
    private BukkitTask task;
    private EventHandler reloadHandler;
    private EventHandler streetHandler;
    private SurvivalGui statusGui;
    private SurvivalCommand command;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();

        // 首次运行生成默认配置文件（缺失时 getConfig 返回空配置，管理员将无从调整参数）
        saveDefaultConfig();

        // 硬依赖核心：通过服务管理器取 API，取不到说明核心未启用
        RegisteredServiceProvider<ChiliCraftAPI> registration =
                getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
        if (registration == null) {
            log.error("未找到 cc-core 提供的 ChiliCraftAPI 服务，cc-survival 已自行禁用（请确认核心已安装并启用）");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = registration.getProvider();

        settings = new SurvivalSettings(this);
        settings.refresh();

        try {
            api.registerModuleConfig("cc-survival", new LiveModuleConfig(this));
        } catch (IllegalStateException e) {
            log.error("模块配置注册失败：{}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        temperature = new TemperatureService(settings);
        weight = new WeightService(settings);

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(new SurvivalListener(settings, weight, temperature, api), this);
        plugins.registerEvents(new DurabilityListener(settings, api), this);

        statusGui = new SurvivalGui(settings, temperature, weight);
        command = new SurvivalCommand(settings, statusGui);
        try {
            api.registerMenuEntry(new ModuleMenuEntry("survival", "生存", MenuCategory.SURVIVAL_ADVENTURE,
                    org.bukkit.Material.CAMPFIRE, 10, java.util.Set.of("chilicraft.survival.use"),
                    java.util.Set.of("chilicraft.survival.admin"), "生存压力状态", () -> true,
                    statusGui::open, player -> player.sendMessage(settings.messageOr("help", "查看生存压力状态"))));
            api.registerCommandRoute(new ModuleCommandRoute("survival", java.util.Set.of(),
                    java.util.Set.of("chilicraft.survival.use"), java.util.Set.of("chilicraft.survival.admin"),
                    command::execute, command::complete));
        } catch (RuntimeException e) {
            api.unregisterMenuEntry("survival");
            api.unregisterCommandRoute("survival");
            log.error("统一入口注册失败，cc-survival 已禁用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        pressureTask = new PressureTask(log, settings, weight, temperature, api);
        // 1 秒统一压力循环（延迟 20 ticks 启动，避开服务器启动尖峰）
        task = getServer().getScheduler().runTaskTimer(this, pressureTask, 20L, 20L);

        // 核心重载配置时发布 core.reload：自行读盘 + 重建缓存，数字改动 /cc reload 即时生效
        reloadHandler = (eventName, data) -> {
            reloadConfig();
            settings.refresh();
        };
        api.subscribe("core.reload", reloadHandler);

        // 巡演联动：方街安全区——入街提示（压力折减由 PressureTask 按 street.worlds 持续生效）
        streetHandler = (eventName, data) -> {
            UUID pid = data.playerId();
            if (pid == null) { return; }
            Player player = Bukkit.getPlayer(pid);
            String template = settings.message("street-calm");
            if (player != null && player.isOnline() && !template.isEmpty()) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(template));
            }
        };
        api.subscribe("street.world_enter", streetHandler);

        log.info("cc-survival 已启用（饥饿={} 体温={} 负重={} 耐久={}）",
                settings.hungerEnabled, settings.tempEnabled,
                settings.weightEnabled, settings.durabilityEnabled);
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
            api.unregisterCommandRoute("survival");
            api.unregisterMenuEntry("survival");
        }
        if (temperature != null) {
            temperature.clear();
        }
        if (weight != null) {
            weight.clear();
        }
        api = null;
        settings = null;
        temperature = null;
        weight = null;
        pressureTask = null;
        reloadHandler = null;
        streetHandler = null;
        statusGui = null;
        command = null;
    }
}

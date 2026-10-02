package com.chilicraft.core;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import com.chilicraft.core.api.ChiliApiImpl;
import com.chilicraft.core.command.CcCommand;
import com.chilicraft.core.command.MenuCommand;
import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.database.DatabaseManager;
import com.chilicraft.core.database.RowStoreImpl;
import com.chilicraft.core.economy.SoulService;
import com.chilicraft.core.event.EventBusImpl;
import com.chilicraft.core.gui.GuiListener;
import com.chilicraft.core.gui.MenuGui;
import com.chilicraft.core.integration.ChiliCraftExpansion;
import com.chilicraft.core.integration.IntegrationManager;
import com.chilicraft.core.param.HomeZoneImpl;
import com.chilicraft.core.param.ParamService;
import com.chilicraft.core.profile.ProfileListener;
import com.chilicraft.core.profile.ProfileManager;
import com.chilicraft.core.text.Messages;
import com.chilicraft.core.module.ModuleRegistry;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * cc-core 主类：装配顺序 = 配置 → 数据库 → 档案 → 服务 → API 注册 →
 * 监听器/指令 → 联动检测 → 自动保存。
 *
 * <p>关停顺序严格相反：停自动保存 → 冲刷档案 → 注销 API → 关数据库
 * （数据库关停内部等待写队列冲刷完剩余任务，保证数据落盘）。
 * 任一环节装配失败抛异常让 Bukkit 禁用本插件，onDisable 对未初始化字段做空保护。</p>
 */
public final class ChiliCorePlugin extends JavaPlugin {

    private Logger logger; // onEnable 时初始化（JavaPlugin 构造期内部状态尚不可用）
    private CoreConfig config;
    private DatabaseManager database;
    private ProfileManager profiles;
    private IntegrationManager integrations;
    private EventBusImpl eventBus; // 事件总线句柄：reloadAll 末尾发布 core.reload 供附属刷新配置
    private ModuleRegistry moduleRegistry;

    @Override
    public void onEnable() {
        this.logger = getSLF4JLogger();

        // 1. 配置（缺失键由 CoreConfig 兜底默认值并告警）
        saveDefaultConfig();
        config = new CoreConfig(logger);
        config.reload(getConfig());

        // 2. 数据库（构造内完成 WAL 设置与模式迁移；失败抛异常禁用本插件）
        Path dbFile = getDataFolder().toPath().resolve("chilicraft.db");
        database = new DatabaseManager(dbFile, logger);

        // 3. 档案与核心服务装配
        profiles = new ProfileManager(database, config, logger);
        Messages messages = new Messages(config);
        this.eventBus = new EventBusImpl(logger);
        SoulService soulService = new SoulService(profiles, logger);
        HomeZoneImpl homeZone = new HomeZoneImpl(profiles, config);
        ParamService paramService = new ParamService(profiles, config, homeZone);
        RowStoreImpl rowStore = new RowStoreImpl(database, logger);
        moduleRegistry = new ModuleRegistry();
        ChiliApiImpl api = new ChiliApiImpl(
                profiles, soulService, eventBus, paramService, homeZone, config,
                rowStore, moduleRegistry, database, this);

        // 4. 对外注册 API（附属经 ServicesManager 取用，全主线程契约）
        getServer().getServicesManager().register(ChiliCraftAPI.class, api, this, ServicePriority.Normal);

        // 5. 监听器
        getServer().getPluginManager().registerEvents(new ProfileListener(profiles, logger), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        // 6. 指令（plugin.yml 声明校验：缺失视为致命装配错误）
        MenuGui menuGui = new MenuGui(api, config, messages);
        CcCommand ccCommand = new CcCommand(profiles, api, config, messages, menuGui, this::reloadAll);
        PluginCommand command = getCommand("cc");
        PluginCommand menuCommand = getCommand("menu");
        if (command == null || menuCommand == null) {
            throw new IllegalStateException("plugin.yml 缺少 cc 或 menu 指令声明，核心不可用");
        }
        command.setExecutor(ccCommand);
        command.setTabCompleter(ccCommand);
        menuCommand.setExecutor(new MenuCommand(menuGui));

        // 7. 联动检测（软依赖：缺失自动降级，绝不阻塞启动）
        integrations = new IntegrationManager(getServer(), config, logger);
        integrations.detect();

        // 7.1 PlaceholderAPI 全局占位符（PAPI 未装或联动关闭时跳过；类引用隔离防 NoClassDefFoundError）
        if (integrations.isEnabled("placeholders")) {
            try {
                boolean ok = ChiliCraftExpansion.install(api, getPluginMeta().getVersion());
                if (ok) {
                    logger.info("PlaceholderAPI 全局占位符已注册（%chilicraft_*%）");
                }
            } catch (Throwable t) {
                logger.warn("PlaceholderAPI 全局占位符注册失败：{}", t.toString());
            }
        }

        // 8. 自动保存（句柄由 ProfileManager 持有，reload/onDisable 时可停）
        profiles.startAutoSave(this);

        logger.info("cc-core 已启用（ChiliCraft v{}）", getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        // PAPI expansion 最先注销（PAPI 不在场时类加载失败属正常路径，吞掉）
        try {
            ChiliCraftExpansion.remove();
        } catch (Throwable ignored) {
            // 忽略
        }
        // 顺序：停任务 → 冲刷 → 注销服务 → 关库（空保护：onEnable 半途失败时 onDisable 仍会执行）
        if (profiles != null) {
            profiles.stopAutoSave();
            profiles.flushAll();
        }
        if (moduleRegistry != null) {
            moduleRegistry.clear();
            moduleRegistry = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        if (database != null) {
            database.shutdown();
        }
    }

    /**
     * /cc reload 动作（主线程）：
     * 重读文件 → 替换配置快照 → 以新间隔重启自动保存 → 重扫联动 →
     * 发布 core.reload 事件（附属订阅后重读自身配置文件）。
     * 消息模板经 CoreConfig.raw 引用随 getConfig() 一并生效。
     */
    private void reloadAll() {
        reloadConfig();
        config.reload(getConfig());
        profiles.stopAutoSave();
        profiles.startAutoSave(this);
        integrations.detect();
        eventBus.publish("core.reload", new EventData());
        logger.info("配置已重载（auto-save 间隔 {} 秒）", getConfig().getInt("auto-save.interval-seconds", 60));
    }
}

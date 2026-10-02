package com.chilicraft.martial;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import org.bukkit.Material;

import java.util.Set;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * cc-martial 主类：武学/擂台附属。
 *
 * <p>装配顺序（与 cc-soul / cc-demon 一致）：
 * 默认配置 → 核心 API → 模块配置注册 → 设置刷新 → 服务装配
 * → 监听器 → 命令 → 周期任务 → 核心事件订阅。</p>
 */
public final class ChiliMartialPlugin extends JavaPlugin {

    private ChiliCraftAPI api;
    private MartialSettings settings;
    private EventHandler reloadHandler;
    private RealmService realms;
    private SkillService skills;
    private AffixService affixes;
    private ArenaService arenas;
    private MartialGui gui;
    private EventHandler adventureDungeonHandler;
    private EventHandler adventureBossHandler;
    private MartialCommand commandHandler;

    @Override
    public void onEnable() {
        // 1. 默认配置（首次启动生成 config.yml；skills.yml 由 saveContentFile 处理）
        saveDefaultConfig();
        saveContentFile("skills.yml");

        // 2. 核心 API：未启用核心时自禁用（附属间硬约束）
        api = getServer().getServicesManager().load(ChiliCraftAPI.class);
        if (api == null) {
            getLogger().severe("未检测到 cc-core 服务（ChiliCraftAPI），cc-martial 自动禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 设置刷新
        settings = new MartialSettings(this);
        settings.refresh();

        // 4. 模块配置注册（核心 /cc reload 时自动合并最新内容）
        try {
            api.registerModuleConfig("cc-martial", new LiveModuleConfig(this));
        } catch (IllegalStateException ignored) {
            // 核心重载后重复注册属正常路径，忽略
        }

        // 5. 服务装配：境界 + 流派技能 + 词缀 + 擂台
        realms = new RealmService(this, settings, api, getSLF4JLogger());
        skills = new SkillService(this, settings, api, getSLF4JLogger(), realms);
        skills.reloadContent();
        skills.startTasks();
        affixes = new AffixService(this, settings, getSLF4JLogger());
        affixes.reloadContent();
        affixes.startTasks();
        arenas = new ArenaService(this, settings, api, getSLF4JLogger(), realms);
        arenas.startTasks();
        gui = new MartialGui(this, settings, realms, skills, arenas);

        // 6. 监听器与命令
        getServer().getPluginManager().registerEvents(
                new MartialListener(settings, realms, skills), this);
        getServer().getPluginManager().registerEvents(
                new HandbookListener(this, skills, arenas), this);
        getServer().getPluginManager().registerEvents(new AffixListener(affixes), this);
        getServer().getPluginManager().registerEvents(new ArenaListener(arenas), this);
        PluginCommand cmd = getCommand("martial");
        if (cmd == null) {
            getLogger().severe("缺少 martial 命令声明，cc-martial 已禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        commandHandler = new MartialCommand(this, settings, realms, skills, affixes, arenas, gui);
        cmd.setExecutor(commandHandler);
        cmd.setTabCompleter(commandHandler);
        try {
            api.registerMenuEntry(new ModuleMenuEntry("martial", "武学", MenuCategory.PROGRESSION,
                    Material.IRON_SWORD, 20, Set.of("chilicraft.martial.use", "chilicraft.martial.user"),
                    Set.of("chilicraft.martial.admin"), "境界、流派、技能与擂台", () -> true,
                    gui::openMain, player -> commandHandler.execute(player, new String[]{"help"})));
            api.registerCommandRoute(new ModuleCommandRoute("martial", Set.of(),
                    Set.of("chilicraft.martial.use", "chilicraft.martial.user"), Set.of("chilicraft.martial.admin"),
                    commandHandler::execute, commandHandler::complete));
        } catch (RuntimeException e) {
            api.unregisterMenuEntry("martial");
            api.unregisterCommandRoute("martial");
            getLogger().severe("统一入口注册失败，cc-martial 已禁用：" + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 6.5 PlaceholderAPI 变量（PAPI 未装或开关关闭时跳过；类引用隔离防 NoClassDefFoundError）
        if (settings.integrationPlaceholders
                && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                boolean ok = MartialExpansion.install(this, settings, realms, skills, affixes);
                if (ok) {
                    getLogger().info("PlaceholderAPI 变量已注册（%chilimartial_*%）");
                }
            } catch (Throwable t) {
                getSLF4JLogger().warn("PlaceholderAPI 变量注册失败：{}", t.toString());
            }
        }

        // 7. 订阅核心重载：刷新设置缓存与内容注册表
        EventHandler reloadListener = (eventName, data) ->
                getServer().getScheduler().runTask(ChiliMartialPlugin.this, ChiliMartialPlugin.this::handleCoreReload);
        reloadHandler = reloadListener;
        api.subscribe("core.reload", reloadListener);

        // 订阅冒险线事件（cc-adventure 未实装时事件不会到达，条件自然悬置）
        adventureDungeonHandler = (eventName, data) -> {
            if (data.playerId() != null) {
                getServer().getScheduler().runTask(ChiliMartialPlugin.this,
                        () -> {
                            realms.recordDungeonClear(data.playerId());
                            skills.addXp(data.playerId(), settings.skillXpDungeon);
                        });
            }
        };
        api.subscribe("adventure.dungeon_clear", adventureDungeonHandler);
        adventureBossHandler = (eventName, data) -> {
            if (data.playerId() != null) {
                String bossId = data.target();
                getServer().getScheduler().runTask(ChiliMartialPlugin.this,
                        () -> {
                            realms.recordBossKill(data.playerId(), bossId);
                            skills.addXp(data.playerId(), settings.skillXpBoss);
                        });
            }
        };
        api.subscribe("adventure.boss_killed", adventureBossHandler);

        getLogger().info("cc-martial 已启用（境界 6 层 / 擂台 "
                + (settings.arenaEnabled ? settings.arenaTimes : "关闭") + "）");
    }

    @Override
    public void onDisable() {
        // PAPI expansion 最先注销：此后不再有变量请求触及下方正在释放的服务
        try {
            MartialExpansion.remove();
        } catch (Throwable ignored) {
            // PAPI 不在场时类加载失败属正常路径
        }
        if (api != null) {
            api.unregisterCommandRoute("martial");
            api.unregisterMenuEntry("martial");
            if (reloadHandler != null) {
                api.unsubscribe("core.reload", reloadHandler);
            }
            if (adventureDungeonHandler != null) {
                api.unsubscribe("adventure.dungeon_clear", adventureDungeonHandler);
            }
            if (adventureBossHandler != null) {
                api.unsubscribe("adventure.boss_killed", adventureBossHandler);
            }
        }
        // 擂台最先中断：归还快照产生的境界进度仍可在下方 realms.flushNow 落库
        if (arenas != null) {
            arenas.shutdown();
        }
        if (skills != null) {
            skills.stopTasks();
            skills.flushNow();
            skills.clearAll();
        }
        if (affixes != null) {
            affixes.stopTasks();
        }
        if (realms != null) {
            realms.flushNow();
            realms.clearAll();
        }
        skills = null;
        affixes = null;
        arenas = null;
        gui = null;
        realms = null;
        api = null;
        settings = null;
        reloadHandler = null;
        adventureDungeonHandler = null;
        adventureBossHandler = null;
    }

    /** 核心重载处理：重读文件并刷新缓存与内容注册表 */
    private void handleCoreReload() {
        reloadConfig();
        if (settings != null) {
            settings.refresh();
        }
        if (skills != null) {
            skills.reloadContent();
        }
        if (affixes != null) {
            affixes.reloadContent();
        }
    }

    /** 资源内容文件落地（skills.yml 等 saveDefaultConfig 覆盖不到的文件） */
    private void saveContentFile(String name) {
        if (getDataFolder().exists() && new java.io.File(getDataFolder(), name).exists()) {
            return;
        }
        if (getResource(name) != null) {
            saveResource(name, false);
        }
    }
}

package com.chilicraft.core.api;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import com.chilicraft.api.EventHandler;
import com.chilicraft.api.GameMode;
import com.chilicraft.api.HomeZone;
import com.chilicraft.api.ModuleConfig;
import com.chilicraft.api.ParamKey;
import com.chilicraft.api.PlayerProfile;
import com.chilicraft.api.RowStore;
import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.database.DatabaseManager;
import com.chilicraft.core.economy.SoulService;
import com.chilicraft.core.event.EventBusImpl;
import com.chilicraft.core.param.HomeZoneImpl;
import com.chilicraft.core.param.ParamService;
import com.chilicraft.core.profile.CraftPlayerProfile;
import com.chilicraft.core.profile.ProfileManager;
import com.chilicraft.core.profile.ProfileRepository;
import com.chilicraft.core.profile.QuestRewardCommit;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import com.chilicraft.core.module.ModuleRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChiliCraftAPI 聚合实现：把档案、灵魂货币、事件总线、
 * 参数/家园区服务与附属配置注册表组装成一个对外实例，
 * 由主类注册进 Bukkit ServicesManager。
 *
 * <p>全部方法遵循主线程契约；写路径入口统一断言主线程，
 * 事件 publish 的断言在 {@link EventBusImpl} 内。</p>
 */
public final class ChiliApiImpl implements ChiliCraftAPI {

    private final ProfileManager profiles;
    private final SoulService soulService;
    private final EventBusImpl eventBus;
    private final ParamService paramService;
    private final HomeZoneImpl homeZone;
    private final CoreConfig config;
    private final RowStore rowStore;
    private final ModuleRegistry moduleRegistry;
    private final DatabaseManager database;
    private final ProfileRepository profileRepository;
    private final JavaPlugin plugin;

    /** 附属配置注册表：moduleId -> 配置实例（onEnable 注册，运行期只读） */
    private final Map<String, ModuleConfig> moduleConfigs = new ConcurrentHashMap<>();

    public ChiliApiImpl(ProfileManager profiles, SoulService soulService, EventBusImpl eventBus,
                       ParamService paramService, HomeZoneImpl homeZone, CoreConfig config, RowStore rowStore,
                       ModuleRegistry moduleRegistry, DatabaseManager database, JavaPlugin plugin) {
        this.profiles = profiles;
        this.soulService = soulService;
        this.eventBus = eventBus;
        this.paramService = paramService;
        this.homeZone = homeZone;
        this.config = config;
        this.rowStore = rowStore;
        this.moduleRegistry = java.util.Objects.requireNonNull(moduleRegistry, "moduleRegistry");
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.profileRepository = new ProfileRepository(database);
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    // ---------------- 档案 ----------------

    @Override
    public PlayerProfile getProfile(UUID playerId) {
        return playerId == null ? null : profiles.getProfile(playerId);
    }

    @Override
    public GameMode getMode(UUID playerId) {
        CraftPlayerProfile profile = playerId == null ? null : profiles.getProfile(playerId);
        return profile == null ? config.defaultMode() : profile.mode();
    }

    @Override
    public void setMode(UUID playerId, GameMode mode) {
        requirePrimaryThread("setMode");
        if (playerId == null) {
            throw new IllegalArgumentException("playerId 不能为 null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为 null");
        }
        CraftPlayerProfile profile = profiles.getProfile(playerId);
        if (profile == null) {
            throw new IllegalStateException("玩家 " + playerId + " 无在线档案，无法切换模式");
        }
        if (profile.mode() == mode) {
            return; // 同模式重复切换：无操作，不占用冷却
        }
        long cooldownMs = config.switchCooldownMs();
        long lastSwitch = profile.modeSwitchAt();
        long now = System.currentTimeMillis();
        if (cooldownMs > 0 && lastSwitch > 0 && now - lastSwitch < cooldownMs) {
            long remainMinutes = (cooldownMs - (now - lastSwitch)) / 60_000L;
            throw new IllegalStateException("模式切换冷却未到，剩余约 " + remainMinutes + " 分钟");
        }
        GameMode from = profile.mode();
        profile.setMode(mode);
        profile.setModeSwitchAt(now);
        eventBus.publish("core.mode_switched",
                new EventData(playerId, mode.name(), 0).put("from", from.name()));
    }

    // ---------------- 灵魂货币 ----------------

    @Override
    public int getSoul(UUID playerId) {
        return soulService.getSoul(playerId);
    }

    @Override
    public void addSoul(UUID playerId, int amount, String reason) {
        requirePrimaryThread("addSoul");
        soulService.addSoul(playerId, amount, reason);
    }

    @Override
    public boolean spendSoul(UUID playerId, int amount, String reason) {
        requirePrimaryThread("spendSoul");
        return soulService.spendSoul(playerId, amount, reason);
    }

    @Override
    public CompletableFuture<com.chilicraft.api.QuestRewardResult> claimQuestReward(
            UUID playerId, String rewardId, String chapterId, String questId, int souls, String reason) {
        requirePrimaryThread("claimQuestReward");
        if (playerId == null || rewardId == null || rewardId.isBlank()
                || chapterId == null || chapterId.isBlank() || questId == null || questId.isBlank()) {
            throw new IllegalArgumentException("任务奖励参数不能为空");
        }
        if (souls < 0) {
            throw new IllegalArgumentException("任务奖励灵魂数量不能为负数");
        }
        if (profiles.getProfile(playerId) == null) {
            return CompletableFuture.completedFuture(
                    new com.chilicraft.api.QuestRewardResult(
                            com.chilicraft.api.QuestRewardStatus.PLAYER_NOT_FOUND,
                            playerId, rewardId, 0));
        }

        CompletableFuture<com.chilicraft.api.QuestRewardResult> result = new CompletableFuture<>();
        database.supply(() -> {
            try {
                return profileRepository.claimQuestReward(
                        playerId, rewardId, chapterId, questId, souls, reason);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((commit, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                result.complete(new com.chilicraft.api.QuestRewardResult(
                        com.chilicraft.api.QuestRewardStatus.FAILED,
                        playerId, rewardId, 0));
                return;
            }
            if (commit.status() == com.chilicraft.api.QuestRewardStatus.SUCCESS) {
                profiles.applyQuestReward(playerId, commit.balanceAfter());
            }
            result.complete(new com.chilicraft.api.QuestRewardResult(
                    commit.status(), playerId, rewardId, commit.balanceAfter()));
        }));
        return result;
    }

    // ---------------- 事件总线 ----------------

    @Override
    public void subscribe(String eventName, EventHandler handler) {
        eventBus.subscribe(eventName, handler);
    }

    @Override
    public void unsubscribe(String eventName, EventHandler handler) {
        eventBus.unsubscribe(eventName, handler);
    }

    @Override
    public void publish(String eventName, EventData data) {
        eventBus.publish(eventName, data); // 主线程断言在 EventBusImpl.publish 内
    }

    // ---------------- 参数与家园区 ----------------

    @Override
    public double getParam(UUID playerId, ParamKey key) {
        return paramService.param(playerId, key);
    }

    @Override
    public HomeZone getHomeZone() {
        return homeZone;
    }

    // ---------------- 附属配置 ----------------

    @Override
    public ModuleConfig getModuleConfig(String moduleId) {
        return moduleId == null ? null : moduleConfigs.get(moduleId);
    }

    @Override
    public void registerModuleConfig(String moduleId, ModuleConfig moduleConfig) {
        if (moduleId == null || moduleId.isEmpty() || moduleConfig == null) {
            throw new IllegalArgumentException("registerModuleConfig 参数非法: moduleId=" + moduleId);
        }
        ModuleConfig prev = moduleConfigs.putIfAbsent(moduleId, moduleConfig);
        if (prev != null) {
            throw new IllegalStateException("模块配置重复注册: " + moduleId);
        }
    }

    @Override
    public void registerMenuEntry(ModuleMenuEntry entry) { moduleRegistry.registerMenuEntry(entry); }

    @Override
    public void unregisterMenuEntry(String moduleId) { moduleRegistry.unregisterMenuEntry(moduleId); }

    @Override
    public java.util.List<ModuleMenuEntry> menuEntries() { return moduleRegistry.menuEntries(); }

    @Override
    public void registerCommandRoute(ModuleCommandRoute route) { moduleRegistry.registerCommandRoute(route); }

    @Override
    public void unregisterCommandRoute(String moduleId) { moduleRegistry.unregisterCommandRoute(moduleId); }

    @Override
    public ModuleCommandRoute getCommandRoute(String moduleIdOrAlias) { return moduleRegistry.getCommandRoute(moduleIdOrAlias); }

    @Override
    public java.util.List<ModuleCommandRoute> commandRoutes() { return moduleRegistry.commandRoutes(); }

    // ---------------- 结构化存储 ----------------

    @Override
    public RowStore rowStore() {
        return rowStore;
    }

    // ---------------- 内部 ----------------

    private static void requirePrimaryThread(String action) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ChiliCraftAPI." + action + " 只允许主线程调用");
        }
    }
}

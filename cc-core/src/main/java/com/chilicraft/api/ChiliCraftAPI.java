package com.chilicraft.api;

import java.util.List;
import java.util.UUID;

/**
 * ChiliCraft 核心 API 契约。
 *
 * <p>线程契约：普通接口方法只允许在服务器主线程调用；任务奖励事务方法也必须从主线程发起，
 * 但数据库工作在核心 DB writer 线程执行，返回 future 完成前不会阻塞主线程。
 * 事件总线 publish 为同步分发，回调内可安全访问游戏状态。</p>
 *
 * <p>获取方式：附属模块 onEnable 中通过 Bukkit 的
 * {@code ServicesManager#getRegistration(ChiliCraftAPI.class)} 拿到实例，
 * 核心未启用时应放弃加载并提示管理员。</p>
 */
public interface ChiliCraftAPI {

    // ---------------- 档案 ----------------

    /**
     * 获取玩家档案只读视图。
     * 仅保证在线玩家返回非 null（登录时预载）；离线玩家返回 null。
     */
    PlayerProfile getProfile(UUID playerId);

    /** 玩家当前全局模式。档案不存在时返回 config 的 mode.default。 */
    GameMode getMode(UUID playerId);

    /**
     * 切换全局模式，触发冷却校验（config: switch-cooldown-hours）。
     *
     * @throws IllegalStateException 档案不存在，或冷却时间未到
     */
    void setMode(UUID playerId, GameMode mode);

    // ---------------- 灵魂货币 ----------------

    /** 灵魂余额。档案不存在返回 0。 */
    int getSoul(UUID playerId);

    /**
     * 增加灵魂。amount 为负表示无校验扣减（内部台账仍会记录）。
     * 需要余额校验的扣减请使用 {@link #spendSoul}。
     */
    void addSoul(UUID playerId, int amount, String reason);

    /**
     * 扣减灵魂（余额校验）。
     * @return 余额足够并已扣减返回 true；不足返回 false 且不产生任何变更
     */
    boolean spendSoul(UUID playerId, int amount, String reason);

    /**
     * 在核心数据库单事务中领取任务奖励。
     * 任务进度、灵魂余额和灵魂台账必须全部成功后才提交。
     */
    java.util.concurrent.CompletableFuture<QuestRewardResult> claimQuestReward(
            UUID playerId,
            String rewardId,
            String chapterId,
            String questId,
            int souls,
            String reason
    );

    // ---------------- 事件总线 ----------------

    /**
     * 订阅事件。事件名遵循「模块名.事件名」规范（如 demon.mob_killed）。
     * 同一 handler 可重复订阅不同事件名。
     */
    void subscribe(String eventName, EventHandler handler);

    /** 取消订阅；handler 未订阅该事件时静默忽略。 */
    void unsubscribe(String eventName, EventHandler handler);

    /** 发布事件：主线程同步分发给全部订阅者。 */
    void publish(String eventName, EventData data);

    // ---------------- 参数与家园区 ----------------

    /**
     * 读取玩家当前生效参数（基准值来自 config: params[mode] 段，
     * 核心已在内部应用家园区加成，例如 MOB_SPAWN_RATE 在自家园区内
     * 乘以 home-zone.mob-multiplier）。
     * 档案不存在时按 config 默认模式取基准值。
     */
    double getParam(UUID playerId, ParamKey key);

    /** 家园区服务。 */
    HomeZone getHomeZone();

    // ---------------- 附属配置 ----------------

    /** 读取附属模块配置；未注册返回 null。 */
    ModuleConfig getModuleConfig(String moduleId);

    /**
     * 附属在 onEnable 时注册自身配置实例（核心据此加载其默认值与数据文件）。
     *
     * @throws IllegalStateException 同一 moduleId 重复注册
     */
    void registerModuleConfig(String moduleId, ModuleConfig config);

    // ---------------- 结构化存储 ----------------

    /**
     * 附属受限存储通道：对预建附属表做行级 CRUD（insert/upsert/update/delete/select）。
     *
     * <p>全部操作经核心 DB 单线程队列串行执行，返回 future 的完成回调在
     * DB 线程——回调内操作游戏状态须先回主线程。表白名单与线程契约详见
     * {@link RowStore}。档案与灵魂货币禁止走此通道（核心私有表不在白名单），
     * 请使用上方专有方法。</p>
     */
    RowStore rowStore();

    void registerMenuEntry(ModuleMenuEntry entry);
    void unregisterMenuEntry(String moduleId);
    List<ModuleMenuEntry> menuEntries();

    void registerCommandRoute(ModuleCommandRoute route);
    void unregisterCommandRoute(String moduleId);
    ModuleCommandRoute getCommandRoute(String moduleIdOrAlias);
    List<ModuleCommandRoute> commandRoutes();
}


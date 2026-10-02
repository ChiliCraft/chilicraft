package com.chilicraft.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 附属结构化存储通道（受限行存储）。
 *
 * <p>规格要求「全部表由 cc-core 管理、禁止附属直连数据库」：本接口是核心
 * 提供给附属的唯一数据访问通道，底层复用核心 DB 单线程写队列，读写串行，
 * 不存在附属与核心之间的写写竞争。</p>
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>方法可在任意线程调用；参数校验同步完成（非法参数当场抛
 *       {@link IllegalArgumentException}）。</li>
 *   <li>SQL 在核心 DB 线程串行执行，返回的 {@link CompletableFuture}
 *       完成回调也在 DB 线程——回调内需要操作游戏状态时必须先
 *       {@code Bukkit.getScheduler().runTask} 回主线程。</li>
 *   <li>SQL 失败时 future 以 {@code CompletionException} 异常完成，
 *       调用方应自行 {@code exceptionally}/{@code join} 处理。</li>
 * </ul>
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>表白名单：仅允许访问预建的附属表（cc_quest_progress、cc_death_records、
 *       cc_soul_fragments、cc_relics、cc_achievements、cc_skills、
 *       cc_expedition_runs、cc_auctions、cc_boat_pairs、cc_discovered_map）；
 *       核心私有表（cc_players、cc_soul_ledger）禁止访问，档案与灵魂货币
 *       请走 {@link ChiliCraftAPI} 专有方法。</li>
 *   <li>列名只允许 {@code [A-Za-z_][A-Za-z0-9_]*}，where 子句只允许
 *       标识符与运算符字符，值一律参数化绑定——防 SQL 注入。</li>
 *   <li>{@link UUID} 值自动序列化为字符串（与核心 DDL 的 TEXT 列一致）。</li>
 * </ul>
 *
 * <h2>where 子句约定</h2>
 * <p>where 为不含 {@code WHERE} 关键字的常量条件串（如
 * {@code "owner_id = ? AND expires_at > ?"}），占位符 {@code ?} 与 args 按序对应。
 * {@link #select} 允许空串（全表）；{@link #update} 与 {@link #delete}
 * 必须给出非空条件，防止误操作全表。</p>
 */
public interface RowStore {

    /**
     * 插入一行。
     *
     * @return AUTOINCREMENT 表返回新行 id；复合主键表返回受影响行数（通常 1）
     */
    CompletableFuture<Integer> insert(String table, Map<String, Object> row);

    /**
     * 插入或整行替换（INSERT OR REPLACE）：命中主键即替换旧行，否则插入。
     * 适用于复合主键表（cc_skills、cc_quest_progress、cc_achievements、
     * cc_discovered_map 等）的进度落库。
     *
     * @param keyColumns 主键列，校验 row 中必须提供且非 null
     * @return 受影响行数（通常 1）
     */
    CompletableFuture<Integer> upsert(String table, Map<String, Object> row, String... keyColumns);

    /**
     * 按条件更新。
     *
     * @return 受影响行数
     */
    CompletableFuture<Integer> update(String table, Map<String, Object> set, String where, Object... args);

    /**
     * 按条件删除。
     *
     * @return 删除行数
     */
    CompletableFuture<Integer> delete(String table, String where, Object... args);

    /**
     * 按条件查询整行。
     *
     * @return 行列表（列名 -> 值；值为 JDBC 原生类型，如 Integer/Long/Double/String/byte[]），
     *         无匹配返回空列表
     */
    CompletableFuture<List<Map<String, Object>>> select(String table, String where, Object... args);
}

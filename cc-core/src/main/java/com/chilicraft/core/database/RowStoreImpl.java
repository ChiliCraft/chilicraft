package com.chilicraft.core.database;

import com.chilicraft.api.RowStore;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * RowStore 实现：受限的行级 CRUD 通道，供附属经 ChiliCraftAPI#rowStore 使用。
 *
 * <p>安全模型：</p>
 * <ul>
 *   <li>表白名单——仅预建附属表，核心私有表（cc_players、cc_soul_ledger）
 *       不在内，档案与灵魂必须走 ProfileManager/SoulService 的事务路径；</li>
 *   <li>列名白名单正则 + where 子句字符白名单（无引号、无分号、无注释符），
 *       值全部参数化绑定，杜绝附属拼接注入；</li>
 *   <li>update/delete 强制非空 where，防止误伤全表。</li>
 * </ul>
 *
 * <p>执行模型：全部 SQL 经 {@link DatabaseManager#supply} 提交到核心 DB
 * 单线程队列——与档案落库共用同一串行队列，天然无写写竞争；
 * 异常以 CompletionException 异常完成 future，交由调用方处理。</p>
 */
public final class RowStoreImpl implements RowStore {

    /** 附属可访问表白名单（核心私有表不在内） */
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "cc_quest_progress",
            "cc_death_records",
            "cc_soul_fragments",
            "cc_relics",
            "cc_achievements",
            "cc_skills",
            "cc_expedition_runs",
            "cc_auctions",
            "cc_boat_pairs",
            "cc_discovered_map",
            "cc_street_memories",
            "cc_mail_letters",
            "cc_park_records",
            "cc_souvenirs"
    );

    /** 合法列名：字母/下划线开头，后跟字母/数字/下划线 */
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 合法 where 片段：标识符、占位符、比较/逻辑运算符、括号、空白（无引号无分号） */
    private static final Pattern WHERE = Pattern.compile("[A-Za-z0-9_?=<>!(),\\s.+-]*");

    private final DatabaseManager database;
    private final Logger logger;

    public RowStoreImpl(DatabaseManager database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    // ---------------- 写路径 ----------------

    @Override
    public CompletableFuture<Integer> insert(String table, Map<String, Object> row) {
        List<String> cols = validated(table, row);
        List<Object> values = flatten(row);
        String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        return database.supply(() -> {
            try (Connection conn = database.openConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, values);
                int affected = ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1); // AUTOINCREMENT 表：返回新行 id
                    }
                }
                return affected; // 复合主键表：无生成键，返回受影响行数
            } catch (SQLException e) {
                throw failed("insert", table, e);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> upsert(String table, Map<String, Object> row, String... keyColumns) {
        List<String> cols = validated(table, row);
        List<Object> values = flatten(row);
        if (keyColumns == null || keyColumns.length == 0) {
            throw new IllegalArgumentException("upsert 必须声明主键列: " + table);
        }
        for (String key : keyColumns) {
            if (key == null || !COLUMN.matcher(key).matches() || !row.containsKey(key)) {
                throw new IllegalArgumentException("upsert 主键列非法或缺失: " + key + "（表 " + table + "）");
            }
        }
        String sql = "INSERT OR REPLACE INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        return database.supply(() -> {
            try (Connection conn = database.openConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, values);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw failed("upsert", table, e);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> update(String table, Map<String, Object> set, String where, Object... args) {
        List<String> cols = validated(table, set);
        String clause = requireWhere(table, where);
        List<String> assigns = new ArrayList<>(cols.size());
        for (String col : cols) {
            assigns.add(col + " = ?");
        }
        List<Object> values = flatten(set);
        Collections.addAll(values, args);
        String sql = "UPDATE " + table + " SET " + String.join(", ", assigns) + " WHERE " + clause;
        return database.supply(() -> {
            try (Connection conn = database.openConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, values);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw failed("update", table, e);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> delete(String table, String where, Object... args) {
        requireTable(table);
        String clause = requireWhere(table, where);
        String sql = "DELETE FROM " + table + " WHERE " + clause;
        return database.supply(() -> {
            try (Connection conn = database.openConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, List.of(args));
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw failed("delete", table, e);
            }
        });
    }

    // ---------------- 读路径 ----------------

    @Override
    public CompletableFuture<List<Map<String, Object>>> select(String table, String where, Object... args) {
        requireTable(table);
        String clause = sanitizeWhere(where); // select 允许空 where = 全表
        String sql = "SELECT * FROM " + table + (clause.isBlank() ? "" : " WHERE " + clause);
        return database.supply(() -> {
            try (Connection conn = database.openConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, List.of(args));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int count = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        // LinkedHashMap 保持列序稳定，便于调用方按序展示
                        Map<String, Object> r = new LinkedHashMap<>(count * 2);
                        for (int i = 1; i <= count; i++) {
                            r.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(r);
                    }
                    return rows;
                }
            } catch (SQLException e) {
                throw failed("select", table, e);
            }
        });
    }

    // ---------------- 校验与辅助 ----------------

    /** 表白名单 + 列名校验；返回保序列名 */
    private static List<String> validated(String table, Map<String, Object> row) {
        requireTable(table);
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("RowStore 行数据不能为空（表 " + table + "）");
        }
        List<String> cols = new ArrayList<>(row.size());
        for (String col : row.keySet()) {
            if (col == null || !COLUMN.matcher(col).matches()) {
                throw new IllegalArgumentException("RowStore 非法列名: " + col + "（表 " + table + "）");
            }
            cols.add(col);
        }
        return cols;
    }

    private static void requireTable(String table) {
        if (table == null || !ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException("RowStore 不允许访问表: " + table + "（不在附属表白名单内）");
        }
    }

    /** where 校验；update/delete 必须非空 */
    private static String requireWhere(String table, String where) {
        String clause = sanitizeWhere(where);
        if (clause.isBlank()) {
            throw new IllegalArgumentException("RowStore 操作表 " + table + " 必须给出非空 where 条件");
        }
        return clause;
    }

    /** 字符白名单过滤（无引号、无分号、无注释符），null 视为空串 */
    private static String sanitizeWhere(String where) {
        if (where == null) {
            return "";
        }
        if (!WHERE.matcher(where).matches()) {
            throw new IllegalArgumentException("RowStore 非法 where 子句: " + where);
        }
        return where.trim();
    }

    /** row 转保序值列表（与 validated 返回的列序一致） */
    private static List<Object> flatten(Map<String, Object> row) {
        return new ArrayList<>(row.values());
    }

    /** 参数绑定：UUID 序列化为字符串，其余原样 setObject（null 支持） */
    private static void bind(PreparedStatement ps, List<Object> values) throws SQLException {
        int i = 1;
        for (Object value : values) {
            ps.setObject(i++, value instanceof UUID uuid ? uuid.toString() : value);
        }
    }

    private IllegalStateException failed(String action, String table, SQLException cause) {
        logger.error("RowStore.{}({}) SQL 执行失败", action, table, cause);
        return new IllegalStateException("RowStore." + action + "(" + table + ") 执行失败", cause);
    }
}

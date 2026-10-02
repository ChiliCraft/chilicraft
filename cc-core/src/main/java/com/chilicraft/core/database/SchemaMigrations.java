package com.chilicraft.core.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 轻量迁移：基于 PRAGMA user_version 的版本号迁移。
 *
 * <p>规则：V1 全部 DDL 在单事务内执行，失败整体回滚；
 * 后续版本按 {@code if (version < N) applyN(st)} 向下追加，
 * 禁止修改已发布版本的 DDL。</p>
 *
 * <p>表口径以规格文档「数据模型」一节为准；home_world / world 列为
 * 规格授权的实现期补齐字段；cc_soul_ledger 为灵魂货币「统一事务＋日志」
 * 要求新增的台账表；V2 为规格 v1.1 方街 4 表（cc-street）。</p>
 */
final class SchemaMigrations {

    private SchemaMigrations() {
    }

    static void migrate(Connection conn) throws SQLException {
        int version = readVersion(conn);
        if (version >= 4) {
            return;
        }

        boolean oldAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (Statement st = conn.createStatement()) {
                if (version < 1) {
                    applyV1(st);
                }
                if (version < 2) {
                    applyV2(st);
                }
                if (version < 3) {
                    applyV3(st);
                }
                if (version < 4) {
                    applyV4(st);
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(oldAutoCommit);
        }
    }

    private static int readVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** V1：规格首期 11 张表 + 灵魂台账表 + 索引 */
    private static void applyV1(Statement st) throws SQLException {
        // ---- cc_players：核心玩家档案（本模块读写） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_players (
                player_id      TEXT PRIMARY KEY,
                mode           TEXT NOT NULL DEFAULT 'COZY',
                martial_realm  INTEGER NOT NULL DEFAULT 0,
                profession     TEXT NOT NULL DEFAULT '',
                soul           INTEGER NOT NULL DEFAULT 0,
                season_points  INTEGER NOT NULL DEFAULT 0,
                home_world     TEXT NOT NULL DEFAULT '',
                home_x         REAL NOT NULL DEFAULT 0,
                home_y         REAL NOT NULL DEFAULT 0,
                home_z         REAL NOT NULL DEFAULT 0,
                mode_switch_at INTEGER NOT NULL DEFAULT 0,
                created_at     INTEGER NOT NULL DEFAULT 0,
                updated_at     INTEGER NOT NULL DEFAULT 0
            )""");

        // ---- cc_quest_progress：任务书进度（cc-quest） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_quest_progress (
                player_id    TEXT NOT NULL,
                chapter_id   TEXT NOT NULL,
                quest_id     TEXT NOT NULL,
                state        TEXT NOT NULL DEFAULT 'ACTIVE',
                progress     INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER,
                PRIMARY KEY (player_id, chapter_id, quest_id)
            )""");

        // ---- cc_death_records：死亡记录（cc-soul） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_death_records (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                player_id TEXT NOT NULL,
                cause     TEXT NOT NULL DEFAULT '',
                world     TEXT NOT NULL DEFAULT '',
                x         REAL NOT NULL DEFAULT 0,
                y         REAL NOT NULL DEFAULT 0,
                z         REAL NOT NULL DEFAULT 0,
                died_at   INTEGER NOT NULL
            )""");

        // ---- cc_soul_fragments：灵魂碎片（cc-soul） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_soul_fragments (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_id   TEXT NOT NULL,
                world      TEXT NOT NULL DEFAULT '',
                x          REAL NOT NULL DEFAULT 0,
                y          REAL NOT NULL DEFAULT 0,
                z          REAL NOT NULL DEFAULT 0,
                amount     INTEGER NOT NULL DEFAULT 0,
                expires_at INTEGER NOT NULL
            )""");

        // ---- cc_relics：12 遗物（cc-soul） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_relics (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                relic_id     TEXT NOT NULL,
                owner_id     TEXT NOT NULL,
                durability   INTEGER NOT NULL DEFAULT 0,
                history_json TEXT NOT NULL DEFAULT '[]',
                state        TEXT NOT NULL DEFAULT 'ACTIVE'
            )""");

        // ---- cc_achievements：成就（各模块共用） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_achievements (
                player_id      TEXT NOT NULL,
                achievement_id TEXT NOT NULL,
                unlocked_at    INTEGER NOT NULL,
                PRIMARY KEY (player_id, achievement_id)
            )""");

        // ---- cc_skills：武学技能（cc-martial） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_skills (
                player_id TEXT NOT NULL,
                skill_id  TEXT NOT NULL,
                level     INTEGER NOT NULL DEFAULT 0,
                xp        INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player_id, skill_id)
            )""");

        // ---- cc_expedition_runs：远征记录（cc-adventure） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_expedition_runs (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                team_json     TEXT NOT NULL DEFAULT '[]',
                deepest_layer INTEGER NOT NULL DEFAULT 0,
                result        TEXT NOT NULL DEFAULT '',
                ended_at      INTEGER NOT NULL
            )""");

        // ---- cc_auctions：拍卖行（cc-economy） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_auctions (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_id TEXT NOT NULL,
                item_data BLOB,
                price     INTEGER NOT NULL DEFAULT 0,
                ends_at   INTEGER NOT NULL
            )""");

        // ---- cc_boat_pairs：同舟绑约（cc-economy） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_boat_pairs (
                pair_id  TEXT PRIMARY KEY,
                player_a TEXT NOT NULL,
                player_b TEXT NOT NULL,
                bound_at INTEGER NOT NULL
            )""");

        // ---- cc_discovered_map：地图探索（cc-adventure） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_discovered_map (
                player_id TEXT NOT NULL,
                chunk_x   INTEGER NOT NULL,
                chunk_z   INTEGER NOT NULL,
                flags     INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player_id, chunk_x, chunk_z)
            )""");

        // ---- cc_soul_ledger：灵魂流水台账（核心，逐笔可审计） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_soul_ledger (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                player_id     TEXT NOT NULL,
                delta         INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                reason        TEXT NOT NULL DEFAULT '',
                occurred_at   INTEGER NOT NULL
            )""");

        // ---- 索引 ----
        st.execute("CREATE INDEX IF NOT EXISTS idx_death_records_player ON cc_death_records(player_id, died_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_soul_fragments_owner ON cc_soul_fragments(owner_id, expires_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_relics_owner ON cc_relics(owner_id)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_achievements_player ON cc_achievements(player_id)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_skills_player ON cc_skills(player_id)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_auctions_ends ON cc_auctions(ends_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_boat_pairs_players ON cc_boat_pairs(player_a, player_b)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_soul_ledger_player ON cc_soul_ledger(player_id, occurred_at)");

        st.execute("PRAGMA user_version = 1");
    }

    /** V2：规格 v1.1 方街 4 表（cc-street），字段口径以规格「数据模型」一节为准 */
    /** V3：任务进度状态变更时间，兼容 V1/V2 已有数据库。 */
    private static void applyV3(Statement st) throws SQLException {
        st.execute("ALTER TABLE cc_quest_progress ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
        st.execute("PRAGMA user_version = 3");
    }

    /** V4：任务奖励幂等记录，兼容已经完成 V1/V2/V3 的数据库。 */
    private static void applyV4(Statement st) throws SQLException {
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_quest_rewards (
                reward_id  TEXT PRIMARY KEY,
                player_id  TEXT NOT NULL,
                chapter_id TEXT NOT NULL,
                quest_id   TEXT NOT NULL,
                souls      INTEGER NOT NULL DEFAULT 0,
                reason     TEXT NOT NULL DEFAULT '',
                claimed_at INTEGER NOT NULL
            )""");
        st.execute("CREATE INDEX IF NOT EXISTS idx_quest_rewards_player ON cc_quest_rewards(player_id, claimed_at)");
        st.execute("PRAGMA user_version = 4");
    }

    private static void applyV2(Statement st) throws SQLException {
        // ---- cc_street_memories：方街居委会留言（cc-street） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_street_memories (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                player_id  TEXT NOT NULL,
                content    TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""");

        // ---- cc_mail_letters：方街邮筒信件（cc-street） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_mail_letters (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                from_id    TEXT NOT NULL,
                to_id      TEXT NOT NULL,
                content    TEXT NOT NULL DEFAULT '',
                read       INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )""");

        // ---- cc_park_records：方街游乐园成绩（cc-street） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_park_records (
                player_id      TEXT NOT NULL,
                game_id        TEXT NOT NULL,
                best_score     INTEGER NOT NULL DEFAULT 0,
                clears         INTEGER NOT NULL DEFAULT 0,
                last_played_at INTEGER NOT NULL,
                PRIMARY KEY (player_id, game_id)
            )""");

        // ---- cc_souvenirs：方街纪念品（cc-street） ----
        st.execute("""
            CREATE TABLE IF NOT EXISTS cc_souvenirs (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                souvenir_id  TEXT NOT NULL,
                owner_id     TEXT NOT NULL,
                city_tag     TEXT NOT NULL DEFAULT '',
                history_json TEXT NOT NULL DEFAULT '[]',
                state        TEXT NOT NULL DEFAULT 'ACTIVE'
            )""");

        // ---- 索引 ----
        st.execute("CREATE INDEX IF NOT EXISTS idx_street_memories_player ON cc_street_memories(player_id, created_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_mail_letters_to ON cc_mail_letters(to_id, created_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_souvenirs_owner ON cc_souvenirs(owner_id)");

        st.execute("PRAGMA user_version = 2");
    }
}

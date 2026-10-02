package com.chilicraft.core.profile;

import com.chilicraft.api.GameMode;
import com.chilicraft.core.database.DatabaseManager;
import com.chilicraft.core.economy.LedgerEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 档案 DAO：纯 JDBC。
 * 线程契约：所有方法只在 DB 线程执行
 * （load 经 DatabaseManager#supply 提交、saveAll 经 DatabaseManager#write 提交）。
 */
public final class ProfileRepository {

    private static final String SELECT_BY_ID = """
            SELECT mode, martial_realm, profession, soul, season_points,
                   home_world, home_x, home_y, home_z,
                   mode_switch_at, created_at, updated_at
            FROM cc_players
            WHERE player_id = ?""";

    /** UPSERT：新档案插入，已有档案更新（created_at 保留原值） */
    private static final String UPSERT = """
            INSERT INTO cc_players (player_id, mode, martial_realm, profession, soul, season_points,
                                     home_world, home_x, home_y, home_z, mode_switch_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(player_id) DO UPDATE SET
                mode = excluded.mode,
                martial_realm = excluded.martial_realm,
                profession = excluded.profession,
                soul = excluded.soul,
                season_points = excluded.season_points,
                home_world = excluded.home_world,
                home_x = excluded.home_x,
                home_y = excluded.home_y,
                home_z = excluded.home_z,
                mode_switch_at = excluded.mode_switch_at,
                updated_at = excluded.updated_at""";

    private static final String INSERT_LEDGER = """
            INSERT INTO cc_soul_ledger (player_id, delta, balance_after, reason, occurred_at)
            VALUES (?,?,?,?,?)""";

    private static final String SELECT_QUEST_STATE = """
            SELECT state FROM cc_quest_progress
            WHERE player_id = ? AND chapter_id = ? AND quest_id = ?""";

    private static final String CLAIM_QUEST = """
            UPDATE cc_quest_progress
            SET state = 'CLAIMED', completed_at = ?, updated_at = ?
            WHERE player_id = ? AND chapter_id = ? AND quest_id = ? AND state = 'COMPLETED'""";

    private static final String INSERT_REWARD = """
            INSERT INTO cc_quest_rewards (reward_id, player_id, chapter_id, quest_id, souls, reason, claimed_at)
            VALUES (?,?,?,?,?,?,?)""";

    private final DatabaseManager db;

    public ProfileRepository(DatabaseManager db) {
        this.db = db;
    }

    /**
     * 查档（DB 线程）。
     * @return 档案快照；无记录（新玩家）返回 null
     */
    public ProfileSnapshot load(UUID playerId) throws SQLException {
        try (Connection conn = db.openConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                GameMode mode = GameMode.fromName(rs.getString("mode"));
                if (mode == null) {
                    // 兼容手工改库等脏数据，回退默认
                    mode = GameMode.COZY;
                }
                return new ProfileSnapshot(playerId, mode,
                        rs.getInt("martial_realm"),
                        rs.getString("profession"),
                        rs.getInt("soul"),
                        rs.getInt("season_points"),
                        rs.getString("home_world"),
                        rs.getDouble("home_x"),
                        rs.getDouble("home_y"),
                        rs.getDouble("home_z"),
                        rs.getLong("mode_switch_at"),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"));
            }
        }
    }

    /**
     * 批量保存脏档案 + 灵魂台账：单事务整体提交/回滚（DB 线程）。
     */
    public QuestRewardCommit claimQuestReward(
            UUID playerId,
            String rewardId,
            String chapterId,
            String questId,
            int souls,
            String reason) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection conn = db.openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM cc_quest_rewards WHERE reward_id = ?")) {
                    ps.setString(1, rewardId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            conn.rollback();
                            return QuestRewardCommit.alreadyClaimed();
                        }
                    }
                }

                String questState;
                try (PreparedStatement ps = conn.prepareStatement(SELECT_QUEST_STATE)) {
                    ps.setString(1, playerId.toString());
                    ps.setString(2, chapterId);
                    ps.setString(3, questId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return QuestRewardCommit.questNotCompleted();
                        }
                        questState = rs.getString(1);
                    }
                }
                if (!"COMPLETED".equals(questState)) {
                    conn.rollback();
                    return "CLAIMED".equals(questState)
                            ? QuestRewardCommit.alreadyClaimed()
                            : QuestRewardCommit.questNotCompleted();
                }

                int balanceAfter;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT soul FROM cc_players WHERE player_id = ?")) {
                    ps.setString(1, playerId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return QuestRewardCommit.playerNotFound();
                        }
                        balanceAfter = Math.addExact(rs.getInt(1), souls);
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE cc_players SET soul = ?, updated_at = ? WHERE player_id = ?")) {
                    ps.setInt(1, balanceAfter);
                    ps.setLong(2, now);
                    ps.setString(3, playerId.toString());
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return QuestRewardCommit.playerNotFound();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT_LEDGER)) {
                    ps.setString(1, playerId.toString());
                    ps.setInt(2, souls);
                    ps.setInt(3, balanceAfter);
                    ps.setString(4, reason == null ? "" : reason);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(CLAIM_QUEST)) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setString(3, playerId.toString());
                    ps.setString(4, chapterId);
                    ps.setString(5, questId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return QuestRewardCommit.conflict();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(INSERT_REWARD)) {
                    ps.setString(1, rewardId);
                    ps.setString(2, playerId.toString());
                    ps.setString(3, chapterId);
                    ps.setString(4, questId);
                    ps.setInt(5, souls);
                    ps.setString(6, reason == null ? "" : reason);
                    ps.setLong(7, now);
                    ps.executeUpdate();
                }
                conn.commit();
                return QuestRewardCommit.success(balanceAfter);
            } catch (SQLException | ArithmeticException exception) {
                conn.rollback();
                throw exception;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void saveAll(List<ProfileSnapshot> profiles, List<LedgerEntry> ledger) throws SQLException {
        try (Connection conn = db.openConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!profiles.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
                        for (ProfileSnapshot s : profiles) {
                            ps.setString(1, s.playerId().toString());
                            ps.setString(2, s.mode().name());
                            ps.setInt(3, s.martialRealm());
                            ps.setString(4, s.profession());
                            ps.setInt(5, s.soul());
                            ps.setInt(6, s.seasonPoints());
                            ps.setString(7, s.homeWorld());
                            ps.setDouble(8, s.homeX());
                            ps.setDouble(9, s.homeY());
                            ps.setDouble(10, s.homeZ());
                            ps.setLong(11, s.modeSwitchAt());
                            ps.setLong(12, s.createdAt());
                            ps.setLong(13, s.updatedAt());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (!ledger.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(INSERT_LEDGER)) {
                        for (LedgerEntry e : ledger) {
                            ps.setString(1, e.playerId().toString());
                            ps.setInt(2, e.delta());
                            ps.setInt(3, e.balanceAfter());
                            ps.setString(4, e.reason());
                            ps.setLong(5, e.occurredAt());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
}

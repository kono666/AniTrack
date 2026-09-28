package com.animetracker.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盯住 V3 迁移真的做了它承诺的两件事: 先清存量重复行, 再加唯一约束.
 *
 * <p>为什么这类断言不能靠「看一眼 SQL 觉得对」: 迁移脚本是**一次性**的,
 * 写错了不会有人再跑一遍第二遍来提醒你 —— 它要么在别人的库上炸掉,
 * 要么更糟: 静默地少加一条约束, 然后重复行照旧长出来, 而所有人以为已经修好了.
 * 所以这里用一个临时内存库把脚本真跑一遍, 从 V2 开始造脏数据, 再迁到 V3 看结果.
 *
 * <p>不起 Spring 上下文: 这里要验的是脚本, 不是应用. 直接调 Flyway 快得多,
 * 而且断言的失败信息只指向 SQL, 不会被一堆 bean 的报错淹掉.
 *
 * <p>只跑 H2 那一份. PostgreSQL 那份的语法差异(H2 支持 ADD CONSTRAINT IF NOT EXISTS,
 * PG 得用匿名块)在真实 PG 上的验证靠 CI 的 image job, 那里有真库可用.
 */
class UniqueConstraintMigrationTest {

    // ========== 工具 ==========

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v3-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    private static MigrateResult migrate(String url, String target) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static Timestamp ts(String localDateTime) {
        return Timestamp.valueOf(localDateTime);
    }

    private static void execute(String url, String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long count(String url, String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<String> strings(String url, String sql, Object... args) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** 表上的唯一约束名. 大小写按 H2 的 INFORMATION_SCHEMA 来(全大写) */
    private static List<String> uniqueConstraints(String url, String table) throws SQLException {
        return strings(url,
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = ? AND CONSTRAINT_TYPE = 'UNIQUE'", table);
    }

    private static long insertUser(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO \"user\" (username, password, role, status) VALUES (?, 'x', 'USER', 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "u" + UUID.randomUUID().toString().substring(0, 8));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("全新库迁到 V3: 三个唯一约束都建出来了, 而且重复插入真的会被数据库拒绝")
    void freshDatabaseGetsAllThreeConstraintsThatActuallyBite() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long userId = insertUser(url);

        assertThat(uniqueConstraints(url, "ANIME_TRACKING"))
                .contains("UK_ANIME_TRACKING_USER_SUBJECT");
        assertThat(uniqueConstraints(url, "REVIEW"))
                .contains("UK_REVIEW_USER_SUBJECT");
        assertThat(uniqueConstraints(url, "EPISODE_WATCHED"))
                .contains("UK_EPISODE_WATCHED_USER_ANIME_EPISODE");

        // 只断言「约束在表上」是不够的 —— 名字对、列对、但没生效的约束是存在的
        //(列写错、写成了普通索引……). 真插两条重复的才知道它咬不咬人.
        execute(url, "INSERT INTO anime_tracking (user_id, subject_id, status, progress) "
                + "VALUES (?, 100, 'watching', 1)", userId);
        assertThatThrownBy(() -> execute(url,
                "INSERT INTO anime_tracking (user_id, subject_id, status, progress) "
                        + "VALUES (?, 100, 'watched', 2)", userId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("UK_ANIME_TRACKING_USER_SUBJECT");

        execute(url, "INSERT INTO review (user_id, subject_id, rating, content) "
                + "VALUES (?, 200, 8, '还行')", userId);
        assertThatThrownBy(() -> execute(url,
                "INSERT INTO review (user_id, subject_id, rating, content) "
                        + "VALUES (?, 200, 9, '又一条')", userId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("UK_REVIEW_USER_SUBJECT");

        execute(url, "INSERT INTO episode_watched (user_id, anime_id, episode_num) "
                + "VALUES (?, 300, 1)", userId);
        assertThatThrownBy(() -> execute(url,
                "INSERT INTO episode_watched (user_id, anime_id, episode_num) "
                        + "VALUES (?, 300, 1)", userId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("UK_EPISODE_WATCHED_USER_ANIME_EPISODE");

        // 同一张表上换个 subject_id 仍然要能插 —— 约束别写宽了, 宽了就误伤正常数据
        execute(url, "INSERT INTO anime_tracking (user_id, subject_id, status, progress) "
                + "VALUES (?, 101, 'watching', 1)", userId);
        assertThat(count(url, "SELECT COUNT(*) FROM anime_tracking")).isEqualTo(2);
    }

    @Test
    @DisplayName("存量重复行: 追番与短评留最新的一条, 已看剧集留最早的一条")
    void existingDuplicatesAreCollapsedKeepingTheRightRow() throws Exception {
        String url = freshUrl();
        // 先停在 V2 —— 那时还没有唯一约束, 重复行插得进去, 正是线上老库的样子
        migrate(url, "2");
        long userId = insertUser(url);

        execute(url, "INSERT INTO anime_tracking (user_id, subject_id, status, progress, updated_at) "
                + "VALUES (?, 100, 'want_to_watch', 0, ?)", userId, ts("2026-01-01 10:00:00"));
        execute(url, "INSERT INTO anime_tracking (user_id, subject_id, status, progress, updated_at) "
                + "VALUES (?, 100, 'watching', 5, ?)", userId, ts("2026-02-01 10:00:00"));

        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, updated_at) "
                + "VALUES (?, 200, 5, '旧的那条', ?)", userId, ts("2026-01-01 10:00:00"));
        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, updated_at) "
                + "VALUES (?, 200, 9, '新的那条', ?)", userId, ts("2026-02-01 10:00:00"));

        // 已看剧集刻意反过来插: 早的那条后插, 这样「留最早」和「留最后插入的」会给出不同答案,
        // 用例才真的在验排序规则, 而不是在验插入顺序
        execute(url, "INSERT INTO episode_watched (user_id, anime_id, episode_num, watched_at) "
                + "VALUES (?, 300, 1, ?)", userId, ts("2026-02-01 10:00:00"));
        execute(url, "INSERT INTO episode_watched (user_id, anime_id, episode_num, watched_at) "
                + "VALUES (?, 300, 1, ?)", userId, ts("2026-01-01 10:00:00"));

        migrate(url, "latest");

        assertThat(count(url, "SELECT COUNT(*) FROM anime_tracking")).isEqualTo(1);
        assertThat(strings(url, "SELECT status FROM anime_tracking")).containsExactly("watching");

        assertThat(count(url, "SELECT COUNT(*) FROM review")).isEqualTo(1);
        assertThat(strings(url, "SELECT content FROM review")).containsExactly("新的那条");

        // watched_at 是「首次打勾时间」, 留最早那条才符合语义
        assertThat(count(url, "SELECT COUNT(*) FROM episode_watched")).isEqualTo(1);
        assertThat(count(url, "SELECT COUNT(*) FROM episode_watched WHERE watched_at = ?",
                ts("2026-01-01 10:00:00"))).isEqualTo(1);

        // 去重和加约束是两步, 只做前一步的话这次迁移就白跑了
        assertThat(uniqueConstraints(url, "ANIME_TRACKING"))
                .contains("UK_ANIME_TRACKING_USER_SUBJECT");
        assertThat(uniqueConstraints(url, "EPISODE_WATCHED"))
                .contains("UK_EPISODE_WATCHED_USER_ANIME_EPISODE");
    }

    @Test
    @DisplayName("已经迁到 V3 的库再启动一次: 一条都不重复执行, 也不报错")
    void rerunningOnAnUpToDateSchemaIsANoOp() {
        String url = freshUrl();
        migrate(url, "latest");

        MigrateResult again = migrate(url, "latest");

        assertThat(again.migrationsExecuted).isZero();
        assertThat(again.success).isTrue();
    }
}

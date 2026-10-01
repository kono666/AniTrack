package com.animetracker.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 盯住 V9(操作账本)这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>形状与 {@link ReviewReplyMigrationTest} 一致, 但这里要守的是**一个例外**:
 * {@code admin_action_log} 是全 schema 唯一一张**一个外键都不挂**的表。理由是账本记的是
 * 已经发生过的事实, 而事实里的东西将来可能不存在 —— 挂 CASCADE 会在目标被删时把处理
 * 记录一起抹掉, 不挂 CASCADE 就是 RESTRICT、会让一次正常删除撞上莫名其妙的约束错误。
 *
 * <p>这正是那种「下一个人会顺手补齐」的地方: 给一张孤零零的表加上外键看起来永远是对的,
 * 加完也不会让任何一条别的用例变红。所以这里要有人明确地断言它**没有**外键, 并且把
 * 它换来的那两件事(能记一个不存在的 id、目标被删后记录还在)各验一次 —— 那两件事
 * 看着像缺陷, 其实是这张表存在的意义。
 */
class AdminActionLogMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v9-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static String string(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> strings(String url, String sql) throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static int count(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 插一条账. 用 PreparedStatement 而不是拼串: detail 里那个用例专门放引号与换行 */
    private static void insertLog(String url, long actorId, long targetId, String detail)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_action_log "
                             + "(actor_id, actor_name, action, target_type, target_id, detail, created_at) "
                             + "VALUES (?, 'admin', 'USER_BAN', 'USER', ?, ?, TIMESTAMP '2030-01-01 00:00:00')")) {
            ps.setLong(1, actorId);
            ps.setLong(2, targetId);
            ps.setString(3, detail);
            ps.executeUpdate();
        }
    }

    /** 造一个用户 + 一条短评, 返回作者 id(评论 id 由 subject_id 反查) */
    private static long seedUserAndReview(String url) throws SQLException {
        String user = "u" + UUID.randomUUID().toString().substring(0, 12);
        execute(url, "INSERT INTO \"user\" (username, password, role, status) VALUES ('"
                + user + "', 'x', 'USER', 'ACTIVE')");
        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                + "SELECT id, 96000201, 8, '还行', TIMESTAMP '2030-01-01 00:00:00' "
                + "FROM \"user\" WHERE username = '" + user + "'");
        return Long.parseLong(string(url, "SELECT id FROM \"user\" WHERE username = '" + user + "'"));
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("V9 能应用, 且七列的 NOT NULL 约束与实体上的 nullable=false 一致")
    void migrationCreatesTheTable() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME = 'ADMIN_ACTION_LOG'")).isEqualTo(1);

        // 这几列的 NOT NULL 不是装饰: 实体上写的是 Long/String, 而"查得到行、读出来是 null"
        // 会在映射那一层炸成一个指不到库的报错. 唯一可以空的是 detail(将来若有动作没有
        // 值得记的细节)与 created_at(与 review 那张表同一个口径).
        for (String column : List.of("ACTOR_ID", "ACTOR_NAME", "ACTION", "TARGET_TYPE", "TARGET_ID")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'ADMIN_ACTION_LOG' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 必须是 NOT NULL", column)
                    .isEqualTo("NO");
        }
        for (String column : List.of("DETAIL", "CREATED_AT")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'ADMIN_ACTION_LOG' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 允许为空", column)
                    .isEqualTo("YES");
        }
    }

    /**
     * <b>这张表上一个外键都没有 —— 全 schema 唯一一张。</b>
     *
     * <p>先断一次主键在, 否则下面那条"没有外键"可能只是因为约束视图整个是空的
     * (查询写错表名、或者 H2 换了视图名), 而那种绿是最危险的一种.
     *
     * <p>⚠️ <b>约束类型串是 {@code 'FOREIGN KEY'} 而不是 {@code 'REFERENTIAL'}.</b>
     * 后者是 JDBC 的 {@code DatabaseMetaData.getImportedKeys} 那一侧的词, H2 的
     * {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} 里没有这个取值 —— 写成它就永远查到
     * 0 行, 于是这条用例<b>无条件通过</b>(写这条注释的当天实测过: 给表加上外键,
     * 那一版仍然全绿)。真正的外键在 {@code REFERENTIAL_CONSTRAINTS} 里(那条视图用它
     * 自己的 {@code DELETE_RULE}), 但"有几条"要在本视图上问。
     */
    @Test
    @DisplayName("admin_action_log 上一个外键都没有, 但有主键")
    void theLedgerHasNoForeignKeys() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'ADMIN_ACTION_LOG' AND CONSTRAINT_TYPE = 'PRIMARY KEY'"))
                .as("对照: 这条查询本身认得出约束, 所以下面那个 0 才有意义")
                .isEqualTo(1);
        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'ADMIN_ACTION_LOG' AND CONSTRAINT_TYPE = 'FOREIGN KEY'"))
                .as("账本刻意不挂外键: CASCADE 会连处理记录一起抹掉, RESTRICT 会让正常删除失败")
                .isZero();
    }

    /**
     * 不挂外键换来的第一件事: <b>能记一个不存在的对象</b>.
     *
     * <p>这是它明确接受的代价 —— 库这一层拦不住写进一个不存在的 id, 由「唯一的写入者
     * 是 AdminService, 且就在同一个事务里」兜着. 把它写下来是为了让下一个人看到
     * 这条性质时知道它是有意的, 而不是随手补一个外键.
     */
    @Test
    @DisplayName("能写进一个不存在的 actor_id / target_id —— 这是刻意的代价, 不是漏了约束")
    void theLedgerAcceptsIdsThatDoNotExist() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");

        insertLog(url, 999999L, 888888L, "禁用用户 某个已经不存在的账号");

        assertThat(count(url, "SELECT COUNT(*) FROM admin_action_log")).isEqualTo(1);
    }

    /**
     * 不挂外键换来的第二件事, 也是这张表存在的理由: <b>目标被删掉之后, 那次处理的记录还在</b>。
     *
     * <p>这里发的是一条裸 {@code DELETE FROM review}, 与 {@code AdminService.deleteAnyReview}
     * 打在库上的东西是同一句. 挂了 CASCADE 的话, 管理员刚删掉一条评论, 关于"谁删的、
     * 删的是哪条"的唯一记录会跟着一起消失 —— 账本最该留下的那一条, 恰好在最该留下它
     * 的时候被清掉.
     *
     * <p>顺带把 user 那一行也删掉: 作者注销之后, 这条记录仍然要读得懂.
     */
    @Test
    @DisplayName("目标被删掉之后账本行还在: 删评论、删用户都不动它")
    void theLedgerOutlivesItsTargets() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long authorId = seedUserAndReview(url);
        long reviewId = Long.parseLong(string(url, "SELECT id FROM review WHERE subject_id = 96000201"));
        insertLog(url, authorId, reviewId, "删除用户 u 在作品 96000201 下的评论：还行");

        execute(url, "DELETE FROM review WHERE id = " + reviewId);
        execute(url, "DELETE FROM \"user\" WHERE id = " + authorId);

        assertThat(count(url, "SELECT COUNT(*) FROM admin_action_log")).isEqualTo(1);
        assertThat(string(url, "SELECT detail FROM admin_action_log")).contains("96000201");
    }

    /**
     * {@code detail} 是无长度上限的 {@code VARCHAR}, 而且原样存取.
     *
     * <p>放引号与换行进去: detail 存的是**人类可读的一句话**, 而评论正文摘要里出现引号、
     * 甚至换行是常态(用户写多行评论). 长度也要真的长得起来 —— 摘要口径是 60 字, 而
     * 中文一个字在 UTF-8 里占三个字节, 短了会截断, 长了会白白截断.
     */
    @Test
    @DisplayName("detail 能原样存下引号、换行和 2000 字的正文")
    void detailRoundTripsQuotesNewlinesAndLongText() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        String detail = "删除用户 o'brien 在作品 1 下的评论：" + "甲".repeat(500)
                + "\n第二行 \"带引号\" 结尾";

        insertLog(url, 1L, 2L, detail);

        assertThat(string(url, "SELECT detail FROM admin_action_log")).isEqualTo(detail);
    }

    /**
     * {@code created_at} 上的那棵索引是这一页**唯一**的排序依据(时间倒序), 列序要对。
     *
     * <p>不建 {@code (actor_id)}: 这一页没有按人筛的入口, 而将来真要按人筛, 该建的也是
     * {@code (actor_id, created_at)} 而不是单独一列 —— 与 V4 末尾给 review 留的那条注记
     * 同一个道理.
     */
    @Test
    @DisplayName("V9 建出 idx_admin_action_log_created, 且只有 created_at 一列")
    void theTimeIndexIsThere() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'ADMIN_ACTION_LOG' "
                + "AND INDEX_NAME = 'IDX_ADMIN_ACTION_LOG_CREATED' ORDER BY ORDINAL_POSITION"))
                .containsExactly("CREATED_AT");
    }

    /**
     * V9 的两份脚本必须是同一批语句。
     *
     * <p>与 V4/V5/V6/V7/V8 同一条规矩(见 {@link HotPathIndexMigrationTest}):
     * 只改一份的后果是 H2 环境全绿、生产 PG 要到部署那一刻才发现少了一张表.
     * 这条是「PG 那边也应该没问题」这句话唯一能拿出来的证据。
     */
    @Test
    @DisplayName("V9 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V9 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V9__add_admin_action_log.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

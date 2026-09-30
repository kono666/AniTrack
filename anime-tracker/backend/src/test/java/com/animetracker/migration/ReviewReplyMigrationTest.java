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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盯住 V8(回复)这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>形状与 {@link ReviewLikeMigrationTest} 一致, 但这里多一件只有 V8 才有的东西:
 * <b>两级级联</b>。删一条短评要带走它下面的回复, 还要再带走那些回复身上的赞 ——
 * 三个表、两条 {@code ON DELETE CASCADE}, 全部发生在库里、不经过一行 Java。少配一条的
 * 表现不是"数据没删干净", 而是<b>删评论直接报外键错误</b>(剩下的那条约束是 RESTRICT),
 * 于是整条删除路径坏掉。这件事必须在库这一层验。
 *
 * <p>{@code reply_count} 那一列的理由与 V7 的 {@code like_count} 逐条相同
 * (NOT NULL DEFAULT 0, 否则存量行是 NULL 而实体那边是基本类型 long), 不再重复。
 */
class ReviewReplyMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v8-" + UUID.randomUUID().toString().replace("-", "")
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

    /** 造一个用户 + 一条短评, 返回评论 id。两处外键都要求目标行真的存在 */
    private static long seedReview(String url) throws SQLException {
        String user = "u" + UUID.randomUUID().toString().substring(0, 12);
        execute(url, "INSERT INTO \"user\" (username, password, role, status) VALUES ('"
                + user + "', 'x', 'USER', 'ACTIVE')");
        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                + "SELECT id, 96000201, 8, '还行', TIMESTAMP '2030-01-01 00:00:00' "
                + "FROM \"user\" WHERE username = '" + user + "'");
        return Long.parseLong(string(url, "SELECT id FROM review WHERE subject_id = 96000201"));
    }

    private static long userIdOf(String url) throws SQLException {
        return Long.parseLong(string(url, "SELECT MIN(id) FROM \"user\""));
    }

    /** 在给定评论下插一条回复, 返回回复 id */
    private static long seedReply(String url, long reviewId) throws SQLException {
        execute(url, "INSERT INTO review_reply (review_id, user_id, content, created_at) "
                + "VALUES (" + reviewId + ", " + userIdOf(url) + ", '说得好', "
                + "TIMESTAMP '2030-01-03 00:00:00')");
        return Long.parseLong(string(url, "SELECT MAX(id) FROM review_reply WHERE review_id = " + reviewId));
    }

    /** 给给定回复点一个赞 —— 用另一个用户, 免得撞 (reply_id, user_id) 唯一约束 */
    private static void seedReplyLike(String url, long replyId) throws SQLException {
        String user = "v" + UUID.randomUUID().toString().substring(0, 12);
        execute(url, "INSERT INTO \"user\" (username, password, role, status) VALUES ('"
                + user + "', 'x', 'USER', 'ACTIVE')");
        execute(url, "INSERT INTO reply_like (reply_id, user_id, created_at) "
                + "SELECT " + replyId + ", id, TIMESTAMP '2030-01-04 00:00:00' "
                + "FROM \"user\" WHERE username = '" + user + "'");
    }

    // ========== 用例 ==========

    /**
     * 迁移能跑到底, 而且 {@code review} 多出来的这一列**带 NOT NULL 与默认值 0**,
     * 两张新表也都在。
     */
    @Test
    @DisplayName("V8 能应用: review 多一列 reply_count(NOT NULL 默认 0), 并建出 review_reply / reply_like")
    void migrationAddsTheColumnAndTheTables() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW' AND COLUMN_NAME = 'REPLY_COUNT'"))
                .as("可空的话存量行就是 NULL, 而实体那边是基本类型 long —— 读一条抛一条")
                .isEqualTo("NO");
        assertThat(string(url, "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW' AND COLUMN_NAME = 'REPLY_COUNT'"))
                .as("没有默认值的话, 已有的那几万条评论会一起变 NULL")
                .isEqualTo("0");
        for (String table : List.of("REVIEW_REPLY", "REPLY_LIKE")) {
            assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME = '" + table + "'"))
                    .as("V8 应该建出 " + table).isEqualTo(1);
        }
    }

    /** 回复正文是 NOT NULL: 一条没有正文的回复没有任何含义, 库是这条规矩的最终防线 */
    @Test
    @DisplayName("review_reply.content 是 NOT NULL —— 空回复连库这一层都过不去")
    void replyContentIsNotNull() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long reviewId = seedReview(url);

        assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW_REPLY' AND COLUMN_NAME = 'CONTENT'"))
                .as("RequestDTO 上的 @NotBlank 只挡得住走接口的请求, 手工改库、历史遗留行绕得过去")
                .isEqualTo("NO");
        assertThatThrownBy(() -> execute(url, "INSERT INTO review_reply "
                + "(review_id, user_id, content, created_at) VALUES ("
                + reviewId + ", " + userIdOf(url) + ", NULL, TIMESTAMP '2030-01-03 00:00:00')"))
                .isInstanceOf(SQLException.class);
    }

    /**
     * 这次迁移是**加在存量数据上的**: 先在 V7 停住、插一条评论, 再补跑 V8。
     *
     * <p>断言的是那一行的 {@code reply_count} 是 0 而不是 NULL —— 开发库和线上都是
     * 带着几万条评论升上来的, 那才是它真实的经过。
     */
    @Test
    @DisplayName("存量评论(迁移之前就存在的)补跑 V8 之后 reply_count 是 0, 不是 NULL")
    void legacyRowsGetZero() throws Exception {
        String url = freshUrl();
        migrate(url, "7");
        long reviewId = seedReview(url);

        migrate(url, "latest");

        assertThat(string(url, "SELECT reply_count FROM review WHERE id = " + reviewId))
                .as("存量行必须拿到默认值; NULL 会在实体映射那一层炸")
                .isEqualTo("0");
    }

    /**
     * <b>两级级联的第一级</b>: 删一条短评, 它下面的回复跟着走。
     *
     * <p>这里发的是一条裸 {@code DELETE FROM review}, 与 {@code AdminService} 手写 SQL
     * 删除时打在库上的东西是同一句 —— 那条路径绕过了全部 Java 代码, 所以"删评论会不会
     * 撞外键"只能在库这一层回答。
     */
    @Test
    @DisplayName("删掉有回复的评论: 回复行被库级联带走, 不是撞外键失败")
    void deletingAReviewTakesItsRepliesWithIt() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long reviewId = seedReview(url);
        seedReply(url, reviewId);
        assertThat(count(url, "SELECT COUNT(*) FROM review_reply WHERE review_id = " + reviewId))
                .as("先确认回复真在, 否则下面那条断言是空过").isEqualTo(1);

        execute(url, "DELETE FROM review WHERE id = " + reviewId);

        assertThat(count(url, "SELECT COUNT(*) FROM review_reply WHERE review_id = " + reviewId))
                .as("没有 ON DELETE CASCADE 的话, 这一句 DELETE 直接报外键错误")
                .isZero();
    }

    /**
     * <b>两级级联的第二级, 也是这条用例真正的价值</b>: 删短评 → 回复被带走 →
     * 那些回复身上的赞也被带走。
     *
     * <p>第二级是**由库自己接着触发**的(H2 与 PG 都支持级联链), 但只要 {@code reply_like}
     * 那一侧配错 —— 比如漏了 {@code ON DELETE CASCADE}、或者有人把它写成 RESTRICT ——
     * 第一级就会在删到回复时被点赞行挡住, 于是<b>删一条被赞过回复的评论直接失败</b>。
     * 只验第一级(上一條用例)抓不到这个: 那条评论下没有赞行。
     */
    @Test
    @DisplayName("删掉「有回复、回复还被赞过」的评论: 回复与回复的赞一起消失(两级级联)")
    void deletingAReviewCascadesTwoLevels() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long reviewId = seedReview(url);
        long replyId = seedReply(url, reviewId);
        seedReplyLike(url, replyId);
        assertThat(count(url, "SELECT COUNT(*) FROM reply_like WHERE reply_id = " + replyId))
                .as("先确认赞行真在, 否则下面那条断言是空过").isEqualTo(1);

        execute(url, "DELETE FROM review WHERE id = " + reviewId);

        assertThat(count(url, "SELECT COUNT(*) FROM review_reply WHERE review_id = " + reviewId))
                .as("第一级: 回复随评论走").isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM reply_like WHERE reply_id = " + replyId))
                .as("第二级: 回复身上的赞随回复走。漏配的话删评论会报外键错误, 而不是留下一堆孤儿赞行")
                .isZero();
    }

    /**
     * 删一条**单独的回复**(楼还在), 它的赞同样跟着走。
     *
     * <p>与上一条是两条不同的路径: 这一条是 {@code ReviewReplyService.deleteReply} 打的
     * 东西, 它删的是中间那一层。少了 {@code fk_reply_like_reply} 的级联, 表现是
     * <b>删一条被赞过的回复报外键错误, 而删一条没被赞过的正常</b> —— 一个只在特定数据上
     * 才现形的 bug。
     */
    @Test
    @DisplayName("删一条被赞过的回复: 它的赞被带走, 同一楼里的其它回复不受影响")
    void deletingOneReplyTakesOnlyItsOwnLikes() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long reviewId = seedReview(url);
        long liked = seedReply(url, reviewId);
        seedReplyLike(url, liked);
        long untouched = seedReply(url, reviewId);

        execute(url, "DELETE FROM review_reply WHERE id = " + liked);

        assertThat(count(url, "SELECT COUNT(*) FROM reply_like WHERE reply_id = " + liked))
                .as("没有级联的话这一句 DELETE 直接报外键错误").isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM review_reply WHERE id = " + untouched))
                .as("删的是一条回复, 不是整楼").isEqualTo(1);
    }

    /**
     * 同一个人对同一条回复只能有一行赞 —— 唯一约束真的建上了。
     *
     * <p>「回复的赞是幂等的」整个押在它身上, 与 V7 的 {@code uk_review_like_review_user}
     * 是同一条理由: service 里没有"查一下再决定"的前置判断, 它直接插, 靠这个约束挡下
     * 重复点击、再由冲突分支把结果当成功返回。
     */
    @Test
    @DisplayName("同一人赞同一条回复两次: 撞唯一约束, 而不是插出两行")
    void theSameUserCannotLikeTheSameReplyTwice() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long replyId = seedReply(url, seedReview(url));
        long userId = userIdOf(url);
        String insert = "INSERT INTO reply_like (reply_id, user_id, created_at) "
                + "VALUES (" + replyId + ", " + userId + ", TIMESTAMP '2030-01-04 00:00:00')";
        execute(url, insert);

        assertThatThrownBy(() -> execute(url, insert))
                .as("这条约束就是幂等的全部依据; 少了它连点两下会插出两行")
                .isInstanceOf(SQLException.class);
        assertThat(count(url, "SELECT COUNT(*) FROM reply_like WHERE reply_id = " + replyId))
                .isEqualTo(1);
    }

    /**
     * 「这条评论下的回复」用的那条索引真的在。
     *
     * <p>它服务的是详情页最热的一段查询({@code WHERE review_id = ? ORDER BY created_at}),
     * 而 {@code review_reply} 是全站唯一一张会随互动量长大的表——没有它, 每次展开回复
     * 都是全表扫。名字与列序照 {@code idx_review_subject_created} 那条既有索引取。
     */
    @Test
    @DisplayName("V8 建出 idx_review_reply_review_created, 且列序是 (review_id, created_at)")
    void theReplyListIndexHasTheRightColumnsInTheRightOrder() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        // 断言列序而不只是"索引在不在": (created_at, review_id) 也是一棵合法、也能建出来的
        // 索引, 但对 WHERE review_id=? ORDER BY created_at 完全无用 —— 名字上看不出区别.
        assertThat(strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW_REPLY' "
                + "AND INDEX_NAME = 'IDX_REVIEW_REPLY_REVIEW_CREATED' ORDER BY ORDINAL_POSITION"))
                .as("先按 review_id 定位, 再按 created_at 取序 —— 反过来这棵索引就白建了")
                .containsExactly("REVIEW_ID", "CREATED_AT");
    }

    /**
     * V8 的两份脚本必须是同一批语句。
     *
     * <p>与 V4/V6/V7 同一条规矩(见 {@link HotPathIndexMigrationTest#bothDialectsRunTheSameStatements}):
     * 只改一份的后果是 H2 环境全绿、生产 PG 要到部署那一刻才发现少了一列或一张表。
     * 这条是「PG 那边也应该没问题」这句话唯一能拿出来的证据。
     */
    @Test
    @DisplayName("V8 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V8 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V8__add_review_replies.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

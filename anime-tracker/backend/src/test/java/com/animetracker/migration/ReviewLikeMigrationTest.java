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
 * 盯住 V7(点赞)这段脚本本身, 不起 Spring 上下文, 直接调 Flyway.
 *
 * <p>为什么迁移要单独有用例, 而不是「mvn test 跑得过就说明脚本没问题」: 迁移的
 * 失败方式是**运行期**的, 而且分两种, 两种都不在编译器和类型系统的视野里 ——
 *
 * <ul>
 *   <li>脚本在 H2 上能跑、在 PG 上不能(或反过来)。开发库和生产库是两套方言,
 *       本地全绿而生产启动失败, 是这整套成对脚本机制存在的唯一理由;</li>
 *   <li>脚本跑得过去但**列的属性不对** —— 少了 {@code NOT NULL DEFAULT 0} 的话,
 *       存量行的 like_count 会是 NULL, 而 {@code Review.likeCount} 是基本类型
 *       {@code long}: Hibernate 读到一个 NULL 会当场抛, 详情页整页打不开。这跟
 *       「脚本有没有成功执行」完全是两回事, 只有真去读一眼列定义才看得见。</li>
 * </ul>
 *
 * <p>还有一条只能在这里验的: <b>删评论时点赞行真的会被带走</b>。它靠的是库级
 * {@code ON DELETE CASCADE}(全仓第一处), 不经过任何 Java 代码 —— 没有这条用例的话,
 * 「级联有没有真的生效」就只能靠读脚本时说一句「应该没问题」, 而失效的表现是
 * 删除评论直接报外键错误。
 *
 * <p>PG 那份同样只验「语句逐字相同」, 本机没有可用的 PG(见
 * {@link HotPathIndexMigrationTest} 的类注释)。
 */
class ReviewLikeMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v7-" + UUID.randomUUID().toString().replace("-", "")
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
                + "SELECT id, 96000101, 8, '还行', TIMESTAMP '2030-01-01 00:00:00' "
                + "FROM \"user\" WHERE username = '" + user + "'");
        return Long.parseLong(string(url, "SELECT id FROM review WHERE subject_id = 96000101"));
    }

    private static long userIdOf(String url) throws SQLException {
        return Long.parseLong(string(url, "SELECT MIN(id) FROM \"user\""));
    }

    // ========== 用例 ==========

    /**
     * 迁移能跑到底, 而且加出来的这一列**带 NOT NULL 与默认值 0**。
     *
     * <p>只断言「迁移 success」是不够的: 少了 DEFAULT 0 的话迁移照样成功, 而存量行
     * 读出来是 NULL —— 映射到 {@code long likeCount} 上直接抛, 症状是「升级之后
     * 所有番剧的详情页都打不开」, 却和这次迁移看着毫无关系。
     */
    @Test
    @DisplayName("V7 能应用: review 多一列 like_count(NOT NULL 默认 0), 并建出 review_like")
    void migrationAddsTheColumnAndTheTable() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW' AND COLUMN_NAME = 'LIKE_COUNT'"))
                .as("可空的话存量行就是 NULL, 而实体那边是基本类型 long —— 读一条抛一条")
                .isEqualTo("NO");
        assertThat(string(url, "SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW' AND COLUMN_NAME = 'LIKE_COUNT'"))
                .as("没有默认值的话, 已有的那几万条评论会一起变 NULL")
                .isEqualTo("0");
        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME = 'REVIEW_LIKE'")).isEqualTo(1);
    }

    /**
     * 这次迁移是**加在存量数据上的**, 不是建在空库上。
     *
     * <p>先在 V6 停住、插一条评论, 再补跑 V7 —— 这才是开发库和线上真实的经过:
     * 库里已经有几万条评论了。用例要的是那一行的 like_count 是 0, 而不是那一条
     * 因为列刚加上而没有值。
     */
    @Test
    @DisplayName("存量评论(迁移之前就存在的)补跑 V7 之后 like_count 是 0, 不是 NULL")
    void legacyRowsGetZero() throws Exception {
        String url = freshUrl();
        migrate(url, "6");
        long reviewId = seedReview(url);

        migrate(url, "latest");

        assertThat(string(url, "SELECT like_count FROM review WHERE id = " + reviewId))
                .as("存量行必须拿到默认值; NULL 会在实体映射那一层炸")
                .isEqualTo("0");
    }

    /**
     * 删一条被赞过的评论, 点赞行跟着消失。
     *
     * <p>这是全仓第一处 {@code ON DELETE CASCADE}, 也是它唯一的存在理由: 删评论有
     * 三条路径(service / 管理端 / 测试里的裸 DELETE), 不级联的话每一条都会撞外键
     * 约束而失败 —— 也就是「删除评论」这个功能整体坏掉。三条路径里有一条绕过了
     * 全部 Java 代码, 所以这件事只能在库这一层验: 这里发的就是一条裸
     * {@code DELETE FROM review}, 与 {@code AdminService} 用手写 SQL 删除时
     * 打在库上的东西是同一句。
     *
     * <p>先断言赞行真的存在, 否则「删完是 0 行」在插入就没成功的库上一样绿。
     */
    @Test
    @DisplayName("删掉被赞过的评论: 点赞行被库级联带走, 不是撞外键失败")
    void deletingALikedReviewTakesTheLikesWithIt() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long reviewId = seedReview(url);
        execute(url, "INSERT INTO review_like (review_id, user_id, created_at) "
                + "VALUES (" + reviewId + ", " + userIdOf(url) + ", TIMESTAMP '2030-01-02 00:00:00')");
        assertThat(count(url, "SELECT COUNT(*) FROM review_like WHERE review_id = " + reviewId))
                .as("先确认赞行真在, 否则下面那条断言是空过").isEqualTo(1);

        execute(url, "DELETE FROM review WHERE id = " + reviewId);

        assertThat(count(url, "SELECT COUNT(*) FROM review_like WHERE review_id = " + reviewId))
                .as("没有 ON DELETE CASCADE 的话, 这一句 DELETE 直接报外键错误")
                .isZero();
    }

    /**
     * 同一个用户对同一条评论只能有一行赞 —— 唯一约束是真的建上了。
     *
     * <p>「点赞是幂等的」这件事整个押在它身上: {@code ReviewLikeService.like} 没有任何
     * 「查一下再决定」的前置判断, 它直接插, 靠这个约束把重复点击挡下来、再由冲突分支
     * 把结果当成功返回。约束不在的话, 连点两下就是**两行**, 而 like_count 会跟着加两次
     * ——界面上看是「赞数变成 2」, 没有任何报错。
     */
    @Test
    @DisplayName("同一人赞同一条评论两次: 撞唯一约束, 而不是插出两行")
    void theSameUserCannotLikeTheSameReviewTwice() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long reviewId = seedReview(url);
        long userId = userIdOf(url);
        String insert = "INSERT INTO review_like (review_id, user_id, created_at) "
                + "VALUES (" + reviewId + ", " + userId + ", TIMESTAMP '2030-01-02 00:00:00')";
        execute(url, insert);

        assertThatThrownBy(() -> execute(url, insert))
                .as("这条约束就是幂等的全部依据; 少了它连点两下会插出两行")
                .isInstanceOf(SQLException.class);
        assertThat(count(url, "SELECT COUNT(*) FROM review_like WHERE review_id = " + reviewId))
                .isEqualTo(1);
    }

    /**
     * V7 的两份脚本必须是同一批语句。
     *
     * <p>与 V4/V6 同一条规矩(见 {@link HotPathIndexMigrationTest#bothDialectsRunTheSameStatements}):
     * 只改一份的后果是 H2 环境全绿、生产 PG 要到部署那一刻才发现少了一列或一张表。
     * 这条是「PG 那边也应该没问题」这句话唯一能拿出来的证据。
     */
    @Test
    @DisplayName("V7 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V7 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V7__add_review_likes.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

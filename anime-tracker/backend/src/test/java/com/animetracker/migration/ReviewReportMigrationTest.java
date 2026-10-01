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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盯住 V13(评论举报)这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>形状与 {@link UserAvatarMigrationTest} / {@link NotificationMigrationTest} 一致。
 * 这一版值得单独守的有两处, 而且**两处都是"配错了不会报错、只会静默改变语义"**:
 *
 * <ul>
 *   <li><b>唯一约束落在哪两列、按什么顺序</b> —— 它同时是「一人对一条评论只能举报一次」
 *       的守门人, 和「这条评论被举报了几次」那次批量聚合的索引。顺序写反, 前者照样成立
 *       (约束的语义与列序无关), 只有后者悄悄变成全表扫;</li>
 *   <li><b>三条外键各自的级联方向</b> —— review 那条必须 CASCADE(删评论的三条路径都
 *       直接删行), 两条 user 那条必须不级联(V7/V8 的规矩)。三条写在同一个
 *       {@code CREATE TABLE} 里, 长得几乎一样, 判断却分两组。</li>
 * </ul>
 *
 * <p>这两条都是**行为版 + 声明版**各断一次: 声明版说清是哪一行配置, 行为版证明它真的
 * 在库上生效。只有声明版的话, "约束存在"和"约束以我们以为的方式存在"仍然是两件事。
 */
class ReviewReportMigrationTest {

    private static final String TABLE = "REVIEW_REPORT";

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v13-" + UUID.randomUUID().toString().replace("-", "")
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
        List<String> out = new ArrayList<>();
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

    // ========== 造数据 ==========

    private static long seedUser(String url, String prefix) throws SQLException {
        String user = prefix + UUID.randomUUID().toString().substring(0, 12);
        execute(url, "INSERT INTO \"user\" (username, password, role, status) VALUES ('"
                + user + "', 'x', 'USER', 'ACTIVE')");
        return Long.parseLong(string(url, "SELECT id FROM \"user\" WHERE username = '" + user + "'"));
    }

    /**
     * 灌一条评论. subject_id 逐条不同 —— 与 {@code ReviewReplyMigrationTest} 同一个理由:
     * 相同的话 "取到的那条对不对" 与 "取到的是不是刚好唯一那条" 就分不清了.
     */
    private static long seedReview(String url, long authorId, int subjectId) throws SQLException {
        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, created_at) VALUES ("
                + authorId + ", " + subjectId + ", 8, '还行', TIMESTAMP '2030-01-01 00:00:00')");
        return Long.parseLong(string(url, "SELECT id FROM review WHERE subject_id = " + subjectId));
    }

    /** 举报一行; status 走建表的 DEFAULT(不写这一列) */
    private static void seedReport(String url, long reviewId, long reporterId) throws SQLException {
        execute(url, "INSERT INTO review_report (review_id, reporter_id, reason) "
                + "VALUES (" + reviewId + ", " + reporterId + ", 'SPAM')");
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("V13 能应用, 九列的可空性与实体的 nullable 一致")
    void migrationCreatesTheTable() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME = '" + TABLE + "'"))
                .as("表建出来了")
                .isEqualTo(1);

        /* 四列 NOT NULL, 每一列在 ReviewReport.java 上都有 nullable = false 对应。 */
        for (String column : List.of("REVIEW_ID", "REPORTER_ID", "REASON", "STATUS")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = '" + TABLE + "' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 必须是 NOT NULL", column)
                    .isEqualTo("NO");
        }

        /* 其余五列刻意可空。detail 是选填的补充说明; handled_by/handled_at 未处理时都是
           null, 而且**永远一起**为 null(它们在 ReviewReportService.dismiss 的同一个分支里
           被赋值); created_at 与全仓其它表一样由 @PrePersist 写。 */
        for (String column : List.of("DETAIL", "HANDLED_BY", "HANDLED_AT", "CREATED_AT")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = '" + TABLE + "' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 允许为空", column)
                    .isEqualTo("YES");
        }
    }

    /**
     * <b>本迁移最该被守住的一条: 唯一约束的列顺序是 (review_id, reporter_id)。</b>
     *
     * <p>「一人对同一条评论只能举报一次」这句话与列序无关 —— 顺序反过来的约束**语义完全
     * 相同**, 三种该被拦/该被放行的情形一条都不会变。所以没有任何行为测试能发现它被写反。
     *
     * <p>会变的是**索引**: 这个唯一约束自带一棵 B 树, 管理端那一页的
     * 「这条评论被举报了几次」是 {@code WHERE review_id IN (...)} 的批量聚合, 吃的正是
     * 最左前缀 {@code review_id}。写成 {@code (reporter_id, review_id)} 之后那句话仍然正确,
     * 只是每次都扫全表 —— 而迁移脚本里那句「所以不另建索引」也会同时变成假话。
     *
     * <p>这就是本仓「唯一约束自带索引、不重复建」那条规矩的**承重面**: 它省掉一个索引的
     * 前提是最左前缀真的对得上。前提没了, 省下来的那个索引得补回去。
     */
    @Test
    @DisplayName("唯一约束 uk_review_report_review_reporter 落在 (review_id, reporter_id), 顺序不能反")
    void theUniqueConstraintIsOrderedReviewFirst() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        /* ⚠️ 索引名不是约束名 —— H2 给唯一约束建的那棵树上又拼了一个自己编的后缀,
           实测叫 UK_REVIEW_REPORT_REVIEW_REPORTER_INDEX_9(那一位是表号), 拼不出确切值,
           所以用 LIKE。这是 PRIMARY_KEY_<表号> 那个坑的**第二个实例**(见
           UserAvatarMigrationTest.thePrimaryKeyIsTheUserId), 而它比那个更容易踩:
           约束名在 TABLE_CONSTRAINTS 里是**逐字**的, 于是"约束名就是索引名"看起来
           完全合理 —— 写等号会查到 0 行, 而 containsExactly 在空集上照样能"通过"。 */
        assertThat(strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = '" + TABLE + "' "
                + "AND INDEX_NAME LIKE 'UK_REVIEW_REPORT_REVIEW_REPORTER%' "
                + "ORDER BY ORDINAL_POSITION"))
                .as("review_id 必须在最左 —— 管理端那次批量聚合吃的是这个前缀; "
                        + "反过来写约束语义一样, 只有这次聚合会静默退化成全表扫")
                .containsExactly("REVIEW_ID", "REPORTER_ID");
    }

    /**
     * 上一条的**行为版**: 唯一约束真的在拦人, 而且拦得不多不少。
     *
     * <p>三个方向一起断, 因为"配错了"有两种相反的坏法, 而单看一种看不出来:
     * 约束太松 → 一个人能刷一百条举报, 「待处理」这个数立刻失去意义(举报轰炸的经典入口);
     * 约束太紧(比如写成只按 {@code review_id}, 或者只按 {@code reporter_id})→
     * **一条评论只允许一个人举报**, 第二条正当的举报会被一个看不懂的 500 挡回去。
     */
    @Test
    @DisplayName("唯一约束的行为: 同一人重复举报被拦, 换人或换评论都放行")
    void onePersonCanOnlyReportTheSameReviewOnce() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long alice = seedUser(url, "a");
        long bob = seedUser(url, "b");
        long review1 = seedReview(url, alice, 96000301);
        long review2 = seedReview(url, alice, 96000302);

        seedReport(url, review1, bob);

        assertThatThrownBy(() -> seedReport(url, review1, bob))
                .as("同一个人对同一条评论举报第二次 —— 这正是要拦的那一下")
                .isInstanceOf(SQLException.class);

        seedReport(url, review1, alice);
        seedReport(url, review2, bob);

        assertThat(count(url, "SELECT COUNT(*) FROM review_report"))
                .as("只该拦掉一条: 换个举报人、换一条评论都必须放行")
                .isEqualTo(3);
    }

    /**
     * review 那条外键必须 CASCADE —— 而且这条是**功能性的**, 不是数据卫生。
     *
     * <p>删评论有三条路径({@code ReviewService.deleteReview}、
     * {@code AdminService.deleteAnyReview}、测试里的裸 DELETE), 它们都直接删
     * {@code review} 行。不级联的话三条会**一起坏掉**, 撞在一个与"删评论"毫无关系的
     * 约束错误上。所以这条断了两个方向: 举报跟着走、别人的举报不许跟着走。
     */
    @Test
    @DisplayName("删评论: 它的举报随之消失(不报外键错误), 别的评论的举报不受影响")
    void deletingAReviewTakesItsReportsWithIt() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long alice = seedUser(url, "a");
        long bob = seedUser(url, "b");
        long doomed = seedReview(url, alice, 96000301);
        long kept = seedReview(url, alice, 96000302);

        seedReport(url, doomed, bob);
        seedReport(url, kept, bob);
        seedReport(url, doomed, alice);
        assertThat(count(url, "SELECT COUNT(*) FROM review_report")).isEqualTo(3);

        execute(url, "DELETE FROM review WHERE id = " + doomed);

        assertThat(count(url, "SELECT COUNT(*) FROM review_report WHERE review_id = " + doomed))
                .as("没有 CASCADE 时这一句 DELETE 会直接报外键错误")
                .isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM review_report"))
                .as("另一条评论的举报不能跟着消失")
                .isEqualTo(1);
    }

    /**
     * 两条 user 外键**不级联** —— 与 review 那条判断相反, 而且是刻意的。
     *
     * <p>V7/V8 立过规矩:「{@code user_id} 一律不级联」。理由与
     * {@link UserAvatarMigrationTest#theUserForeignKeyCascades} 那条例外汇总成一句话:
     * 级联会**静默删掉子行而没有任何东西会报错**。举报带状态与处理人, 比一个计数器更
     * 禁不起静默消失。本仓今天没有删用户功能, 所以这条约束目前只是一句声明 —— 但它必须
     * 是**对的**那句, 否则将来做注销账号时才发现, 而那时已经有一批举报不在了。
     *
     * <p>三个外键写在同一个 {@code CREATE TABLE} 里、长得几乎一样, 所以这里连"哪一条
     * 是哪一条"一起断: {@code REVIEW_ID} 那条 CASCADE, 两条 user 那条都不 CASCADE。
     */
    @Test
    @DisplayName("两条 user 外键(reporter / handler)都不级联, 只有 review 那条级联")
    void theUserForeignKeysDoNotCascade() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        for (String fk : List.of("FK_REVIEW_REPORT_REPORTER", "FK_REVIEW_REPORT_HANDLER")) {
            assertThat(string(url, "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                    + "WHERE CONSTRAINT_NAME = '" + fk + "'"))
                    .as("%s 必须是 CASCADE 之外的东西 —— 举报带状态与处理人, "
                            + "静默消失比留下孤儿行糟得多", fk)
                    .isNotEqualTo("CASCADE");
        }
        assertThat(string(url, "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_NAME = 'FK_REVIEW_REPORT_REVIEW'"))
                .as("对照组: review 那条必须级联, 否则上面两条断言在「三条全不级联」时也会绿")
                .isEqualTo("CASCADE");
    }

    /**
     * {@code status} 的 {@code DEFAULT 'PENDING'} 真的在库上生效。
     *
     * <p>应用这条路径**不靠它** —— {@code ReviewReport.onCreate} 里有兜底(理由见那个
     * 方法的注释: Hibernate 对 null 字段会显式写 NULL, 而那一列是 NOT NULL, 光有
     * DEFAULT 是救不了的)。所以这条 DEFAULT 服务的不是 ORM, 而是**不走 ORM 的那些写入**:
     * 手工改库、将来的数据修复脚本、测试里的裸 INSERT。
     *
     * <p>只断"列上有 DEFAULT"是不够的 —— {@code INFORMATION_SCHEMA} 里写着 DEFAULT 而
     * 插入时报 NOT NULL 失败, 也是可能的; 这里直接插一行不写 status, 再读回来看。
     */
    @Test
    @DisplayName("status 的库级 DEFAULT 生效: 不写这一列插进去读回来是 PENDING")
    void statusFallsBackToPendingAtTheDatabaseLevel() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long alice = seedUser(url, "a");
        long bob = seedUser(url, "b");
        long review = seedReview(url, alice, 96000301);

        seedReport(url, review, bob);

        assertThat(string(url, "SELECT status FROM review_report WHERE review_id = " + review))
                .as("不走 ORM 的写入(手工改库 / 修复脚本 / 测试里的裸 INSERT)只有这一层兜底")
                .isEqualTo("PENDING");
    }

    /**
     * V13 的两份脚本必须是同一批语句。
     *
     * <p>与 V4~V12 同一条规矩(见 {@link HotPathIndexMigrationTest}): 只改一份的后果是
     * H2 环境全绿、生产 PG 要到部署那一刻才发现少了一张表。这条是「PG 那边也应该没问题」
     * 这句话唯一能拿出来的证据。
     */
    @Test
    @DisplayName("V13 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V13 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V13__add_review_reports.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

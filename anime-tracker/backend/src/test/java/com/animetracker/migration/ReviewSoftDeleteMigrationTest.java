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
 * 盯住 V14({@code review.deleted_at / deleted_by})这段脚本本身, 不起 Spring 上下文, 直接调
 * Flyway。
 *
 * <p>它要守的不是"列建出来了", 而是四件会被下一个人顺手改坏的性质:
 *
 * <ul>
 *   <li><b>两列都可空、且没有默认值</b> —— 存量评论一行都不用改就是"在架上"。给
 *       {@code NOT NULL DEFAULT now()} 之类的写法在这里有两个后果, 都是灾难性的: 加列本身
 *       会被库拒绝(没有 DEFAULT 的 NOT NULL 列), 或者更糟 —— 加成了, 而**全站评论在那一刻
 *       集体被移除**;</li>
 *   <li><b>外键是有名字的、而且不给 CASCADE</b> —— {@code deleted_by} 记的是"谁把它撤下来的"
 *       这个事实, 那个人销号之后这一列该继续指向一个不存在的 id, 而不是把整条评论一起带走。
 *       这是本表唯一一处与 V7/V8 的 {@code user_id} 规矩不同的判断, 所以两条都断言:
 *       元数据里的 {@code DELETE_RULE} 与**真的删一次那个用户**(后者才是决定性的);</li>
 *   <li><b>这一版没有加索引</b> —— {@code HotPathIndexMigrationTest} 那条"review 表上恰好只有
 *       一个 {@code IDX_} 索引"的硬断言就是为它立的, 这里不重复, 但别把它改成白名单;</li>
 *   <li><b>两方言逐字相同, 且每一步都能重跑</b> —— 第二句的 {@code IF NOT EXISTS} 是这版
 *       刻意选的写法(PostgreSQL 的 {@code ALTER TABLE ... ADD CONSTRAINT} 没有
 *       {@code IF NOT EXISTS}), 所以"重跑一次是空操作"这句话必须真的成立。</li>
 * </ul>
 */
class ReviewSoftDeleteMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v14-" + UUID.randomUUID().toString().replace("-", "")
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

    private static int integer(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * 那一列在 H2 元数据里的某一项.
     *
     * <p>表名这里是**大写** {@code REVIEW}, 而 {@code PasswordChangedAtMigrationTest} 里查
     * {@code "user"} 那张表用的是小写 —— 两个都对, 差别在迁移脚本里怎么写的: 不带引号的
     * {@code review} 会被库折成大写, 带引号的 {@code "user"} 原样保留小写.
     * ({@code ReviewLikeMigrationTest} 查的就是 {@code 'REVIEW'}.)
     */
    private static String column(String url, String name, String field) throws SQLException {
        return string(url, "SELECT " + field + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW' AND COLUMN_NAME = '" + name + "'");
    }

    /**
     * 造出来的这一小片数据: 一条评论的**作者**, 以及按了移除的那个**管理员**.
     *
     * <p>两个用户是刻意分开的, 不是一个 —— {@code deleted_by} 指的是后者. 合成一个人会让
     * 下面那条"删用户必须删不动"的断言**因为错误的原因通过**: 评论的 {@code user_id} 上本来
     * 就挂着 V7/V8 那句不级联的外键, 于是无论 {@code deleted_by} 是 RESTRICT 还是 CASCADE,
     * 删那个作者都会失败. 这是本文件里唯一一处非写不可的讲究.
     */
    private record Seeded(long authorId, long moderatorId) {
    }

    /** 两个用户 + 一条挂在作者名下的评论 */
    private static Seeded seed(String url) throws SQLException {
        execute(url, "INSERT INTO \"user\" (username, password, role, status) "
                + "VALUES ('legacy', 'x', 'USER', 'ACTIVE')");
        execute(url, "INSERT INTO \"user\" (username, password, role, status) "
                + "VALUES ('moderator', 'x', 'USER', 'ACTIVE')");
        execute(url, "INSERT INTO review (user_id, subject_id, rating) "
                + "SELECT id, 100, 8 FROM \"user\" WHERE username = 'legacy'");
        return new Seeded(
                Long.parseLong(string(url, "SELECT id FROM \"user\" WHERE username = 'legacy'")),
                Long.parseLong(string(url, "SELECT id FROM \"user\" WHERE username = 'moderator'")));
    }

    /** 把那条评论标记成"已被某人移除" */
    private static void markRemoved(String url, long moderatorId) throws SQLException {
        execute(url, "UPDATE review SET deleted_at = TIMESTAMP '2030-01-01 00:00:00', "
                + "deleted_by = " + moderatorId);
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("V14 能应用, review 表上多出两个可空、无默认值的列")
    void migrationAddsBothColumns() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        // 先确认两列真的在, 否则下面每一项都会拿到 null 而"通过"成一条绿
        assertThat(integer(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'REVIEW' AND COLUMN_NAME IN ('DELETED_AT', 'DELETED_BY')"))
                .as("对照: 查不到列的话, 下面几条都会以 null 的形式静默通过")
                .isEqualTo(2);

        assertThat(column(url, "DELETED_AT", "IS_NULLABLE"))
                .as("可空是关键: NULL 读作「在架上」, 存量行一行都不用改就是对的")
                .isEqualTo("YES");
        assertThat(column(url, "DELETED_BY", "IS_NULLABLE")).isEqualTo("YES");
        assertThat(column(url, "DELETED_AT", "COLUMN_DEFAULT"))
                .as("不能有默认值 —— 给了默认值等于宣布全站评论都在那一刻被移除")
                .isNull();
        assertThat(column(url, "DELETED_BY", "COLUMN_DEFAULT")).isNull();
        assertThat(column(url, "DELETED_AT", "DATETIME_PRECISION"))
                .as("微秒精度, 与 V10 的 password_changed_at 同一个写法")
                .isEqualTo("6");
    }

    /**
     * 外键: 名字是 {@code FK_REVIEW_DELETED_BY}, 指向 {@code "user"(id)}, 而且**不级联**。
     *
     * <p>为什么"不级联"要单独钉: 这是本表唯一一处与 V7/V8 对 {@code user_id} 的规矩**不同**
     * 的判断。那边的理由是"级联会静默删掉子行而计数器不跟着减"; 这里的理由是反过来的 ——
     * 这一列记的是**事实**, 那个人销号之后它应该继续指向一个不存在的 id。给 CASCADE 的话,
     * 删掉一个管理员会把他当年移除过的所有评论一起删掉, 而且是**真的删行**(这条路径绕过了
     * 软删), 账本里那些 REVIEW_DELETE 就全变成了指向虚空的记录。
     */
    @Test
    @DisplayName("外键有名、指向 user(id)、且不是 CASCADE: 删掉那个人不会带走这条评论")
    void theForeignKeyIsRestrictNotCascade() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        Seeded seeded = seed(url);

        assertThat(integer(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_NAME = 'FK_REVIEW_DELETED_BY'"))
                .as("外键名必须是显式的那个 —— 库自动生成的名字没人猜得到, 也没法在迁移里引用")
                .isEqualTo(1);
        assertThat(string(url, "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_NAME = 'FK_REVIEW_DELETED_BY'"))
                .isNotEqualToIgnoringCase("CASCADE");

        // 元数据之外的证据: 真的删一次那个管理员. 挂了 CASCADE 的话这句话会成功,
        // 并且把那条评论一起删掉 —— 而它现在应该是"删不动"(评论还引用着他).
        // 这里删的必须是 deleted_by 指向的那个人, 不能是作者: 作者那一侧本来就有一句
        // 不级联的外键, 拿作者来试的话, CASCADE 的实现也会"删不动", 于是这条断言变成
        // 一句永远为真的话(反向验证时真的踩到过这一点)
        markRemoved(url, seeded.moderatorId());
        assertThatThrownBy(() -> execute(url, "DELETE FROM \"user\" WHERE id = " + seeded.moderatorId()))
                .as("deleted_by 上挂了外键且不级联 —— 删掉那个管理员必须删不动")
                .isInstanceOf(SQLException.class);
        assertThat(integer(url, "SELECT COUNT(*) FROM review"))
                .as("那条评论必须还在")
                .isEqualTo(1);
    }

    /**
     * <b>存量评论不会被这一次迁移判成「已移除」。</b>
     *
     * <p>先迁到 V13、灌一条评论, 再迁到 V14, 那条评论读出来必须是 NULL。任何"给个默认值"
     * 的改动都会让它红 —— 而那样改的后果是**全站评论在迁移那一刻集体消失**, 且这件事与
     * 评论、管理端、任何一次点击都没有关系, 排查时会先怀疑读路径上的过滤条件写错了。
     */
    @Test
    @DisplayName("先于 V14 存在的评论, 迁完之后读作「在架上」")
    void existingRowsStayOnTheShelf() throws Exception {
        String url = freshUrl();
        migrate(url, "13");
        seed(url);

        migrate(url, "latest");

        assertThat(string(url, "SELECT deleted_at FROM review")).isNull();
        assertThat(string(url, "SELECT deleted_by FROM review")).isNull();
    }

    /**
     * 两句 {@code ALTER} 都是幂等的 —— 连跑两遍不报错, 而且**不会多出第二个外键**。
     *
     * <p>这一条是 V14 选"列级具名约束"而不是另起一句 {@code ADD CONSTRAINT} 的全部理由
     * (PostgreSQL 那侧没有 {@code IF NOT EXISTS}, V3 的注释记着这件事)。Flyway 正常情况下
     * 不会重复执行同一个版本, 但这句 {@code IF NOT EXISTS} 写在那里就是"允许重放"的意思,
     * 而那句话要么成立、要么就该去掉。第二遍必须连外键一起跳过 —— 只跳过列而重新加一次
     * 约束的话, 第二次会在库上抛"约束已存在"。
     */
    @Test
    @DisplayName("同两条 ALTER 跑第二遍不报错, 也不会多出第二个外键")
    void theAltersAreIdempotent() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        Seeded seeded = seed(url);
        markRemoved(url, seeded.moderatorId());

        execute(url, "ALTER TABLE review ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP(6)");
        execute(url, "ALTER TABLE review ADD COLUMN IF NOT EXISTS deleted_by BIGINT "
                + "CONSTRAINT fk_review_deleted_by REFERENCES \"user\" (id)");

        assertThat(integer(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_NAME = 'FK_REVIEW_DELETED_BY'"))
                .as("重放不该多挂一个同名外键")
                .isEqualTo(1);
        assertThat(string(url, "SELECT deleted_by FROM review"))
                .as("重放是空操作: 已经写进去的值一个字节都不该被动")
                .isEqualTo(String.valueOf(seeded.moderatorId()));
    }

    /**
     * V14 的两份脚本必须是同一批语句。
     *
     * <p>与 V4 起的每一条迁移同一条规矩。这一版尤其要紧: 它有一句**方言相关的写法**
     * (列级具名约束), 而 PostgreSQL 那侧本机根本没跑过 —— CI 的「镜像 + compose 冒烟」
     * 是本仓唯一能验真 PG 的地方。语句逐字相同至少保证"改了一处忘了另一处"会在这里红。
     */
    @Test
    @DisplayName("V14 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V14 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V14__add_review_soft_delete.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

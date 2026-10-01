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
 * 盯住 V15({@code user.last_login_at})这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>这一列存在的理由只有一个: 管理端用户列表答不了「这个号还活着吗」——
 * 一个注册完再没来过的账号和一个昨天刚登录过的账号, 在管理员眼里长得一模一样。
 * 于是这个文件要守的不是"列建出来了", 而是三件会被下一个人顺手改坏的性质:
 *
 * <ul>
 *   <li><b>它必须可空、且没有默认值</b> —— 存量用户读出来是 NULL, 读作"注册后从未登录过",
 *       一个都不用回填。给它一个 {@code DEFAULT CURRENT_TIMESTAMP} 的后果是**所有存量用户
 *       在一夜之间变成"刚刚登录过"**, 而那是一个不报错、不用例会红的静默错 ——
 *       管理员据此排查"这号还在用吗"时会得到全站都在用的假象;</li>
 *   <li><b>它不能有索引</b> —— 管理端按这一列排序时 ORDER BY 的第一键是**表达式**
 *       ({@code CASE WHEN u.lastLoginAt IS NULL THEN 1 ELSE 0 END}, 用来把空值恒定压到最后),
 *       普通 B-tree 对它无效。见 {@code HotPathIndexMigrationTest} 的白名单与 V15 脚本注释。</li>
 *   <li><b>两方言逐字相同</b> —— 与 V4~V14 同一条规矩。</li>
 * </ul>
 *
 * <p><b>表名口径的坑(照抄自 {@code ReviewSoftDeleteMigrationTest}):</b> 不带引号的表名在
 * H2 的 {@code INFORMATION_SCHEMA} 里是**大写**({@code TABLE_NAME = 'REVIEW'}), 而
 * {@code "user"} 是带引号建的, 名字原样保留**小写**。写成 {@code TABLE_NAME = 'USER'}
 * 会一条都查不到, 表现成"列不存在", 看起来像迁移没跑。
 */
class LastLoginAtMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v15-" + UUID.randomUUID().toString().replace("-", "")
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

    /** 那一列在 H2 元数据里的某一项. 表名是小写的 {@code user}(带引号建的) */
    private static String column(String url, String field) throws SQLException {
        return string(url, "SELECT " + field + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'user' AND COLUMN_NAME = 'LAST_LOGIN_AT'");
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("V15 能应用, user 表上多出一个可空、无默认值的 last_login_at")
    void migrationAddsTheColumn() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        // 先确认这一列真的在, 否则下面每一项都会拿到 null 而"通过"成一条绿
        assertThat(integer(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'user' AND COLUMN_NAME = 'LAST_LOGIN_AT'"))
                .as("对照: 查不到列的话, 下面几条都会以 null 的形式静默通过")
                .isEqualTo(1);
        assertThat(column(url, "IS_NULLABLE"))
                .as("可空是关键: NULL 读作「注册后从未登录过」")
                .isEqualTo("YES");
        assertThat(column(url, "COLUMN_DEFAULT"))
                .as("不能有默认值: 给了 CURRENT_TIMESTAMP 就等于宣布全站用户刚刚都登录过")
                .isNull();
    }

    /**
     * 精度必须是微秒, 与 {@code created_at} / {@code password_changed_at} 同一个口径。
     *
     * <p>这一列本身只用到秒, 但它与另外两列会一起出现在排序与管理端展示里; 全仓时间列
     * 统一成 6 位小数, 不在这里开特例 —— 开特例的成本是下一个人以为"这一列可以随便写"。
     */
    @Test
    @DisplayName("这一列是 TIMESTAMP(6), 与 rest of schema 的时间列同口径")
    void theColumnKeepsMicrosecondPrecision() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(column(url, "DATETIME_PRECISION")).isEqualTo("6");
    }

    /**
     * <b>存量用户不会被这一次迁移判成「刚刚登录过」。</b>
     *
     * <p>这条是这个文件里最该在的一条: 先迁到 V14、插一个用户, 再迁到 V15, 那个用户读出来
     * 必须是 NULL。任何"给个默认值免得空着难看"的改动都会让它红 —— 而那样改的后果不是
     * 界面难看, 是**后台那一列对全站都显示一个假的登录时间**, 且没有任何东西会报错。
     */
    @Test
    @DisplayName("先于 V15 存在的用户, 迁完之后 last_login_at 是 NULL")
    void existingRowsKeepNull() throws Exception {
        String url = freshUrl();
        migrate(url, "14");
        execute(url, "INSERT INTO \"user\" (username, password, role, status) "
                + "VALUES ('legacy', 'x', 'USER', 'ACTIVE')");

        migrate(url, "latest");

        assertThat(string(url, "SELECT last_login_at FROM \"user\" WHERE username = 'legacy'"))
                .as("存量用户必须读作「从未登录过」, 否则后台那一列整片是假数据")
                .isNull();
    }

    /**
     * 这一列写进去能原样读回来(微秒不丢)。
     *
     * <p>与 {@code password_changed_at} 那份同一个理由: 丢精度的表现是"值被静默截掉几位",
     * 而那种错不会让任何一条语义用例变红 —— 值本身是唯一看得见的证据。
     */
    @Test
    @DisplayName("写进去的时间(含微秒)能原样读回来")
    void valueRoundTripsWithMicroseconds() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        execute(url, "INSERT INTO \"user\" (username, password, role, status, last_login_at) "
                + "VALUES ('visitor', 'x', 'USER', 'ACTIVE', TIMESTAMP '2030-03-04 05:06:07.654321')");

        assertThat(string(url, "SELECT last_login_at FROM \"user\" WHERE username = 'visitor'"))
                .startsWith("2030-03-04 05:06:07.654321");
    }

    /**
     * {@code ADD COLUMN IF NOT EXISTS} 是幂等的 —— 同一句跑第二遍不报错。
     *
     * <p>Flyway 正常情况下不会重复执行同一个版本, 但这句话写在那里就是"允许重放"的意思,
     * 而那句话要么成立、要么就该去掉。
     */
    @Test
    @DisplayName("同一条 ALTER 跑第二遍不报错(IF NOT EXISTS 是当真的)")
    void theAlterIsIdempotent() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");

        execute(url, "ALTER TABLE \"user\" ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP(6)");

        assertThat(column(url, "IS_NULLABLE")).isEqualTo("YES");
    }

    /**
     * <b>这一列上不能有索引 —— 这是刻意的, 不要"顺手补一个"。</b>
     *
     * <p>管理端按最近登录排序时, ORDER BY 的第一键是表达式
     * ({@code CASE WHEN u.lastLoginAt IS NULL THEN 1 ELSE 0 END}), 普通 B-tree 对它无效;
     * 真要有用得建表达式索引, 而 H2 与 PG 的写法不同, 会打破"两方言语句逐字相同"。
     * 加在这里的成本是纯粹的写放大 —— 每一条 UPDATE 都要维护一棵一条计划都用不上的树。
     *
     * <p>H2 自动建的那几个不算: 主键是 {@code PRIMARY_KEY_<表 id>}, 唯一约束是
     * {@code <约束名>_INDEX_<表 id>}, 外键列各一棵 —— 名字都不以 {@code IDX_} 开头。
     * 所以筛 {@code index_name LIKE 'IDX%'}: 那个前缀是本仓"我们自己建的索引"的约定
     * (见 {@code HotPathIndexMigrationTest.ourIndexes})。
     */
    @Test
    @DisplayName("这一列上没有自建索引(B-tree 对表达式排序键无效, 加了只是写放大)")
    void theColumnIsDeliberatelyNotIndexed() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");

        assertThat(integer(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'user' AND COLUMN_NAME = 'LAST_LOGIN_AT' "
                + "AND INDEX_NAME LIKE 'IDX%'"))
                .as("加了索引就是白付写放大的成本 —— 排序第一键是表达式, 这条索引进不了计划")
                .isZero();
    }

    /**
     * V15 的两份脚本必须是同一批语句。
     *
     * <p>与 V4~V14 同一条规矩。这一版只有一句 ALTER, 看上去"不可能写歪" —— 而正因为只有
     * 一句, 引号、列名、类型任何一处不同都会是整份脚本的不同。
     */
    @Test
    @DisplayName("V15 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V15 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V15__add_user_last_login_at.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

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

/**
 * 盯住 V16({@code login_event})这张表的形状, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>这是全项目第一张<b>为统计而建</b>的表, 所以它要守的不是"表建出来了", 而是三条
 * 会被下一个人"顺手改好"的性质 —— 每一条改错的后果都不是报错, 是<b>静默地换了口径</b>:
 *
 * <ul>
 *   <li><b>{@code user_id} 必须可空、且没有外键。</b> 可空是因为"用户名根本不存在"那一类
 *       失败没有用户可挂, 而它恰恰是扫账号最典型的形状; 外键则会在删用户时把日志挡住
 *       或级联删掉 —— 日志的价值在于它记的是"发生过什么", 而账号是可以后消失的。
 *       (与 {@code admin_action_log} 同一条理由, 那份写在
 *       {@code AdminActionLogMigrationTest.theLedgerHasNoForeignKeys}.)</li>
 *   <li><b>{@code created_at} 必须 NOT NULL。</b> 没有时刻的事件不构成事件 —— 这一列是
 *       整张表存在的意义。而它是应用侧写的({@code LoginEvent.createdAt}), 一旦允许为空,
 *       一条没带时刻的行会让按天分组的曲线悄悄少一个点, 没人会注意到。</li>
 *   <li><b>只建 {@code (created_at)} 一条索引, 且不要给 {@code user_id} 加。</b>
 *       看板的三个查询与保留期清理全是"扫一段时间窗", 一条就够; 而今天没有任何查询是
 *       从"某个用户"出发的, {@code (user_id, created_at)} 现在只是纯粹的写放大
 *       (V4 立下的规矩: 加索引要有实测依据)。</li>
 * </ul>
 *
 * <p><b>表名口径:</b> {@code login_event} 是不带引号建的, 在 H2 的
 * {@code INFORMATION_SCHEMA} 里是<b>大写</b> {@code LOGIN_EVENT}。
 * (对照: {@code "user"} 带引号建, 是小写 —— 同一个仓库两种口径, 抄的时候别抄错。)
 */
class LoginEventMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v16-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    private static void migrate(String url) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .target(MigrationVersion.fromVersion("latest"))
                .load()
                .migrate();
    }

    private static void execute(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
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

    private static String string(String url, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 某一列在 H2 元数据里的某一项 */
    private static String column(String url, String name, String field) throws SQLException {
        return string(url, "SELECT " + field + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'LOGIN_EVENT' AND COLUMN_NAME = '" + name + "'");
    }

    // ========== 用例 ==========

    /**
     * 五列都在, 且空值口径与设计一致。
     *
     * <p>五个 {@code COLUMN_NAME} 查询每一个都先断一行, 最后再一条条断属性: 列名写错时
     * {@code column()} 回的是 null, 而 null 有可能<b>恰好</b>满足某一条断言(比如断言"没有默认值"),
     * 于是整条用例在一个不存在的列上通过。先确认它真的在, 后面的断言才有意义。
     */
    @Test
    @DisplayName("V16 建出 login_event: user_id 可空、created_at 与 success 都是 NOT NULL")
    void migrationCreatesTheTable() throws Exception {
        String url = freshUrl();

        migrate(url);

        for (String name : List.of("ID", "USER_ID", "CREATED_AT", "IP", "SUCCESS")) {
            assertThat(column(url, name, "COLUMN_NAME"))
                    .as("对照: %s 不在的话, 下面那几条会以 null 的形式静默通过", name)
                    .isEqualTo(name);
        }

        assertThat(column(url, "USER_ID", "IS_NULLABLE"))
                .as("可空是关键: 「用户名不存在」那一类失败没有用户可挂")
                .isEqualTo("YES");
        assertThat(column(url, "IP", "IS_NULLABLE"))
                .as("取不到地址就写 null, 塞占位值会让「有多少条没有 IP」这个信号消失")
                .isEqualTo("YES");
        assertThat(column(url, "CREATED_AT", "IS_NULLABLE"))
                .as("没有时刻的事件不构成事件")
                .isEqualTo("NO");
        assertThat(column(url, "SUCCESS", "IS_NULLABLE"))
                .as("一次尝试非成即败, 没有第三种")
                .isEqualTo("NO");
    }

    /** 时间列与 rest of schema 同口径: 6 位小数, 不在这里开特例 */
    @Test
    @DisplayName("created_at 是 TIMESTAMP(6)")
    void createdAtKeepsMicrosecondPrecision() throws Exception {
        String url = freshUrl();

        migrate(url);

        assertThat(column(url, "CREATED_AT", "DATETIME_PRECISION")).isEqualTo("6");
    }

    /**
     * <b>这张表上一个外键都没有, 但有主键。</b>
     *
     * <p>先断一次主键在, 否则下面那条"没有外键"可能只是因为约束视图整个是空的
     * (查询写错表名、或者 H2 换了视图名), 而那种绿是最危险的一种。
     *
     * <p>⚠️ 约束类型串是 {@code 'FOREIGN KEY'} 而<b>不是</b> {@code 'REFERENTIAL'} ——
     * 后者是 JDBC 那一侧的词, H2 的 {@code TABLE_CONSTRAINTS} 里没有这个取值, 写成它
     * 就永远查到 0 行、<b>无条件通过</b>。(这个坑 {@code AdminActionLogMigrationTest}
     * 上实测记录过, 这里照抄结论。)
     */
    @Test
    @DisplayName("login_event 上一个外键都没有, 但有主键")
    void theTableHasNoForeignKeys() throws Exception {
        String url = freshUrl();

        migrate(url);

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'LOGIN_EVENT' AND CONSTRAINT_TYPE = 'PRIMARY KEY'"))
                .as("对照: 这条查询本身认得出约束, 所以下面那个 0 才有意义")
                .isEqualTo(1);
        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'LOGIN_EVENT' AND CONSTRAINT_TYPE = 'FOREIGN KEY'"))
                .as("挂了外键, 删用户就会被一串日志挡住, 或者级联把日志删掉")
                .isZero();
    }

    /**
     * <b>能记一个没有用户的失败尝试</b> —— 不挂外键换来的那件事。
     *
     * <p>它顺带把"可空"从元数据上的一个字符串变成了一个真的写进去了的事实:
     * 元数据说 YES 而实际插不进去(比如某处偷偷加了 NOT NULL), 只有这条能发现。
     */
    @Test
    @DisplayName("user_id 为 NULL 的事件能写进去 —— 扫账号那一类失败就是这个形状")
    void recordsFailuresWithoutAUser() throws Exception {
        String url = freshUrl();
        migrate(url);

        execute(url, "INSERT INTO login_event (user_id, created_at, ip, success) "
                + "VALUES (NULL, TIMESTAMP '2030-01-02 03:04:05.123456', '203.0.113.7', FALSE)");

        assertThat(count(url, "SELECT COUNT(*) FROM login_event WHERE user_id IS NULL "
                + "AND success = FALSE")).isEqualTo(1);
    }

    /** 写进去的时刻(含微秒)能原样读回来 —— 微秒丢了的话曲线本身不受影响, 但值是唯一看得见的证据 */
    @Test
    @DisplayName("created_at 写进去能原样读回来(微秒不丢)")
    void createdAtRoundTrips() throws Exception {
        String url = freshUrl();
        migrate(url);

        execute(url, "INSERT INTO login_event (user_id, created_at, success) "
                + "VALUES (1, TIMESTAMP '2030-03-04 05:06:07.654321', TRUE)");

        assertThat(string(url, "SELECT created_at FROM login_event WHERE user_id = 1"))
                .startsWith("2030-03-04 05:06:07.654321");
    }

    /**
     * <b>自建索引只有一条, 且在 {@code created_at} 上。</b>
     *
     * <p>两个方向都要守, 而它们的失效方式不一样:
     * <ul>
     *   <li>少一条(删了)是让三个看板查询与保留期清理退化成全表扫;</li>
     *   <li>多一条(给 {@code user_id} 补一个"反正迟早用得上")是每条 INSERT 都多维护一棵
     *       一条计划都用不上的树 —— 而今天没有任何查询是从"某个用户"出发的。
     *       要加得先有实测依据(V4 的规矩), 那时它自然是最左前缀 {@code (user_id, created_at)}。</li>
     * </ul>
     *
     * <p>筛 {@code INDEX_NAME LIKE 'IDX%'}: 那个前缀是本仓"我们自己建的索引"的约定
     * (见 {@code HotPathIndexMigrationTest.ourIndexes})。H2 自动建的不算 —— 主键是
     * {@code PRIMARY_KEY_<表 id>}, 名字不以 {@code IDX_} 开头。
     */
    @Test
    @DisplayName("自建索引只有 (created_at) 一条, 且刻意没有 user_id 上的索引")
    void onlyTheTimeWindowIndexExists() throws Exception {
        String url = freshUrl();

        migrate(url);

        List<String> indexed = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                             + "WHERE TABLE_NAME = 'LOGIN_EVENT' AND INDEX_NAME LIKE 'IDX%'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                indexed.add(rs.getString(1));
            }
        }

        assertThat(indexed)
                .as("三个看板查询与保留期清理全是扫时间窗, (created_at) 一条全包; "
                        + "user_id 上再建一条就只是写放大")
                .containsExactly("CREATED_AT");
    }

    /**
     * 两句 {@code IF NOT EXISTS} 是当真的 —— 同一份脚本跑第二遍不报错。
     *
     * <p>Flyway 正常情况下不会重复执行同一个版本, 但 {@code IF NOT EXISTS} 写在那里就是
     * "允许重放"的意思, 而那句话要么成立、要么就该去掉。
     */
    @Test
    @DisplayName("CREATE TABLE / CREATE INDEX 跑第二遍不报错(IF NOT EXISTS 是当真的)")
    void theScriptIsIdempotent() throws Exception {
        String url = freshUrl();
        migrate(url);

        // 逐字重放 V16 的两句
        execute(url, "CREATE TABLE IF NOT EXISTS login_event ("
                + "id BIGINT GENERATED BY DEFAULT AS IDENTITY, user_id BIGINT,"
                + "created_at TIMESTAMP(6) NOT NULL, ip VARCHAR(45), success BOOLEAN NOT NULL,"
                + "CONSTRAINT pk_login_event PRIMARY KEY (id))");
        execute(url, "CREATE INDEX IF NOT EXISTS idx_login_event_created ON login_event (created_at)");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'LOGIN_EVENT' AND INDEX_NAME LIKE 'IDX%'"))
                .as("重放不该把索引变出第二条")
                .isEqualTo(1);
    }

    /**
     * V16 的两份脚本必须是同一批语句。
     *
     * <p>与 V4~V15 同一条规矩。这一版是"建表 + 建索引"两句, 看上去不可能写歪 ——
     * 而正因为短, 任何一处列名/类型/长度的不同都会是整份脚本的不同。
     */
    @Test
    @DisplayName("V16 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V16 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V16__add_login_event.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

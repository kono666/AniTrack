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
 * 盯住 V10({@code user.password_changed_at})这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>这一列的存在理由只有一个: 让「改密之前签发的 token」失效
 * ({@code JwtAuthFilter.isStaleAfterPasswordChange})。于是这个文件要守的不是"列建出来了",
 * 而是三件会被下一个人顺手改坏的性质:
 *
 * <ul>
 *   <li><b>它必须可空、且没有默认值</b> —— 存量用户读出来是 NULL, 读作"从未改过密码",
 *       过滤器一律放行。给一个 {@code NOT NULL DEFAULT now()} 的后果是**全站所有人
 *       立刻掉线**, 而且是在一次与登录毫无关系的迁移之后;</li>
 *   <li><b>精度必须是微秒({@code TIMESTAMP(6)})</b> —— 这一条看着无关紧要, 实际决定了
 *       一个真会发生的 bug 出不出现: 比较时要把这一列截到秒才能与 JWT 的 {@code iat} 对齐,
 *       而如果这一列本身只有秒精度, 那个截断就成了摆设、"同一秒内的新 token" 那条判断
 *       会随数据库的取整方向随机地错;</li>
 *   <li><b>两方言逐字相同</b> —— 与 V4/V5/V6/V7/V8/V9 同一条规矩。</li>
 * </ul>
 */
class PasswordChangedAtMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v10-" + UUID.randomUUID().toString().replace("-", "")
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

    /** 那一列在 H2 元数据里的某一项 */
    private static String column(String url, String field) throws SQLException {
        return string(url, "SELECT " + field + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'user' AND COLUMN_NAME = 'PASSWORD_CHANGED_AT'");
    }

    // ========== 用例 ==========

    @Test
    @DisplayName("V10 能应用, user 表上多出一个可空、无默认值的 password_changed_at")
    void migrationAddsTheColumn() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        // 先确认这一列真的在, 否则下面每一项都会拿到 null 而"通过"成一条绿
        assertThat(integer(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'user' AND COLUMN_NAME = 'PASSWORD_CHANGED_AT'"))
                .as("对照: 查不到列的话, 下面几条都会以 null 的形式静默通过")
                .isEqualTo(1);
        assertThat(column(url, "IS_NULLABLE"))
                .as("可空是关键: 给已有表加 NOT NULL 列没有默认值会被库直接拒绝, "
                        + "而给了默认值就等于宣布全站用户都在那一刻改过密码 —— 全员掉线")
                .isEqualTo("YES");
        assertThat(column(url, "COLUMN_DEFAULT"))
                .as("不能有默认值: NULL 才读作「从未改过密码」")
                .isNull();
    }

    /**
     * 精度必须是微秒。
     *
     * <p>这一条与 {@code JwtAuthFilter} 里那句 {@code truncatedTo(SECONDS)} 是一对: 那边
     * 的截断正是因为这边存的是微秒。若哪天有人把这一列改成 {@code TIMESTAMP}(秒), 截断
     * 仍然成立、测试仍然全绿, 但"同一秒内改密并立刻用新 token"那条判断就变成了一场
     * 掷硬币 —— 所以精度本身要被钉住。
     */
    @Test
    @DisplayName("这一列是 TIMESTAMP(6): 微秒精度, 不是秒")
    void theColumnKeepsMicrosecondPrecision() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(column(url, "DATETIME_PRECISION")).isEqualTo("6");
    }

    /**
     * <b>存量用户不会被这一次迁移判成「改过密码」。</b>
     *
     * <p>这条是这个文件里最该在的一条: 先迁到 V9、插一个用户, 再迁到 V10, 那个用户读出来
     * 必须是 NULL。任何"给个默认值免得空着难看"的改动都会让它红 —— 而那样改的后果不是
     * 界面难看, 是**所有人下次刷新页面时被登出**, 且这件事与登录、密码、任何用户操作
     * 都没有关系, 排查时会先怀疑 token 签发那一侧。
     */
    @Test
    @DisplayName("先于 V10 存在的用户, 迁完之后 password_changed_at 是 NULL")
    void existingRowsKeepNull() throws Exception {
        String url = freshUrl();
        migrate(url, "9");
        execute(url, "INSERT INTO \"user\" (username, password, role, status) "
                + "VALUES ('legacy', 'x', 'USER', 'ACTIVE')");

        migrate(url, "latest");

        assertThat(string(url, "SELECT password_changed_at FROM \"user\" WHERE username = 'legacy'"))
                .as("存量用户必须读作「从未改过密码」, 否则这次迁移会把全站踢下线")
                .isNull();
    }

    /**
     * 这一列写进去能原样读回来(微秒不丢)。
     *
     * <p>放在这里而不是只靠集成测试: 丢精度的表现是"比较时偶尔判错", 而那种错不会让
     * 任何一条用例变红 —— 值本身被截掉几位, 是唯一看得见的证据。
     */
    @Test
    @DisplayName("写进去的时间(含微秒)能原样读回来")
    void valueRoundTripsWithMicroseconds() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        execute(url, "INSERT INTO \"user\" (username, password, role, status, password_changed_at) "
                + "VALUES ('changer', 'x', 'USER', 'ACTIVE', TIMESTAMP '2030-01-02 03:04:05.123456')");

        assertThat(string(url, "SELECT password_changed_at FROM \"user\" WHERE username = 'changer'"))
                .startsWith("2030-01-02 03:04:05.123456");
    }

    /**
     * {@code ADD COLUMN IF NOT EXISTS} 是幂等的 —— 同一句跑第二遍不报错。
     *
     * <p>Flyway 正常情况下不会重复执行同一个版本, 但这句话写在那里就是"允许重放"的意思,
     * 而那句话要么成立、要么就该去掉。两边都试一次: 断言的是这条性质, 不是 Flyway 的行为。
     */
    @Test
    @DisplayName("同一条 ALTER 跑第二遍不报错(IF NOT EXISTS 是当真的)")
    void theAlterIsIdempotent() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");

        execute(url, "ALTER TABLE \"user\" ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP(6)");

        assertThat(column(url, "IS_NULLABLE")).isEqualTo("YES");
    }

    /**
     * V10 的两份脚本必须是同一批语句。
     *
     * <p>与 V4/V5/V6/V7/V8/V9 同一条规矩(见 {@link HotPathIndexMigrationTest})。这一版只有
     * 一句 ALTER, 看上去"不可能写歪" —— 而正因为只有一句, 库名、引号、列名任何一处不同
     * 都会是整份脚本的不同。
     */
    @Test
    @DisplayName("V10 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V10 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V10__add_password_changed_at.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

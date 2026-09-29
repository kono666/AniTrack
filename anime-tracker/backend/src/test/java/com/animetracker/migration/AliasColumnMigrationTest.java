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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V5 加的 aliases 列本身对不对.
 *
 * <p>为什么"实体和脚本对得上"这件事要单独验: {@code ddl-auto=validate} 只在**应用启动
 * 时**比对实体与表, 而那正是启动失败的地方 —— 也就是"开发库过、生产库起不来"这类问题
 * 最容易漏到部署那一刻的原因. 这里顺便钉住列宽 1000, 因为它与
 * {@code AnimeAliases.MAX_STORED_LENGTH} 和实体上的 {@code @Column(length = 1000)}
 * 是同一个数字的三份抄写, 任何一份改漏了都会以"启动失败"或"INSERT 失败"的形式暴露.
 *
 * <p>不起 Spring 上下文, 直接调 Flyway: 要验的是脚本, 断言的失败信息只该指向 SQL.
 * 只跑 H2 那一份 —— PG 那份的验证靠 CI 的 image job(本机没有可用的 PG), 但两份 V5
 * 的语句是逐字相同的, 这一点由 {@link #bothDialectsRunTheSameStatements()} 守住,
 * 否则「PG 那边也应该没问题」就只是一句希望.
 */
class AliasColumnMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的表状态 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v5-" + UUID.randomUUID().toString().replace("-", "")
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

    /** 这一列在不在, 以及它的可空性(用 INFORMATION_SCHEMA, 与 V4 那组用例同一套写法) */
    private static List<String> aliasColumnFacts(String url) throws SQLException {
        return strings(url, "SELECT COLUMN_NAME || '|' || CHARACTER_MAXIMUM_LENGTH || '|' || IS_NULLABLE "
                + "FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'ANIME' AND COLUMN_NAME = 'ALIASES'");
    }

    // ========== 列 ==========

    @Test
    @DisplayName("V5 之后 anime 上有一列 ALIASES: 宽度 1000, 可空")
    void addsNullableAliasColumn() throws Exception {
        String url = freshUrl();
        migrate(url, "5");

        assertThat(aliasColumnFacts(url))
                .as("V5 应当给 anime 加出 aliases VARCHAR(1000) 这一列")
                .containsExactly("ALIASES|1000|YES");
    }

    /**
     * 可空是**刻意的**, 不是漏写 NOT NULL.
     *
     * <p>NULL 表示"这一行的别名还没补过", 而详情页正是靠它决定要不要回源一次
     * (见 {@code AnimeService.getAnimeDetail}). 给上 NOT NULL 或默认值, 这个信息就没了,
     * 存量那 470 行也会在迁移时就卡住.
     */
    @Test
    @DisplayName("这一列没有 NOT NULL —— NULL 是「别名还没补过」这个业务状态本身")
    void columnIsDeliberatelyNullable() throws Exception {
        String url = freshUrl();
        migrate(url, "5");

        assertThat(aliasColumnFacts(url).get(0)).endsWith("|YES");
    }

    /**
     * 在一台"已经有这一列"的库上再跑一遍, 不能报错.
     *
     * <p>这不是假想: application.yml 里 {@code baseline-on-migrate=true} +
     * {@code baseline-version=1} 意味着"Flyway 之前就存在的老库"会从 V2 起逐个补跑,
     * V5 也会跑到它头上; 而那种库完全可能因为历史上手工建过这一列而已经存在.
     * 没有 {@code IF NOT EXISTS}, 那次启动会以 "Duplicate column name" 直接失败.
     */
    @Test
    @DisplayName("重复执行不报错（老库 baseline 之后补跑 V5 的情形）")
    void isIdempotent() throws Exception {
        String url = freshUrl();
        migrate(url, "5");

        assertThatCode(() -> execute(url, "ALTER TABLE anime ADD COLUMN IF NOT EXISTS aliases VARCHAR(1000)"))
                .as("脚本必须带 IF NOT EXISTS, 否则在已经手工建过这一列的库上启动会失败")
                .doesNotThrowAnyException();

        assertThat(aliasColumnFacts(url)).containsExactly("ALIASES|1000|YES");
    }

    // ========== 两份方言脚本 ==========

    /**
     * V5 的 h2 与 postgres 两份脚本, 去掉注释与格式差异后语句完全相同.
     *
     * <p>为什么这条对 V5 成立: {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} 是 H2 与
     * PostgreSQL 9.6+ 都支持的写法, 所以没有方言差异要绕(V3 那边不能这么省事, 是因为 PG 的
     * {@code ADD CONSTRAINT} 没有 IF NOT EXISTS). 而只改一份的后果是: 开发与测试环境(H2)
     * 全绿, 生产(PG)要到部署那一刻才发现少了一列 —— 正是这整套成对脚本机制想消灭的问题.
     */
    @Test
    @DisplayName("V5 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V5 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V5__add_anime_aliases.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

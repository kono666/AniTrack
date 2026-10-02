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
 * 盯住 V17({@code subject_extras} 加四张内容表)的形状, 不起 Spring 上下文, 直接调 Flyway.
 *
 * <p>这五张表要守的不是"表建出来了", 而是三条会被下一个人"顺手改好"的性质 ——
 * 每一条改错的后果都不是报错, 而是一个<b>看起来正常的坏状态</b>:
 *
 * <ul>
 *   <li><b>一个外键都不能有。</b> 三个 extras 请求与 {@code /subject/{id}} 是<b>并行</b>发的
 *       (前端 allSettled), 写这些表的时候 {@code anime} 那一行完全可能还不存在。挂上外键
 *       就是把一个正常的并发顺序变成一次插入失败, 而失败被 catch 住之后表现为
 *       "这一块永远是空的"。</li>
 *   <li><b>marker 三列必须可空。</b> NULL 是"没取过 / 没取全"这个状态<b>唯一</b>的表达。
 *       加一个 NOT NULL 之后, 写入方只能塞一个默认值 —— 而任何默认值都意味着
 *       "取过了", 于是第一次回源失败之后这块就再也不会重取。</li>
 *   <li><b>四张内容表都不许有唯一约束。</b> 实测同一个人会以不同职务反复出现(最多 5 次)、
 *       同一个声优在一个条目里最多重复 4 次 —— 唯一约束挡住的不是脏数据, 是<b>真实数据</b>,
 *       而代价是整批插入失败(见 {@code SubjectExtrasMapper} 的 cap 那条理由, 同一种失效)。</li>
 * </ul>
 *
 * <p><b>表名口径:</b> 这五张都是不带引号建的, 在 H2 的 {@code INFORMATION_SCHEMA} 里是
 * <b>大写</b>。(对照: {@code "user"} 带引号建, 是小写 —— 同一个仓库两种口径, 别抄错。)
 */
class SubjectExtrasMigrationTest {

    private static final List<String> TABLES = List.of(
            "SUBJECT_EXTRAS", "SUBJECT_CHARACTER", "SUBJECT_CHARACTER_ACTOR",
            "SUBJECT_STAFF", "SUBJECT_RELATION");

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v17-" + UUID.randomUUID().toString().replace("-", "")
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
    private static String column(String url, String table, String name, String field)
            throws SQLException {
        return string(url, "SELECT " + field + " FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + name + "'");
    }

    // ========== 用例 ==========

    /**
     * 五张表都建出来了, 而且都只有一个主键。
     *
     * <p>先断主键数, 后面那些"没有外键 / 没有唯一约束"的断言才有意义 —— 查询写错表名时
     * 它们会无条件通过, 而那种绿是最危险的一种。
     */
    @Test
    @DisplayName("V17 建出五张表, 每张各一个主键")
    void migrationCreatesTheFiveTables() throws Exception {
        String url = freshUrl();

        migrate(url);

        for (String table : TABLES) {
            assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_NAME = '" + table + "' AND CONSTRAINT_TYPE = 'PRIMARY KEY'"))
                    .as("对照: %s 的主键不在的话, 下面那几条会以 0 的形式静默通过", table)
                    .isEqualTo(1);
        }
    }

    /**
     * <b>五张表上一个外键都没有。</b>
     *
     * <p>⚠️ 约束类型串是 {@code 'FOREIGN KEY'} 而<b>不是</b> {@code 'REFERENTIAL'} ——
     * 后者是 JDBC 那一侧的词, H2 的 {@code TABLE_CONSTRAINTS} 里没有这个取值,
     * 写成它就永远查到 0 行、<b>无条件通过</b>。
     */
    @Test
    @DisplayName("五张表上一个外键都没有 —— 三个 extras 请求与详情是并行发的")
    void theTablesHaveNoForeignKeys() throws Exception {
        String url = freshUrl();

        migrate(url);

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME IN ('SUBJECT_EXTRAS','SUBJECT_CHARACTER',"
                + "'SUBJECT_CHARACTER_ACTOR','SUBJECT_STAFF','SUBJECT_RELATION') "
                + "AND CONSTRAINT_TYPE = 'FOREIGN KEY'"))
                .as("挂了外键, 写附属数据就会被 anime 那一行的存在与否挡住")
                .isZero();
    }

    /**
     * <b>挂不了外键换来的那件事, 做成一个真的写进去了的事实。</b>
     *
     * <p>{@code anime} 表里根本没有 999999 这个条目, 而这四张内容表照样写得进去 ——
     * 元数据说"没有外键"和实际插得进去是两件事, 只有这条能同时证明。
     */
    @Test
    @DisplayName("条目还不存在时也能写附属数据 —— 详情与三个 extras 是并行请求")
    void rowsCanBeWrittenBeforeTheAnimeExists() throws Exception {
        String url = freshUrl();
        migrate(url);

        execute(url, "INSERT INTO subject_character "
                + "(subject_id, character_id, name, relation, image, sort_order) "
                + "VALUES (999999, 12345, '角色', '主角', 'https://lain.bgm.tv/pic/crt/g/1.jpg', 0)");
        execute(url, "INSERT INTO subject_extras (subject_id, characters_fetched_at) "
                + "VALUES (999999, TIMESTAMP '2030-01-02 03:04:05.123456')");

        assertThat(count(url, "SELECT COUNT(*) FROM subject_character WHERE subject_id = 999999"))
                .isEqualTo(1);
    }

    /**
     * <b>marker 三列必须可空</b> —— NULL 是"没取过 / 没取全"唯一的表达。
     *
     * <p>五个 {@code COLUMN_NAME} 查询每一个都先断一行: 列名写错时 {@code column()}
     * 回的是 null, 而 null 有可能<b>恰好</b>满足某一条断言, 于是整条用例在一个不存在的
     * 列上通过。
     */
    @Test
    @DisplayName("subject_extras: 三列 marker 都可空, 主键是 subject_id")
    void markersAreNullable() throws Exception {
        String url = freshUrl();

        migrate(url);

        for (String name : List.of("SUBJECT_ID", "CHARACTERS_FETCHED_AT",
                "STAFF_FETCHED_AT", "RELATIONS_FETCHED_AT")) {
            assertThat(column(url, "SUBJECT_EXTRAS", name, "COLUMN_NAME"))
                    .as("对照: %s 不在的话, 下面那几条会以 null 的形式静默通过", name)
                    .isEqualTo(name);
        }

        for (String name : List.of("CHARACTERS_FETCHED_AT", "STAFF_FETCHED_AT",
                "RELATIONS_FETCHED_AT")) {
            assertThat(column(url, "SUBJECT_EXTRAS", name, "IS_NULLABLE"))
                    .as("%s 可空是关键: NULL = 没取过/没取全, 一旦 NOT NULL, "
                            + "第一次回源失败之后这块就再也不会重取", name)
                    .isEqualTo("YES");
        }
    }

    /** 时间列与 rest of schema 同口径: 6 位小数, 不在这里开特例 */
    @Test
    @DisplayName("marker 是 TIMESTAMP(6)")
    void markersKeepMicrosecondPrecision() throws Exception {
        String url = freshUrl();

        migrate(url);

        assertThat(column(url, "SUBJECT_EXTRAS", "CHARACTERS_FETCHED_AT", "DATETIME_PRECISION"))
                .isEqualTo("6");
    }

    /**
     * 四张内容表的关键列都是 NOT NULL。
     *
     * <p>{@code sort_order} 也在其中: 它是"上游给的顺序"唯一的载体(落库是先删后插,
     * 物理顺序不再等于上游顺序), 允许为空就等于允许这块内容每次刷新的排列都不一样。
     */
    @Test
    @DisplayName("内容表: subject_id / name / sort_order 都是 NOT NULL")
    void contentColumnsAreNotNull() throws Exception {
        String url = freshUrl();

        migrate(url);

        for (String table : List.of("SUBJECT_CHARACTER", "SUBJECT_CHARACTER_ACTOR",
                "SUBJECT_STAFF", "SUBJECT_RELATION")) {
            for (String name : List.of("SUBJECT_ID", "SORT_ORDER")) {
                assertThat(column(url, table, name, "IS_NULLABLE"))
                        .as("%s.%s", table, name)
                        .isEqualTo("NO");
            }
        }
        for (String table : List.of("SUBJECT_CHARACTER", "SUBJECT_CHARACTER_ACTOR",
                "SUBJECT_STAFF")) {
            assertThat(column(url, table, "NAME", "IS_NULLABLE"))
                    .as("%s.NAME", table)
                    .isEqualTo("NO");
        }
    }

    /**
     * <b>四张内容表上一个唯一约束都没有。</b>
     *
     * <p>这不是"暂时没加", 是加不了: 实测同一个人会以不同职务反复出现
     * ({@code /v0/subjects/8/persons} 里 person 419 出现 5 次),
     * 同一个声优在一个条目里最多重复 4 次。写成用例是因为"给这两列加个唯一索引吧"
     * 是下一个人很自然会做的改动, 而它的后果是<b>整批插入失败</b> ——
     * 那块内容从此永远空着, 日志里只有一条语法正确的约束冲突。
     */
    @Test
    @DisplayName("四张内容表上一个唯一约束都没有 —— 上游的数据本身就是有重复的")
    void noUniqueConstraintsOnContentTables() throws Exception {
        String url = freshUrl();

        migrate(url);

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME IN ('SUBJECT_CHARACTER','SUBJECT_CHARACTER_ACTOR',"
                + "'SUBJECT_STAFF','SUBJECT_RELATION') "
                + "AND CONSTRAINT_TYPE = 'UNIQUE'"))
                .as("实测同一个人会以不同职务反复出现、同一个声优最多重复 4 次 —— "
                        + "唯一约束挡住的是真实数据, 代价是整批插入失败")
                .isZero();
    }

    /** 重复的行确实写得进去 —— 把上一条从元数据上的一个字符串变成一个真发生的事实 */
    @Test
    @DisplayName("同一个声优重复出现两次能写进去(实测上游就是这么给的)")
    void duplicatedActorsAreStorable() throws Exception {
        String url = freshUrl();
        migrate(url);

        execute(url, "INSERT INTO subject_character_actor "
                + "(subject_id, character_id, actor_id, name, image, sort_order) VALUES "
                + "(8, 100, 3890, '声优', NULL, 0), (8, 101, 3890, '声优', NULL, 1)");

        assertThat(count(url, "SELECT COUNT(*) FROM subject_character_actor "
                + "WHERE subject_id = 8 AND actor_id = 3890")).isEqualTo(2);
    }

    /**
     * <b>四张内容表各有一条自建索引, 都在 {@code subject_id} 上; subject_extras 上没有。</b>
     *
     * <p>读路径全是"取这个条目的一块", 一条就够。{@code subject_extras} 的主键就是
     * {@code subject_id}, 再建一条是纯粹的写放大 —— 所以那边断言"一条都没有"。
     */
    @Test
    @DisplayName("四张内容表各只有一条 (subject_id) 索引, 账本表一条都不建")
    void onlyTheSubjectIndexExists() throws Exception {
        String url = freshUrl();

        migrate(url);

        for (String table : List.of("SUBJECT_CHARACTER", "SUBJECT_CHARACTER_ACTOR",
                "SUBJECT_STAFF", "SUBJECT_RELATION")) {
            List<String> indexed = new ArrayList<>();
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                                 + "WHERE TABLE_NAME = '" + table + "' AND INDEX_NAME LIKE 'IDX%'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    indexed.add(rs.getString(1));
                }
            }
            assertThat(indexed).as("%s 的自建索引", table).containsExactly("SUBJECT_ID");
        }

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'SUBJECT_EXTRAS' AND INDEX_NAME LIKE 'IDX%'"))
                .as("主键就是 subject_id, 再建一条索引只是写放大")
                .isZero();
    }

    /** 十句 DDL 都是 {@code IF NOT EXISTS} —— 同一份脚本跑第二遍不报错 */
    @Test
    @DisplayName("CREATE TABLE / CREATE INDEX 跑第二遍不报错(IF NOT EXISTS 是当真的)")
    void theScriptIsIdempotent() throws Exception {
        String url = freshUrl();
        migrate(url);

        // 逐字重放 V17 的五句 DDL(建索引那五句见下)
        execute(url, "CREATE TABLE IF NOT EXISTS subject_extras ("
                + "subject_id INTEGER, characters_fetched_at TIMESTAMP(6),"
                + "staff_fetched_at TIMESTAMP(6), relations_fetched_at TIMESTAMP(6),"
                + "CONSTRAINT pk_subject_extras PRIMARY KEY (subject_id))");
        execute(url, "CREATE INDEX IF NOT EXISTS idx_subject_character_subject "
                + "ON subject_character (subject_id)");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'SUBJECT_CHARACTER' AND INDEX_NAME LIKE 'IDX%'"))
                .as("重放不该把索引变出第二条")
                .isEqualTo(1);
    }

    /**
     * V17 的两份脚本必须是同一批语句。
     *
     * <p>这一版有十句 DDL、五张表、二十多个列, 是本仓最长的一份脚本 —— 只改一份的话
     * H2 环境全绿, 而生产要到部署时才发现。
     */
    @Test
    @DisplayName("V17 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V17 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V17__add_subject_extras.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

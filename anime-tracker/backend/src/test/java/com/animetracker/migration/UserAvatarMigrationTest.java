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
 * 盯住 V12(头像)这段脚本本身, 不起 Spring 上下文, 直接调 Flyway。
 *
 * <p>形状与 {@link NotificationMigrationTest} 一致, 但它守的是本迁移**唯一一处
 * 需要实测才能定的东西**: 那一列写成 {@code BYTEA}。
 *
 * <p>为什么这一条要有测试而不是写在注释里就够了: 三个候选写法在两个库上落成三种不同的
 * 类型 ——
 *
 * <ul>
 *   <li>{@code BYTEA} → H2 报 {@code BINARY VARYING}(JDBC VARBINARY), 正是 Hibernate
 *       对裸 {@code byte[]} 的期望, 也是 PostgreSQL 的原生二进制类型;</li>
 *   <li>{@code BLOB} → H2 报 {@code BINARY LARGE OBJECT}, 与裸 {@code byte[]} 对不上,
 *       启动时的 {@code ddl-auto: validate} 直接炸;</li>
 *   <li>实体上写 {@code @Lob} → PG 上映射成 {@code oid}, 图片被搬进 {@code pg_largeobject}
 *       那张**事务管不着**的系统表里。</li>
 * </ul>
 *
 * <p>所以下面那条 {@code DATA_TYPE} 的断言是「实测结论」的书面形式, 不是对 SQL 标准
 * 的复述。{@code BYTEA} 换成 {@code BLOB}(或者实体上补一个 {@code @Lob}) 会在这里变红,
 * 而那正是它该红的时候。
 */
class UserAvatarMigrationTest {

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v12-" + UUID.randomUUID().toString().replace("-", "")
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

    private static long seedUser(String url, String prefix) throws SQLException {
        String user = prefix + UUID.randomUUID().toString().substring(0, 12);
        execute(url, "INSERT INTO \"user\" (username, password, role, status) VALUES ('"
                + user + "', 'x', 'USER', 'ACTIVE')");
        return Long.parseLong(string(url, "SELECT id FROM \"user\" WHERE username = '" + user + "'"));
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

    // ========== 用例 ==========

    @Test
    @DisplayName("V12 能应用, 四列的可空性与实体的 nullable 一致")
    void migrationCreatesTheTable() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(count(url, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME = 'USER_AVATAR'"))
                .as("表建出来了")
                .isEqualTo(1);

        /* 三列 NOT NULL, 每一列在 UserAvatar.java 上都有 nullable = false 对应。
           少了它, 一行"有用户、没图片"的记录能写进库, 而读的时候会拿到一个 null
           字节数组 —— 那条路径上没人判空(它本该不可能发生)。 */
        for (String column : List.of("USER_ID", "CONTENT_TYPE", "BYTES")) {
            assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'USER_AVATAR' AND COLUMN_NAME = '" + column + "'"))
                    .as("%s 必须是 NOT NULL", column)
                    .isEqualTo("NO");
        }

        /* updated_at 刻意可空 —— 与 email/failed_attempts 同一条理由: 给已有表加 NOT NULL
           列时两个库都会因为没有 DEFAULT 而拒绝。它同时是 ETag 与 ?v= 版本号的来源,
           所以"没写过"必须是可表达的状态。 */
        assertThat(string(url, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'USER_AVATAR' AND COLUMN_NAME = 'UPDATED_AT'"))
                .as("updated_at 允许为空")
                .isEqualTo("YES");
    }

    /**
     * <b>本迁移唯一一条用实测定的东西: {@code bytes} 落成 VARBINARY, 不是 BLOB。</b>
     *
     * <p>这一条与实体的写法是**一对**: 脚本写 {@code BYTEA}、实体写裸 {@code byte[]},
     * 两边必须同时成立。改任意一边都会让另一边的 {@code ddl-auto: validate} 在启动时
     * 炸 —— 那时报的是"列类型不符", 而这个测试会先一步说清是哪一半错了。
     */
    @Test
    @DisplayName("bytes 是 BINARY VARYING(不是 BINARY LARGE OBJECT) —— 与裸 byte[] 配对")
    void theBytesColumnIsVarBinaryNotBlob() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        String type = string(url, "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'USER_AVATAR' AND COLUMN_NAME = 'BYTES'");
        assertThat(type)
                .as("BYTEA 在 H2 上报的就是这个名字; 换成 BLOB 会变成 BINARY LARGE OBJECT, "
                        + "那时 Hibernate 的 validate 会拒掉裸 byte[] 这个字段")
                .isEqualTo("BINARY VARYING");
        // 反向再断一次: 上面那条如果哪天因为 H2 改了大写/别名而失效, 这一条仍然拦得住
        // 「有人把它改成 BLOB」这唯一一种真的会发生的改动。
        assertThat(type)
                .as("实体上没有 @Lob, 这一列就不该是大型对象类型")
                .isNotEqualTo("BINARY LARGE OBJECT")
                .isNotEqualTo("CHARACTER LARGE OBJECT");
    }

    /**
     * 主键落在 {@code user_id} 上 —— 一人一张, 于是 {@code save} 天然是 upsert 语义。
     *
     * <p>换成自增 id + 唯一索引也能达到同样的约束力, 但那样「改头像」就得先查一次再决定
     * 插入还是更新; 主键是它, 一次 {@code save} 就说完了。这条断言守的是这个选择。
     */
    @Test
    @DisplayName("主键是 user_id 单列")
    void thePrimaryKeyIsTheUserId() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        /* ⚠️ 主键索引的名字是 PRIMARY_KEY_<表号> —— 那一位是 H2 自己编的, 拼不出确切值,
           所以这里用 LIKE 而不是等号(实测: 本表上它是 PRIMARY_KEY_F)。写死一个猜测的
           名字会让这条断言**永远查到 0 行**, 而 containsExactly 在空集上照样能"通过"
           —— 那种假绿与 AdminActionLogMigrationTest 上记的 'REFERENTIAL' 那个坑同类。 */
        assertThat(strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = 'USER_AVATAR' AND INDEX_NAME LIKE 'PRIMARY_KEY%' "
                + "ORDER BY ORDINAL_POSITION"))
                .as("一人一张: 主键就是 user_id")
                .containsExactly("USER_ID");
    }

    /**
     * 外键挂在 {@code user} 上且带 CASCADE —— <b>全 schema 唯一一条这样配的 user 外键。</b>
     *
     * <p>V7/V8 立过一条规矩:「{@code user_id} 一律不级联」。那张名单里没有这张表, 理由
     * 是那条规矩防的是「级联静默删掉子行而**计数器不跟着减**」—— 而这张表一个计数器都
     * 没有。所以这里是那条规矩的**例外**, 而且是刻意挑出来的例外, 得有人守着: 下一个人
     * 看到别的表都写 RESTRICT, 很可能"顺手对齐"。
     *
     * <p>两条一起验 —— 外键在不在、级联对不对。少了 CASCADE 的后果不是"删不干净", 而是
     * 将来做「注销账号」时被一个无法理解的约束错误挡住(本仓今天没有删用户功能, 所以
     * 这条约束目前只是一句数据完整性声明)。
     */
    @Test
    @DisplayName("外键 fk_user_avatar_user 指向 user 且 ON DELETE CASCADE")
    void theUserForeignKeyCascades() throws Exception {
        String url = freshUrl();

        migrate(url, "latest");

        assertThat(string(url, "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_NAME = 'FK_USER_AVATAR_USER'"))
                .as("头像不是计数器, 用户没了就该没了 —— 这正是 V7/V8 那条"
                        + "「user_id 不级联」规矩的例外")
                .isEqualTo("CASCADE");
        assertThat(strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                + "WHERE TABLE_NAME = 'USER_AVATAR' AND CONSTRAINT_NAME = 'FK_USER_AVATAR_USER'"))
                .as("外键落在 user_id 上 —— 挂到别的列上是另一回事, 但同样叫这个名字")
                .containsExactly("USER_ID");
    }

    /**
     * 字节能原样存取 —— 这是这张表存在的全部理由, 也是上面那条类型断言的**行为版本**。
     *
     * <p>用一段含 0x00 与高位字节的数组: 全 ASCII 的内容对编码问题不敏感, 而二进制列最
     * 常见的坏法恰恰是"经过了一次字符串转换"(那会把 0x00 截断、把 >0x7F 的字节改掉)。
     */
    @Test
    @DisplayName("写进去的字节原样读回来")
    void bytesRoundTrip() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long userId = seedUser(url, "a");

        // 0x00 与 0xFF 各来几个, 中间夹一段能读的 ASCII
        byte[] original = new byte[300];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 256);
        }
        original[0] = 0x00;
        original[1] = (byte) 0xFF;

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO user_avatar (user_id, content_type, bytes) VALUES (?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, "image/png");
            ps.setBytes(3, original);
            ps.executeUpdate();
        }

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bytes, content_type FROM user_avatar WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBytes("bytes"))
                        .as("长度与内容都要对: 少一个字节的图片在浏览器里是一张破图")
                        .isEqualTo(original);
                assertThat(rs.getString("content_type")).isEqualTo("image/png");
            }
        }
    }

    /**
     * 删用户会把他的头像一起带走 —— 上一条外键断言的**行为版本**。
     *
     * <p>与 {@code NotificationMigrationTest} 里那条「删用户被挡住」正好相反, 两张表并排
     * 看会很像, 判断却相反。两条测试都在, 是为了让这个相反是写下来的, 而不是谁"统一"掉
     * 其中一处之后才被发现。
     */
    @Test
    @DisplayName("删用户: 他的头像随之消失(不报外键错误)")
    void deletingAUserTakesTheAvatarWithIt() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        long doomed = seedUser(url, "a");
        long kept = seedUser(url, "b");

        for (long id : new long[]{doomed, kept}) {
            execute(url, "INSERT INTO user_avatar (user_id, content_type, bytes) VALUES ("
                    + id + ", 'image/jpeg', X'0102')");
        }
        assertThat(count(url, "SELECT COUNT(*) FROM user_avatar")).isEqualTo(2);

        execute(url, "DELETE FROM \"user\" WHERE id = " + doomed);

        assertThat(count(url, "SELECT COUNT(*) FROM user_avatar WHERE user_id = " + doomed))
                .as("没有 CASCADE 时这一句 DELETE 会直接报外键错误").isZero();
        assertThat(count(url, "SELECT COUNT(*) FROM user_avatar"))
                .as("另一个人的头像不能跟着消失").isEqualTo(1);
    }

    /**
     * V12 的两份脚本必须是同一批语句。
     *
     * <p>与 V4~V11 同一条规矩(见 {@link HotPathIndexMigrationTest}): 只改一份的后果是
     * H2 环境全绿、生产 PG 要到部署那一刻才发现少了一张表。这条是「PG 那边也应该没问题」
     * 这句话唯一能拿出来的证据 —— 也正是它能成立, 才让 {@code BYTEA} 这个写法值得
     * 在上面单独测一遍(两方言逐字相同 ⇒ 只有一种类型要验)。
     */
    @Test
    @DisplayName("V12 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V12 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V12__add_user_avatar.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

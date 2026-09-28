package com.animetracker.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
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
 * 盯住 V4 补的索引真的补在了「查询会用到的地方」.
 *
 * <p>为什么这件事必须用例来钉: 索引建错了不会报错. 列写反了 ({@code (updated_at, user_id)}
 * 而不是 {@code (user_id, updated_at)})、列少了一个、建到了别的表上 —— 迁移全都成功,
 * 接口照样 200, 只是每次查询依旧全表扫描. 也就是说, 补索引这件事如果只靠「读一遍 SQL
 * 觉得对」, 那么它和没补的唯一区别是多了一棵没人用的树. E2E 的验收标准是
 * 「大数据量下不再全表扫描」, 所以这里**造出大数据量**, 用 {@code EXPLAIN} 看优化器
 * 到底走了哪棵树.
 *
 * <p>顺带钉住两条「故意没建」的: {@code episode_watched(user_id, anime_id)} 与
 * {@code review(user_id)} 已经是多余的(理由见 V4 脚本末尾), 用例把「它们要的东西
 * V3 的唯一索引已经提供了」这一条验出来 —— 否则下一个人看到清单里有、库里没有,
 * 会顺手补上一条纯粹的写放大.
 *
 * <p>不起 Spring 上下文, 直接调 Flyway: 这里要验的是脚本, 断言的失败信息只该指向 SQL.
 *
 * <p>只跑 H2 那一份. PG 那份的验证靠 CI 的 image job(本机没有可用的 PG), 但两份 V4
 * 的语句是逐字相同的, 这一点由 {@link #bothDialectsRunTheSameStatements()} 守住 ——
 * 否则「PG 那边也应该没问题」就只是一句希望.
 */
class HotPathIndexMigrationTest {

    // ========== 工具 ==========

    /** 每个用例一个全新的内存库, 免得用例之间互相看见对方的行 */
    private static String freshUrl() {
        return "jdbc:h2:mem:anitrack-v4-" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    private static MigrateResult migrate(String url, String target) {
        return Flyway.configure()
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

    /** 优化器为这条 SQL 选的执行计划. H2 把它当一行结果返回 */
    private static String plan(String url, String sql) throws SQLException {
        return String.join(" ", strings(url, "EXPLAIN " + sql));
    }

    /** 某个索引的列, 按索引里的先后顺序. 顺序就是这条用例的重点之一 */
    private static List<String> indexColumns(String url, String table, String index) throws SQLException {
        return strings(url, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                + "WHERE TABLE_NAME = '" + table + "' AND INDEX_NAME = '" + index + "' "
                + "ORDER BY ORDINAL_POSITION");
    }

    private static List<String> indexNames(String url, String table) throws SQLException {
        return strings(url, "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                + "WHERE TABLE_NAME = '" + table + "'");
    }

    /**
     * 这张表上**我们建的**索引(V4 用 idx_ 前缀命名).
     *
     * <p>要按名字过滤是因为 H2 自己也会建索引: 每个外键列一棵(FK_<约束名>_INDEX_<n>)、
     * 每个唯一约束一棵(UK_<约束名>_INDEX_<n>)、主键一棵(PRIMARY_KEY_<n>)。这些不是
     * V4 加的, 混在一起数会得出「索引很多」的假象 —— 而这条实际上是在问
     * 「V4 到底动了哪些东西」。
     */
    private static List<String> ourIndexes(String url, String table) throws SQLException {
        return indexNames(url, table).stream()
                .filter(name -> name.startsWith("IDX_"))
                .collect(Collectors.toList());
    }

    /** 这条计划用到了这张表的哪几棵索引. 空的就意味着全表扫描(计划里写的是 tableScan) */
    private static List<String> indexesUsed(String url, String table, String plan) throws SQLException {
        return indexNames(url, table).stream().filter(plan::contains).collect(Collectors.toList());
    }

    /**
     * 这张表上有没有一棵索引, 它的前几列恰好是 {@code columns}.
     *
     * <p>这是「不用新建索引」这类结论的正确验法: 索引能不能服务某个 WHERE, 取决于
     * 那几个列是不是它的**最左前缀**, 与优化器最后挑了哪棵树无关. 用它来验,
     * 结论在 H2 与 PG 上都成立, 不必求助于某个方言的 EXPLAIN.
     */
    private static boolean hasIndexPrefixing(String url, String table, List<String> columns)
            throws SQLException {
        return indexColumnSets(url, table).stream()
                .anyMatch(cs -> cs.size() >= columns.size() && cs.subList(0, columns.size()).equals(columns));
    }

    /** 这张表上每棵索引的列(按索引内顺序) */
    private static List<List<String>> indexColumnSets(String url, String table) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        for (String index : indexNames(url, table)) {
            out.add(indexColumns(url, table, index));
        }
        return out;
    }

    /**
     * 造出「全表扫明显更贵」的数据量.
     *
     * <p>每张表两万行, 五十个用户各摊到四百行: 按某个用户查是 2% 的选择性, 全表扫描
     * 与走索引的代价差距足够大, 优化器的选择才有意义 —— 在几百行的库上它无论如何
     * 都会选全表扫描, 那样断言「命中索引」就会红得毫无道理.
     *
     * <p>最后一句 {@code ANALYZE} 是必需的: 数据是刚插进去的, 不刷新统计信息,
     * 计划就取决于优化器碰巧什么时候自己刷新一次 —— 那是一个随后台状态变化的断言.
     */
    private static void seed(String url) throws SQLException {
        // 四张表的外键都指向 "user", 所以先造用户; id 从 1 开始连续, 后面 MOD(X,50)+1 都在这个范围里
        execute(url, "INSERT INTO \"user\" (username, password, role, status) "
                + "SELECT 'u' || X, 'x', 'USER', 'ACTIVE' FROM SYSTEM_RANGE(1, 50)");

        execute(url, "INSERT INTO anime_tracking (user_id, subject_id, status, progress, updated_at) "
                + "SELECT MOD(X, 50) + 1, X, 'watching', 0, "
                + "       DATEADD('SECOND', X, TIMESTAMP '2026-01-01 00:00:00') "
                + "FROM SYSTEM_RANGE(1, 20000)");

        execute(url, "INSERT INTO review (user_id, subject_id, rating, content, created_at, updated_at) "
                + "SELECT MOD(X, 50) + 1, X, 8, '还行', "
                + "       DATEADD('SECOND', X, TIMESTAMP '2026-01-01 00:00:00'), "
                + "       DATEADD('SECOND', X, TIMESTAMP '2026-01-01 00:00:00') "
                + "FROM SYSTEM_RANGE(1, 20000)");

        execute(url, "INSERT INTO episode_watched (user_id, anime_id, episode_num, watched_at) "
                + "SELECT MOD(X, 50) + 1, X, 1, TIMESTAMP '2026-01-01 00:00:00' "
                + "FROM SYSTEM_RANGE(1, 20000)");

        execute(url, "INSERT INTO agent_conversation (user_id, title, persona, message_count, created_at, updated_at) "
                + "SELECT X, '会话', 'default', 0, TIMESTAMP '2026-01-01 00:00:00', "
                + "       TIMESTAMP '2026-01-01 00:00:00' FROM SYSTEM_RANGE(1, 50)");

        execute(url, "INSERT INTO agent_message (conversation_id, role, content, created_at) "
                + "SELECT MOD(X, 50) + 1, 'user', '你好', TIMESTAMP '2026-01-01 00:00:00' "
                + "FROM SYSTEM_RANGE(1, 20000)");

        execute(url, "ANALYZE");
    }

    // ========== 用例 ==========

    /**
     * 六条真实的高频 SQL, 逐条看优化器走的哪棵树.
     *
     * <p>前三条断言「用的就是新加的那个索引」. 后三条是另外一回事, 见各自的注释 ——
     * 它们要证明的是「不建也已经是索引查询」, 这个结论同样是靠计划看出来的,
     * 而不是靠「按理说最左前缀应该能命中」推出来的.
     */
    @Test
    @DisplayName("高频查询全部走索引, 没有一条是全表扫描")
    void hotPathQueriesAreIndexBacked() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");
        seed(url);

        // 详情页的热度条: 6 条 count 打到同一张表的同一个 subject_id 上
        String heat = plan(url,
                "SELECT COUNT(*) FROM anime_tracking WHERE subject_id = 42 AND status = 'watching'");
        assertThat(heat)
                .as("按 subject_id 的 count 应当走 V4 新加的索引; 走的还是全表扫描就说明索引没建上")
                .contains("IDX_ANIME_TRACKING_SUBJECT");

        // 评论列表: 过滤 subject_id + 按 created_at 倒序 + 分页, 最新的一条要先出来
        String comments = plan(url,
                "SELECT * FROM review WHERE subject_id = 42 ORDER BY created_at DESC LIMIT 20");
        assertThat(comments)
                .as("评论列表是最热的一条读, 过滤列与排序列都该在索引里")
                .contains("IDX_REVIEW_SUBJECT_CREATED");

        // 打开一个 AI 会话: 把该会话的消息按写入顺序取出来.
        //
        // 这里**不**断言命中的是 IDX_AGENT_MESSAGE_CONVERSATION: H2 会为外键列自动建
        // 一棵同列的索引, 于是它挑了那一棵 —— 而 PG 不为外键建索引, 生产上这条查询
        // 原本就是全表扫描, V4 这条索引正是给生产建的. 两边都成立的说法是
        // 「这条查询走索引, 不是全表扫描」, 所以断言的是这一句(哪棵树无所谓).
        // 「V4 确实建了这条索引」由 onlyTheIndexesThatEarnTheirKeepAreAdded 断言.
        String messages = plan(url,
                "SELECT * FROM agent_message WHERE conversation_id = 9 ORDER BY id");
        assertThat(indexesUsed(url, "AGENT_MESSAGE", messages))
                .as("按 conversation_id 取消息要走索引, 不是全表扫描")
                .isNotEmpty();

        // 首页「最近活动」: 按用户取最近 10 条.
        //
        // 这条**不**断言命中了 IDX_ANIME_TRACKING_USER_UPDATED: 在 H2 上 user_id 已经
        // 有三棵树打头(外键那棵、V3 唯一约束那棵、我们这棵), 它挑了最窄的一棵再排一次序
        // —— H2 不给「省了排序」记功. 所以这里断言的是「走了索引, 不是全表扫描」;
        // 这条索引在生产上才是唯一可用的那棵, 这一点没有本地方言能验, 只能靠读注释.
        String recent = plan(url,
                "SELECT * FROM anime_tracking WHERE user_id = 7 ORDER BY updated_at DESC LIMIT 10");
        assertThat(indexesUsed(url, "ANIME_TRACKING", recent))
                .as("按 user_id 取最近 N 条要走索引")
                .isNotEmpty();

        // 已看剧集: 清单里要求补 (user_id, anime_id), 而这张表上所有查询的列集
        // (user_id) / (user_id, anime_id) / (user_id, anime_id, episode_num)
        // 都是 V3 那个唯一约束的前缀 —— 前缀查询走同一棵树, 所以一条都不用加.
        // 这条断言验的是「不用加也没问题」那一半(查询已经是索引查询), 另一半
        // (确实没加) 由 onlyTheIndexesThatEarnTheirKeepAreAdded 断言.
        String watched = plan(url,
                "SELECT * FROM episode_watched WHERE user_id = 7 AND anime_id = 42");
        assertThat(indexesUsed(url, "EPISODE_WATCHED", watched))
                .as("(user_id, anime_id) 是 uk_episode_watched_user_anime_episode 的前缀, 已经是索引查询")
                .isNotEmpty();

        // 短评条数: 同理, 「按 user_id 过滤」由 uk_review_user_subject 的最左前缀提供.
        // (这条查询要的是条数, 不排序, 所以连 (user_id, created_at) 都用不上.)
        String reviewCount = plan(url, "SELECT COUNT(*) FROM review WHERE user_id = 7");
        assertThat(indexesUsed(url, "REVIEW", reviewCount))
                .as("按 user_id 数短评要走索引")
                .isNotEmpty();
    }

    /**
     * V4 加的就是这四条, 一条不多一条不少.
     *
     * <p>为什么把「没加什么」也写成断言: 清单里列了六条, 实际只建四条 —— 另外两条
     * ({@code review(user_id)} 与 {@code episode_watched(user_id, anime_id)}) 是多余的,
     * 理由写在 V4 脚本末尾。留着这个断言, 后来的人看到「清单里明明有」时, 会先看到
     * 一条红的用例和它的理由, 而不是顺手补一条纯粹的写放大上去。
     * 反过来说, 少了哪一条也会红 —— 四条各自对应一条真实的高频查询, 缺哪条就有
     * 一类查询回到全表扫描。
     */
    @Test
    @DisplayName("V4 只加这四条索引: 清单里另外两条是多余的, 已经验过")
    void onlyTheIndexesThatEarnTheirKeepAreAdded() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");

        assertThat(ourIndexes(url, "ANIME_TRACKING"))
                .containsExactlyInAnyOrder("IDX_ANIME_TRACKING_USER_UPDATED", "IDX_ANIME_TRACKING_SUBJECT");
        assertThat(ourIndexes(url, "REVIEW"))
                .containsExactly("IDX_REVIEW_SUBJECT_CREATED");
        assertThat(ourIndexes(url, "AGENT_MESSAGE"))
                .containsExactly("IDX_AGENT_MESSAGE_CONVERSATION");
        assertThat(ourIndexes(url, "EPISODE_WATCHED"))
                .as("清单里要求的 (user_id, anime_id) 是 V3 唯一索引的前缀, 再建一条只是写放大")
                .isEmpty();

        // 上面两条「没加」不能只是一句「按理说最左前缀能命中」—— 最左前缀是**模式匹配**
        // 出来的结论, 而这条恰恰是最容易想当然的地方(打头的列写错、列的顺序写反, 都会让
        // 这个结论不成立, 而从索引名字上完全看不出来). 所以直接对着索引定义验前缀.
        assertThat(hasIndexPrefixing(url, "EPISODE_WATCHED", List.of("USER_ID", "ANIME_ID")))
                .as("(user_id, anime_id) 必须是某棵已有索引的最左前缀 —— 这是不给它建索引的唯一理由")
                .isTrue();
        assertThat(hasIndexPrefixing(url, "REVIEW", List.of("USER_ID")))
                .as("(user_id) 必须是某棵已有索引的最左前缀 —— 这是不给 review 建单列索引的唯一理由")
                .isTrue();
    }

    /**
     * 索引的列必须是 (过滤字段, 排序字段) 这个顺序.
     *
     * <p>为什么单列出来验: 「索引存在」与「索引有用」是两回事. {@code (updated_at, user_id)}
     * 也是一个合法的、能被建出来的索引, 但对 {@code WHERE user_id=? ORDER BY updated_at}
     * 完全无用 —— 它只能靠 updated_at 定位. 只看索引名字或只看表上有没有索引, 是看不出
     * 这个区别的, 得看列的顺序.
     */
    @Test
    @DisplayName("新索引的列顺序: 过滤字段在前, 排序字段在后")
    void columnsAreFilterThenSort() throws Exception {
        String url = freshUrl();
        migrate(url, "latest");

        assertThat(indexColumns(url, "ANIME_TRACKING", "IDX_ANIME_TRACKING_USER_UPDATED"))
                .containsExactly("USER_ID", "UPDATED_AT");
        assertThat(indexColumns(url, "ANIME_TRACKING", "IDX_ANIME_TRACKING_SUBJECT"))
                .containsExactly("SUBJECT_ID");
        assertThat(indexColumns(url, "REVIEW", "IDX_REVIEW_SUBJECT_CREATED"))
                .containsExactly("SUBJECT_ID", "CREATED_AT");
        assertThat(indexColumns(url, "AGENT_MESSAGE", "IDX_AGENT_MESSAGE_CONVERSATION"))
                .containsExactly("CONVERSATION_ID");
    }

    /**
     * 已经手工建过索引的库, 再迁到 V4 不能炸.
     *
     * <p>这是 IF NOT EXISTS 存在的理由, 也是最容易在真实环境撞上的那一种: 线上出慢查询,
     * 运维先手工 {@code CREATE INDEX} 顶一下, 之后代码里的迁移才发版. 没有 IF NOT EXISTS,
     * 那次启动会以 "Index already exists" 直接失败 —— 这是**启动**失败, 不是某个功能不可用.
     * 同理也覆盖了「上一次迁移跑到一半被打断」的库.
     */
    @Test
    @DisplayName("库里已经手工建过其中一条索引: 迁移照跑, 其余三条照建")
    void handBuiltIndexesDoNotBreakTheMigration() throws Exception {
        String url = freshUrl();
        migrate(url, "3");
        execute(url, "CREATE INDEX idx_anime_tracking_subject ON anime_tracking (subject_id)");

        MigrateResult result = migrate(url, "latest");

        assertThat(result.success).isTrue();
        assertThat(indexColumns(url, "ANIME_TRACKING", "IDX_ANIME_TRACKING_SUBJECT"))
                .containsExactly("SUBJECT_ID");
        // 手工建过的那条没拦住别的: 剩下三条也要真的建出来
        assertThat(indexNames(url, "ANIME_TRACKING")).contains("IDX_ANIME_TRACKING_USER_UPDATED");
        assertThat(indexNames(url, "REVIEW")).contains("IDX_REVIEW_SUBJECT_CREATED");
        assertThat(indexNames(url, "AGENT_MESSAGE")).contains("IDX_AGENT_MESSAGE_CONVERSATION");
    }

    /**
     * V4 的两份脚本必须是同一批语句 —— 这一点由用例守住, 而不是靠注释里那句「逐字相同」.
     *
     * <p>只对 V4 立这条规矩: V1~V3 是**应当**有方言差异的(H2 的 ADD CONSTRAINT IF NOT EXISTS
     * 在 PG 上没有对应写法), 拿同一把尺子去量它们只会得到一堆噪音. V4 则相反 —— 语句相同
     * 是刻意的, 而两份文件里只改一份的后果是: 开发与测试环境(H2)全绿, 生产(PG)要到部署
     * 那一刻才发现少了一列, 正是这整套成对脚本机制想消灭的那类问题.
     */
    @Test
    @DisplayName("V4 的 h2 与 postgres 两份脚本: 去掉注释与格式差异后, 语句完全相同")
    void bothDialectsRunTheSameStatements() throws Exception {
        assertThat(statementsOf("postgres"))
                .as("V4 两份脚本的语句必须一致; 只改一份的话 H2 环境是绿的, 生产要到部署时才发现")
                .isEqualTo(statementsOf("h2"))
                .isNotEmpty();
    }

    /** 脚本里的语句. 去掉 -- 注释、把连续空白折成一个空格、按分号切开, 免得被换行方式绊住 */
    private static List<String> statementsOf(String dialect) throws IOException {
        Path file = Path.of("src", "main", "resources", "db", "migration", dialect,
                "V4__add_hot_path_indexes.sql");
        String sql = Files.readString(file).replaceAll("(?m)--.*$", "");
        return Arrays.stream(sql.replaceAll("\\s+", " ").split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

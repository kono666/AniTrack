package com.animetracker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/reviews} 的对外契约: 信封、键、关键词、档位、排序、分页、403.
 *
 * <p><b>为什么这一组非有不可, 而 {@code AdminServiceReviewPageTest} 那 48 个用例挡不住.</b>
 * 那个类里的仓储是 Mockito 造的, {@code @Query} 的文本**根本没进过解析器**。这一轮就真
 * 栽在这上面: {@code COUNT_ADMIN} 当时写的是 {@code "SELECT COUNT(r) FROM Review r"}
 * 却拼上了引用 {@code u.username} 的 WHERE —— 别名 {@code u} 从未声明, Hibernate 抛
 * {@code SemanticException: Could not interpret path expression 'u.username'}。而这条
 * {@code @Query} 是在**建仓 bean 时**校验的, 于是整个 ApplicationContext 起不来、整个
 * 后端起不来。48 个单元用例全绿, 一跑任何一个 {@code @SpringBootTest} 就全红。
 *
 * <p>同理, {@code AdminService.getReviewPage} 里那句 {@code REVIEW_SORTS.contains(sort)}
 * 也是只有走 HTTP 才拦得住: 它让"不带 sort 参数的默认请求"在 {@code Set.of} 的
 * {@code contains(null)} 上抛 NPE —— 而那正是绝大多数请求的形状。
 *
 * <p>所以本类第一条用例是**一个参数都不带的请求**。它看着最没用, 实际是这两类错的
 * 唯一哨兵: 少了任何一个, 那条会红, 而别的用例(都带着参数)照样绿。
 *
 * <p>还剩下三类只有这一层看得见的: 类级 {@code @Validated} 少了会让 {@code @Min}/
 * {@code @Max} 被静默忽略、{@code limit=100000} 一路拉到 service; 非管理员的 403 完全
 * 跨在过滤器链上; 以及 query 参数真的接到了 service 上(一个把 {@code sort} 参数丢掉的
 * controller 能通过所有 service 层用例)。这些错的共同点是**接口返回 200**。
 *
 * <p>数据是自己灌的, 全部落在 {@code SUBJECT_BASE} 起的一段 id 上, 每条断言都在
 * {@code keyword=kqw} 这个子集里做 —— 库是独立的内存库({@code anitrack-admin-review}),
 * 且关掉了启动预加载(否则这个 JVM 会去调 api.bgm.tv, 让一组本地用例依赖外网)。
 *
 * <p>用 MockMvc: 要验的是状态码与响应体, 不涉及连接器行为。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-review;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminReviewIntegrationTest {

    /** Bangumi 不会给出的 id 段: 断言只在自己造的这一段上做, 不受任何真实数据影响 */
    private static final int SUBJECT_BASE = 97000000;

    /** 灌进去的时间基准. 取未来是为了与 dev 初始化那两个账号的真实注册时间错开 */
    private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 1, 0, 0);

    /**
     * 灌进去的评论正文统一以它开头, 用来把断言圈在自己这几行里。
     *
     * <p>三个字母**都不是十六进制字符**, 也不是下面作者名的一部分: 关键词同时匹配
     * 正文与用户名, 若标记落在 {@code [0-9a-f]} 里, 它可能在某个随机用户名里也出现,
     * 于是"命中 4 条"变成"命中 5 条", 而且只在运气不好的时候红。
     */
    private static final String MARK = "kqw";

    /** 作者. 名字里带 {@code yyu} —— 那是"关键词也搜用户名"那一半的落点, 正文里没有它 */
    private static final String AUTHOR = "u_yyu_author";

    private static final String AUTHOR_PASSWORD = "passw0rd123";

    /** 唯一一条正文里带 {@code %} 的: 少了 ESCAPE, 搜一个 % 会命中全表 */
    private static final String PERCENT_CONTENT = MARK + "0 省了 50% 时间";

    /** 灌进 anime 表的那部番. 只有 {@code SUBJECT_BASE + 1} 缓存过 */
    private static final int CACHED_SUBJECT = SUBJECT_BASE + 1;

    private static final String CACHED_TITLE = "缓存过的那部番";

    /**
     * 管理端一行该有的键. 用"恰好是这些"而不是"包含这些": 多一个键同样是契约变更.
     *
     * <p>最后三个是 c94 举报补上的. {@code latestReason} 与 {@code latestReportAt}
     * 在**没有被举报的行上也要在**, 值为 {@code null} —— 与 {@code animeTitle} 同一条
     * 理由: 少一个键与"这个值恰好为空"在界面上长得一样, 而前端那一格会走 undefined 分支.
     *
     * <p>{@code deletedAt} 是 V14 软删补上的. 它同样"在架上的行也要在, 值为 null",
     * 而它比前面几个更硬: 界面靠它决定那一行显示「已移除 + 恢复」还是「删除」,
     * 键一少, 被移除的行看起来就与普通行一模一样 —— 管理员会以为它还活着.
     */
    private static final List<String> ROW_KEYS = List.of(
            "id", "subjectId", "animeTitle", "username", "userId",
            "rating", "content", "likeCount", "replyCount", "createdAt",
            "reportCount", "latestReason", "latestReportAt", "deletedAt");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 两个 token 各**整个类共用一次**。
     *
     * <p>登录是按 IP 限流的(10 次/分钟/IP), 逐用例登录会在第 11 个用例上拿到 429,
     * 而失败信息是"登录失败", 与被测接口毫无关系 —— 排查时很容易往错的方向找。
     * 注册限得更紧(5 次/分钟), 所以作者只注册一次, 由 {@link #ensureAuthor} 兜着。
     */
    private static String adminToken;
    private static String authorToken;

    /** 一行种子: 正文 + 评分 + 赞数. 回复数不在这里 —— 它是真的发出来的, 见下 */
    private record Seed(String content, int rating, int likes) {}

    /**
     * 四行, 三项刻意互相打乱:
     *
     * <pre>
     * 行    评分   赞数    回复数
     * kqw0   3     5       2(真的发两条)
     * kqw1   9     0       0
     * kqw2   5     3       0
     * kqw3   9     1       0
     * </pre>
     *
     * <p>三项同序的话, 六种排序组合里会有一半返回同一个答案, 于是那些断言变成同一条
     * 断言 —— 改错排序键也照样绿。
     */
    private static List<Seed> seeds() {
        return List.of(
                new Seed(PERCENT_CONTENT, 3, 5),
                new Seed(MARK + "1", 9, 0),
                new Seed(MARK + "2", 5, 3),
                new Seed(MARK + "3", 9, 1));
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        // 类里所有用例共用同一个 Spring 上下文, 也就共用同一个库 —— 先清掉上一轮灌的。
        // 不用 `LIKE 'kqw%'`: `_`/`%` 在 LIKE 里的含义与默认转义字符各库并不统一,
        // 而 subject_id 这一段 id 是死的, BETWEEN 到哪都一样。
        jdbc.update("DELETE FROM review_reply WHERE review_id IN "
                + "(SELECT id FROM review WHERE subject_id BETWEEN ? AND ?)", SUBJECT_BASE, SUBJECT_BASE + 99);
        jdbc.update("DELETE FROM review WHERE subject_id BETWEEN ? AND ?", SUBJECT_BASE, SUBJECT_BASE + 99);
        jdbc.update("DELETE FROM anime WHERE id BETWEEN ? AND ?", SUBJECT_BASE, SUBJECT_BASE + 99);

        ensureAuthor();
        long authorId = jdbc.queryForObject(
                "SELECT id FROM \"user\" WHERE username = ?", Long.class, AUTHOR);

        List<Seed> seeds = seeds();
        List<Object[]> args = new ArrayList<>(seeds.size());
        for (int i = 0; i < seeds.size(); i++) {
            args.add(new Object[]{authorId, SUBJECT_BASE + i, seeds.get(i).rating(),
                    seeds.get(i).content(), (long) seeds.get(i).likes(),
                    // created_at 显式写死: 走 JDBC 不经过实体的 @PrePersist, 不写就是 null,
                    // 而"时间戳为空的短评"是历史数据的形状, 不是应用会产生的行。排序断言
                    // 全按主键倒序, 用不到它 —— 灌上只是让这一行看起来像真的。
                    Timestamp.valueOf(BASE.plusMinutes(i))});
        }
        // 走 JDBC 而不是仓储: 断言要的是"每一列的来源就是这次灌进去的那个数", 而仓储
        // 会经手 @PrePersist、唯一性检查那一串。reply_count 一律 0: 它是下面真发回复
        // 改上去的, 写死一个数就证明不了那一列会被写活。
        jdbc.batchUpdate("INSERT INTO review "
                        + "(user_id, subject_id, rating, content, like_count, reply_count, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?)",
                args);

        // 只给一部番建缓存行, 另一条评论挂在一个 anime 表里没有的 id 上 —— 那正是
        // "这部番还没被拉进本地库"的真实状态
        jdbc.update("INSERT INTO anime (id, title, tags) VALUES (?, ?, ?)",
                CACHED_SUBJECT, CACHED_TITLE, "科幻");

        if (adminToken == null) {
            adminToken = login("admin", "admin123");
        }

        // 回复数用**真发两条回复**改上去, 不在种子里写死一个 2: 写死的话, 断言只证明
        // "那一列被原样读出来了", 证明不了它是一个会被写活的数
        long reviewId = reviewIdOf(PERCENT_CONTENT);
        authorReplies(reviewId, "第一条");
        authorReplies(reviewId, "第二条");
    }

    /** 注册只做一次(限流 5 次/分钟), 之后的用例只复用 token */
    private void ensureAuthor() throws Exception {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"user\" WHERE username = ?", Integer.class, AUTHOR);
        if (existing != null && existing > 0) {
            if (authorToken == null) {
                authorToken = login(AUTHOR, AUTHOR_PASSWORD);
            }
            return;
        }
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + AUTHOR + "\",\"email\":\"" + AUTHOR
                                + "@example.com\",\"password\":\"" + AUTHOR_PASSWORD + "\"}"))
                .andExpect(status().isOk());
        authorToken = login(AUTHOR, AUTHOR_PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private void authorReplies(long reviewId, String content) throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk());
    }

    private long reviewIdOf(String content) {
        Long id = jdbc.queryForObject("SELECT id FROM review WHERE content = ?", Long.class, content);
        assertThat(id).as("种子行 <%s> 应当刚灌进去", content).isNotNull();
        return id;
    }

    /**
     * 灌一条举报, 返回它的 id。
     *
     * <p>走 JDBC 而不是接口: 这里要验的是**列表怎么读举报**, 而举报怎么<b>写</b>进去
     * 由 {@code ReviewReportIntegrationTest} 管。经过接口还会顺手带上"不能举报自己的
     * 评论"那条规则, 于是种子数据能不能灌成取决于谁是作者 —— 那是另一个被测对象。
     *
     * <p>举报人必须<b>逐个指定</b>, 不给默认值: {@code (review_id, reporter_id)} 上有唯一
     * 约束, "同一条评论攒两条举报"这个场景只有换人才造得出来。给个默认值会让第二次调用
     * 静默撞约束失败 —— 而这里的写法强迫调用方想一下"这次是谁举报的"。
     */
    private long seedReport(long reviewId, long reporterId, String reason, String status) {
        jdbc.update("INSERT INTO review_report "
                        + "(review_id, reporter_id, reason, status, created_at) VALUES (?, ?, ?, ?, ?)",
                reviewId, reporterId, reason, status, Timestamp.valueOf(BASE));
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM review_report WHERE review_id = ?", Long.class, reviewId);
        assertThat(id).as("举报行应当刚灌进去").isNotNull();
        return id;
    }

    private long adminId() {
        return idOf("admin");
    }

    private long authorIdOf() {
        return idOf(AUTHOR);
    }

    private long idOf(String username) {
        Long id = jdbc.queryForObject("SELECT id FROM \"user\" WHERE username = ?", Long.class, username);
        assertThat(id).as("账号 <%s> 应当存在", username).isNotNull();
        return id;
    }

    // ========== 工具 ==========

    /** 以管理员身份发一次请求. 参数按 key,value 成对给 */
    private ResultActions getReviews(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/reviews")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** 发一次请求并取出 {@code data} 节点(顺带钉住 200 + code 200) */
    private JsonNode dataOf(String... params) throws Exception {
        String body = getReviews(params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private static List<String> contentsOf(JsonNode data) {
        List<String> contents = new ArrayList<>();
        data.path("list").forEach(row -> contents.add(row.path("content").asText()));
        return contents;
    }

    private static int totalOf(JsonNode data) {
        return data.path("total").asInt();
    }

    private static JsonNode rowOf(JsonNode data, String content) {
        for (JsonNode row : data.path("list")) {
            if (content.equals(row.path("content").asText())) {
                return row;
            }
        }
        throw new AssertionError("这一页里没有正文为 <" + content + "> 的行: " + data.path("list"));
    }

    // ========== 信封与键 ==========

    /**
     * <b>一个参数都不带</b>的请求 —— 本类里最要紧的一条。
     *
     * <p>它同时是两件事的哨兵, 而那两件事都会让**整个后端起不来或全部 500**:
     * {@code getReviewPage} 里 {@code Set.of(..).contains(null)} 抛的 NPE(默认请求正是
     * {@code sort == null} 的那条路), 以及 {@code COUNT_ADMIN} 那条 JPQL 能不能被解析
     * (别名没声明的话连 Spring 上下文都建不起来)。下面每条用例都带着参数, 只有这条不带。
     */
    @Test
    @DisplayName("不带任何参数的默认请求: 200 + {list,total,page} 信封, 行里十四个键一个不多一个不少")
    void theDefaultRequestIsAWellFormedPage() throws Exception {
        JsonNode data = dataOf();

        assertThat(totalOf(data)).isEqualTo(4);
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(contentsOf(data)).containsExactly(MARK + "3", MARK + "2", MARK + "1", PERCENT_CONTENT);

        data.path("list").forEach(row -> {
            List<String> keys = new ArrayList<>();
            row.fieldNames().forEachRemaining(keys::add);
            // "恰好是这些"而不是"包含这些": 少一个键(比如这一轮补上的 replyCount)与
            // 多一个键都是契约变更, 而"包含"只拦得住前者
            assertThat(keys).containsExactlyInAnyOrderElementsOf(ROW_KEYS);
            assertThat(row.path("username").asText()).isEqualTo(AUTHOR);
        });
    }

    // ========== 权限 ==========

    @Test
    @DisplayName("普通用户拿到 403, 匿名拿到 401 —— 两者不是同一件事")
    void onlyAdminsCanListReviews() throws Exception {
        mockMvc.perform(get("/api/admin/reviews")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/reviews"))
                .andExpect(status().isUnauthorized());
    }

    // ========== 番剧名 ==========

    /**
     * 番剧名那一格: 本地缓存过就给名字, 没缓存过就给 {@code null}, 而**键必须都在**。
     *
     * <p>{@code review.subject_id} 与 {@code anime} 之间没有外键, 那部番完全可能还没被
     * 拉进本地库 —— 而"这个 id 对应的番剧还没进本地库"本身是真信息。少了这个键, 前端
     * 那一格走的是 {@code undefined} 分支, 与 null 在界面上长得一模一样, 于是两者再也
     * 分不开了。
     */
    @Test
    @DisplayName("animeTitle: 缓存过的番给名字, 没缓存过的给 null, 而两种都带着这个键")
    void animeTitleIsResolvedPerRowAndNullWhenNotCached() throws Exception {
        JsonNode data = dataOf("keyword", MARK);

        assertThat(rowOf(data, MARK + "1").path("animeTitle").asText()).isEqualTo(CACHED_TITLE);

        // 没缓存过的那一行是 kqw0 —— 它的正文是 PERCENT_CONTENT, 不是 "kqw0"
        JsonNode uncached = rowOf(data, PERCENT_CONTENT);
        assertThat(uncached.path("subjectId").asInt()).isEqualTo(SUBJECT_BASE);
        assertThat(uncached.has("animeTitle")).as("键要在, 值才是 null").isTrue();
        assertThat(uncached.path("animeTitle").isNull()).isTrue();
    }

    // ========== 回复数 ==========

    /** 那个 2 是**真发出去的两条回复**改上去的, 不是种子里写死的 */
    @Test
    @DisplayName("replyCount 是评论表上那一列的真值: 发了两条就是 2, 没发过就是 0")
    void replyCountReflectsRealReplies() throws Exception {
        JsonNode data = dataOf("keyword", MARK);

        assertThat(rowOf(data, PERCENT_CONTENT).path("replyCount").asInt()).isEqualTo(2);
        assertThat(rowOf(data, MARK + "1").path("replyCount").asInt()).isZero();
    }

    // ========== 关键词 ==========

    /** 命中正文。用 {@code kqw2} 而不是 {@code kqw}: 后者四行全都命中, 分辨不出筛没筛 */
    @Test
    @DisplayName("关键词命中评论正文")
    void keywordMatchesReviewContent() throws Exception {
        JsonNode data = dataOf("keyword", MARK + "2");

        assertThat(totalOf(data)).isEqualTo(1);
        assertThat(contentsOf(data)).containsExactly(MARK + "2");
    }

    /**
     * 命中**用户名**, 而且大小写不敏感 —— 作者名里的 {@code yyu} 在正文里一次都没出现,
     * 所以命中只可能来自 {@code LOWER(u.username) LIKE ...} 这半边。
     *
     * <p>查询词写成大写的 {@code YYU}: 少了 {@code LOWER}, 大小写不匹配会让这条变成 0 条。
     * 若只查小写的 {@code yyu}, 去掉 LOWER 照样命中, 那半句就没被钉住。
     */
    @Test
    @DisplayName("关键词也命中用户名, 且大小写不敏感")
    void keywordMatchesUsernameCaseInsensitively() throws Exception {
        JsonNode data = dataOf("keyword", "YYU");

        assertThat(totalOf(data)).isEqualTo(4);
        assertThat(contentsOf(data)).noneMatch(c -> c.toLowerCase().contains("yyu"));
    }

    /**
     * 关键词里的 {@code %} 是**字面量**, 不是通配符。
     *
     * <p>搜一个孤零零的 {@code %}: 转义做对了, 只命中正文里那个百分号所在的一行;
     * 少了 {@code ESCAPE} / 转义, 模式串会变成"匹配一切", 四行全回来 —— 而界面上
     * 看不出这是错的, 只像是"搜索没起作用"。
     */
    @Test
    @DisplayName("关键词里的 % 是字面量: 搜一个 % 只命中正文里真有 % 的那一行")
    void percentInKeywordIsEscapedNotWild() throws Exception {
        JsonNode data = dataOf("keyword", "%");

        assertThat(totalOf(data)).isEqualTo(1);
        assertThat(contentsOf(data)).containsExactly(PERCENT_CONTENT);
    }

    // ========== 档位 ==========

    /** 三档闭区间: 1–4 / 5–7 / 8–10。四行的评分刻意是 3 / 9 / 5 / 9 */
    @Test
    @DisplayName("评分档位三档各自筛出该筛的行")
    void ratingBandsAreClosedIntervals() throws Exception {
        assertThat(contentsOf(dataOf("keyword", MARK, "rating", "low"))).containsExactly(PERCENT_CONTENT);
        assertThat(contentsOf(dataOf("keyword", MARK, "rating", "mid"))).containsExactly(MARK + "2");
        assertThat(contentsOf(dataOf("keyword", MARK, "rating", "high")))
                .containsExactly(MARK + "3", MARK + "1");
    }

    /**
     * 关键词与档位是 **AND**, 不是 OR。
     *
     * <p>{@code kqw0} 命中满分那一行(评分 3), 再叠一个 {@code high}(8–10)——
     * 两条件相交为空。写成 OR 的话这里会回来四条, 而列表上看起来只是"筛选没生效"。
     * 这也是"计数与取页共用同一份 WHERE"的落点: 两个条件只作用于其中一边时,
     * {@code total} 与 {@code list} 会各自回答不同的问题。
     */
    @Test
    @DisplayName("关键词与档位是 AND: 命中正文但评分不匹配 -> 0 条, total 也是 0")
    void keywordAndRatingAreCombinedWithAnd() throws Exception {
        JsonNode data = dataOf("keyword", MARK + "0", "rating", "high");

        assertThat(totalOf(data)).isZero();
        assertThat(contentsOf(data)).isEmpty();

        // 同一对条件里把档位换成匹配的那个 —— 否则上面两个 0 也可能只是"关键词没生效"
        JsonNode matched = dataOf("keyword", MARK + "0", "rating", "low");
        assertThat(totalOf(matched)).isEqualTo(1);
        assertThat(contentsOf(matched)).containsExactly(PERCENT_CONTENT);
    }

    // ========== 只看被举报的 ==========

    /**
     * {@code reported=true} 同时收窄 {@code total} 与 {@code list} —— 与关键词、档位
     * 共用的那一份 WHERE。
     *
     * <p>三件事一起断, 少一件这条就不成立:
     *
     * <ul>
     *   <li>不带参数时是<b>全部四条</b> —— 少了它, 一个"永远只返回被举报的"实现照样绿;</li>
     *   <li>带上之后只剩被举报的那两条, 且 {@code total} 也是 2 —— 计数与取页只作用于
     *       一边时, 前端按 {@code ceil(total/limit)} 算出来的页数会跟列表对不上;</li>
     *   <li>{@code reported=false} 与不带参数<b>等价</b> —— 它是"不筛", 不是"只筛没被举报的"。
     *       这条尤其要紧: 判据是"字面量 true 才算数", 写反成"false 时反过来筛"不会报错,
     *       只会让管理员点一下"只看被举报"再点回来时, 看到的是另一个集合。</li>
     * </ul>
     */
    @Test
    @DisplayName("reported=true: 只留下被举报的两条, total 一起收窄; false 与不传等价")
    void reportedFilterNarrowsBothTheCountAndTheRows() throws Exception {
        long reported1 = reviewIdOf(MARK + "1");
        long reported2 = reviewIdOf(MARK + "2");
        seedReport(reported1, adminId(), "SPAM", "PENDING");
        seedReport(reported2, authorIdOf(), "ABUSE", "PENDING");

        assertThat(totalOf(dataOf("keyword", MARK)))
                .as("不带这个参数时是全部四条")
                .isEqualTo(4);

        JsonNode filtered = dataOf("keyword", MARK, "reported", "true");
        assertThat(totalOf(filtered)).isEqualTo(2);
        assertThat(contentsOf(filtered)).containsExactly(MARK + "2", MARK + "1");

        assertThat(totalOf(dataOf("keyword", MARK, "reported", "false")))
                .as("false 是「不筛」, 不是「只筛没被举报的」")
                .isEqualTo(4);
        assertThat(contentsOf(dataOf("keyword", MARK, "reported", "false")))
                .as("四个内容键一起断, 免得只比了条数")
                .containsExactlyInAnyOrderElementsOf(contentsOf(dataOf("keyword", MARK, "reported", "1")));
    }

    /**
     * 行上的举报摘要: {@code reportCount} 是<b>待处理</b>的条数, {@code latestReason} 是
     * 其中最新那条的理由 —— 而<b>已忽略的不算</b>。
     *
     * <p>"已忽略的不算"是这一整块最容易写漏也最难被发现的地方: 摘要那条查询与筛选那条
     * EXISTS 是两个独立的地方各写一遍 {@code status = 'PENDING'}(一处
     * {@code ReviewQueries.FILTER_REPORTED}, 一处 {@code findPendingSummaries}), 只要
     * 漏掉任何一处, 症状都只是"数字大了一点"。所以下面同时断三样: 条数、最新理由、
     * 以及"忽略之后这两个数一起变小"。
     *
     * <p>{@code latestReason} 取的是<b>最新那条</b>而不是任意一条: 两条举报的理由刻意
     * 不同(SPAM 先、ABUSE 后), 取错了会拿到 SPAM —— 而"到底拿的哪一条"在界面上
     * 就是"管理员看到的是哪个理由"。
     */
    @Test
    @DisplayName("行上的举报摘要: 只数待处理的, latestReason 是最新那条的理由")
    void rowsCarryThePendingReportSummary() throws Exception {
        long review1 = reviewIdOf(MARK + "1");
        long review2 = reviewIdOf(MARK + "2");
        seedReport(review1, adminId(), "SPAM", "PENDING");
        long second = seedReport(review1, authorIdOf(), "ABUSE", "PENDING");
        seedReport(review2, adminId(), "SPOILER", "DISMISSED");

        JsonNode data = dataOf("keyword", MARK);

        JsonNode reportedRow = rowOf(data, MARK + "1");
        assertThat(reportedRow.path("reportCount").asInt())
                .as("两条待处理")
                .isEqualTo(2);
        assertThat(reportedRow.path("latestReason").asText())
                .as("取的是**最新**那条(后插的 ABUSE), 取成 SPAM 说明顺序反了")
                .isEqualTo("ABUSE");
        assertThat(reportedRow.path("latestReportAt").isNull())
                .as("有举报时这个时间必须在 —— 前端要拿它显示「最近被举报于…」")
                .isFalse();

        JsonNode dismissedRow = rowOf(data, MARK + "2");
        assertThat(dismissedRow.path("reportCount").asInt())
                .as("唯一一条举报已被忽略 —— 它不该再进队列, 也不该再计数")
                .isZero();
        assertThat(dismissedRow.path("latestReason").isNull())
                .as("键在、值为 null: 少了键的话前端那一格走的是 undefined 分支")
                .isTrue();

        JsonNode cleanRow = rowOf(data, MARK + "3");
        assertThat(cleanRow.path("reportCount").asInt()).isZero();
        assertThat(cleanRow.path("latestReason").isNull()).isTrue();
        assertThat(cleanRow.path("latestReportAt").isNull()).isTrue();

        // 忽略掉最新的那条之后再问一次: 条数与理由**一起**退回去 —— 只改一处的话,
        // 界面会出现"还剩 1 条, 但理由是刚忽略掉的那条"
        jdbc.update("UPDATE review_report SET status = 'DISMISSED' WHERE id = ?", second);

        JsonNode afterDismiss = rowOf(dataOf("keyword", MARK), MARK + "1");
        assertThat(afterDismiss.path("reportCount").asInt()).isEqualTo(1);
        assertThat(afterDismiss.path("latestReason").asText())
                .as("退回到还待处理的那条")
                .isEqualTo("SPAM");
    }

    /**
     * 被移除的评论与「待处理举报」的关系(V14) —— <b>这条用例同时钉着两个独立的地方</b>:
     * 队列那半写在 {@code ReviewQueries.FILTER_REPORTED} 里, 角标那半写在
     * {@code ReviewReportRepository.findPendingSummaries} 里。两者是两份 JPQL, 只改一处
     * 不会报错, 症状只是「队列里翻不到, 角标却挂着 1」—— 而管理员会一直去点那个角标。
     *
     * <p>为什么移除之后举报就算处理完了: 处置本身(把评论撤下来)就是举报想要的结果, 留着
     * 角标等于这条举报永远排不干净。而**举报行一条都没动** —— 所以恢复之后两样一起回来,
     * 这一点也在这里钉住(它是"软删"这个选择最值钱的性质)。
     *
     * <p>结尾那段走的是真接口({@code DELETE} / {@code PUT .../restore}), 不是直接改库:
     * 那两个端点的状态码与账本由 {@code AdminReviewDeleteIntegrationTest} 管, 这里要的是
     * **它们改完之后读路径说了什么**。
     */
    @Test
    @DisplayName("被移除的评论还在列表里(带 deletedAt), 但队列与角标一起归零; 恢复之后一起回来")
    void aRemovedReviewLeavesTheReportQueueAndItsBadge() throws Exception {
        long reviewId = reviewIdOf(MARK + "1");
        seedReport(reviewId, adminId(), "SPAM", "PENDING");

        assertThat(rowOf(dataOf("keyword", MARK), MARK + "1").path("deletedAt").isNull())
                .as("在架上时这一列是 null")
                .isTrue();
        assertThat(contentsOf(dataOf("keyword", MARK, "reported", "true")))
                .as("前提: 它本来在举报队列里")
                .containsExactly(MARK + "1");

        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        JsonNode removed = rowOf(dataOf("keyword", MARK), MARK + "1");
        assertThat(removed.path("deletedAt").isNull())
                .as("管理端列表里它还在, 而且带着移除时间 —— 这一行要能被恢复")
                .isFalse();
        assertThat(removed.path("reportCount").asInt())
                .as("角标归零")
                .isZero();
        assertThat(contentsOf(dataOf("keyword", MARK, "reported", "true")))
                .as("队列里也不该再有它")
                .isEmpty();

        mockMvc.perform(put("/api/admin/reviews/" + reviewId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        JsonNode restored = rowOf(dataOf("keyword", MARK), MARK + "1");
        assertThat(restored.path("deletedAt").isNull())
                .as("恢复之后不再是已移除")
                .isTrue();
        assertThat(restored.path("reportCount").asInt())
                .as("那条举报还原样待处理, 所以角标回来")
                .isEqualTo(1);
        assertThat(contentsOf(dataOf("keyword", MARK, "reported", "true")))
                .as("队列也回来")
                .containsExactly(MARK + "1");
    }

    /**
     * 认不出的 {@code reported} 值落回"不筛", 不是 400 —— 与 {@code rating}/{@code sort}
     * 同一条 doctrine。
     *
     * <p>与管理端<b>写</b>路径正好相反: {@code AdminService.setUserRole} 拿到未知角色回
     * 400。读路径筛错一个值的后果只是结果集放宽一点, 管理员看得出不对; 写路径写错一个值
     * 意味着数据落进一个谁也筛不出来的档位。这个不对称是有意的, 两边各有一处注释讲它。
     */
    @Test
    @DisplayName("认不出的 reported 值落回默认(不筛), 不是 400")
    void anUnknownReportedValueFallsBackToNoFilter() throws Exception {
        seedReport(reviewIdOf(MARK + "1"), adminId(), "SPAM", "PENDING");

        assertThat(contentsOf(dataOf("keyword", MARK, "reported", "yes"))).hasSize(4);
        assertThat(contentsOf(dataOf("keyword", MARK, "reported", "TRUE!"))).hasSize(4);
    }

    // ========== 排序 ==========

    /**
     * query 参数真的接到了 service 上。
     *
     * <p>service 层已经钉死了六种排序各自切在库上; 这里补的是另一半 —— 一个把
     * {@code sort}/{@code order} 丢在地上、永远用默认序的 controller 能通过
     * 所有 service 层用例。三个断言各换一列, 免得"排序参数生效了"与"某一列恰好如此"分不开。
     */
    @Test
    @DisplayName("sort / order 真的生效: 赞多的在前、赞少的在前、回复多的在前")
    void sortAndOrderReachTheService() throws Exception {
        assertThat(contentsOf(dataOf("keyword", MARK, "sort", "likes")))
                .containsExactly(PERCENT_CONTENT, MARK + "2", MARK + "3", MARK + "1");
        assertThat(contentsOf(dataOf("keyword", MARK, "sort", "likes", "order", "asc")))
                .containsExactly(MARK + "1", MARK + "3", MARK + "2", PERCENT_CONTENT);
        assertThat(contentsOf(dataOf("keyword", MARK, "sort", "replies", "order", "desc")).get(0))
                .isEqualTo(PERCENT_CONTENT);
    }

    /**
     * 认不出来的取值一律当"不筛"/默认, **不返回 400**。
     *
     * <p>与 {@code page}/{@code limit} 的越界不同: 越界是个**资源**问题(一次拉十万行),
     * 而写错一个档位键不是 —— 前端手抖把 {@code rating} 拼成 {@code Rateing}, 用户该看到
     * 的是那份没筛的列表, 不是一页错误。
     */
    @Test
    @DisplayName("认不出的 sort / order / rating 落回默认, 不是 400")
    void unknownFilterValuesFallBackInsteadOfFailing() throws Exception {
        JsonNode data = dataOf("keyword", MARK, "sort", "bogus", "order", "sideways", "rating", "NOPE");

        assertThat(totalOf(data)).isEqualTo(4);
        assertThat(contentsOf(data)).containsExactly(MARK + "3", MARK + "2", MARK + "1", PERCENT_CONTENT);
    }

    // ========== 分页 ==========

    /** 两页拼起来不重不漏 —— 只断言"每页两条"的话, 两页返回同一批行也能过 */
    @Test
    @DisplayName("page / limit 真的切片: 第 2 页接在第 1 页后面, 不重不漏")
    void pageAndLimitSliceWithoutOverlap() throws Exception {
        List<String> first = contentsOf(dataOf("keyword", MARK, "page", "1", "limit", "2"));
        List<String> second = contentsOf(dataOf("keyword", MARK, "page", "2", "limit", "2"));

        assertThat(first).containsExactly(MARK + "3", MARK + "2");
        assertThat(second).containsExactly(MARK + "1", PERCENT_CONTENT);

        List<String> bothPages = new ArrayList<>(first);
        bothPages.addAll(second);
        assertThat(bothPages).hasSize(4).doesNotHaveDuplicates();

        // limit 也真的限住了行数 —— 只验"两页合起来是四条"的话, 一个忽略 limit、
        // 每次都返回全部四行的实现照样绿(两页各四条, 拼起来八条才会红)
        assertThat(first).hasSize(2);
    }

    /**
     * 越界页: {@code list} 空, 但 {@code total} 是**真实**的匹配数。
     *
     * <p>total 若跟着回 0, 前端按 {@code ceil(total/limit)} 画的翻页控件会凭空少几页,
     * 用户从最后一页往回点就回不去了。{@code page} 也原样回给请求的那个值, 而不是被
     * 夹成有效页 —— 否则界面会显示"你在第 1 页"而列表是空的。
     */
    @Test
    @DisplayName("越界页: 列表空, total 仍是真的, page 原样回")
    void outOfRangePageKeepsTheRealTotal() throws Exception {
        JsonNode data = dataOf("keyword", MARK, "page", "99999", "limit", "20");

        assertThat(contentsOf(data)).isEmpty();
        assertThat(totalOf(data)).isEqualTo(4);
        assertThat(data.path("page").asInt()).isEqualTo(99999);
    }

    /**
     * {@code page}/{@code limit} 越界是 400, 不是被静默夹取。
     *
     * <p>这条跨在类级 {@code @Validated} 上: 少了那个注解, 方法级校验压根不装配,
     * {@code @Min}/{@code @Max} 被静默忽略, {@code limit=100000} 会真的去拉十万行 ——
     * 而接口返回 200。
     */
    @Test
    @DisplayName("page / limit 越界是 400, 边界值 100 是 200")
    void outOfRangePageParamsAreRejected() throws Exception {
        getReviews("page", "0").andExpect(status().isBadRequest());
        getReviews("limit", "0").andExpect(status().isBadRequest());
        getReviews("limit", "101").andExpect(status().isBadRequest());
        getReviews("limit", "100").andExpect(status().isOk());
    }
}

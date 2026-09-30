package com.animetracker.controller;

import com.animetracker.entity.Review;
import com.animetracker.repository.ReviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 点赞这条路的对外契约, 以及**冗余计数的对账**。
 *
 * <p>为什么必须在真库上跑一遍, service 单测不够:
 *
 * <ul>
 *   <li>点赞要动的两张表是**同一个事务**里的两个写(一行 {@code review_like} + 一句
 *       JPQL 自增)。事务边界、JPQL 批量更新与持久化上下文的关系、以及 {@code > 0}
 *       那条守卫, 都是 mock 看不见的东西 —— mock 里 {@code incrementLikeCount}
 *       永远"成功";</li>
 *   <li>{@code Review.likeCount} 标了 {@code insertable=false, updatable=false},
 *       而"标没标"的差别只在**生成出来的 UPDATE 语句**里。这里有一条用例专门把
 *       "改评论正文"走一遍, 看计数会不会被 Hibernate 的默认 UPDATE 刷回去 ——
 *       那是一条真实存在过的丢数据路径, 而且不报任何错(见 Review.likeCount 的注释)。</li>
 * </ul>
 *
 * <p>用 MockMvc + 内存库: 要验的是状态码、过滤器链与落库结果, 不涉及异步或连接器行为。
 * 关掉启动预加载, 否则这个 JVM 会去调 api.bgm.tv(见 AdminReviewDeleteIntegrationTest)。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-review-like;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        // 注册/登录默认是**按 IP** 限流的(注册 5 次/分钟、登录 10 次/分钟), 而这个类每个
        // 用例都要造两三个用户, 9 个用例加起来二十来次注册、二十来次登录, 于是跑到一半
        // 就开始 429 —— 失败信息会是"注册接口返回 429", 与点赞毫无关系, 极难联想到限流。
        // 限流本身由 AuthRateLimitIntegrationTest 专门验, 这里把它放开。
        "app.security.rate-limit.register-per-minute=1000",
        "app.security.rate-limit.login-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReviewLikeIntegrationTest {

    /** Bangumi 不可能返回的 id 段: 断言只在自己造的行上做, 不受任何真实数据影响 */
    private static final int SUBJECT_ONE = 999201;
    private static final int SUBJECT_TWO = 999202;
    private static final int SUBJECT_THREE = 999203;
    private static final int SUBJECT_FOUR = 999204;
    private static final int SUBJECT_FIVE = 999205;
    private static final int SUBJECT_SIX = 999206;
    private static final int SUBJECT_SEVEN = 999207;
    private static final int SUBJECT_EIGHT = 999208;
    private static final int SUBJECT_NINE = 999209;

    private static final long NEVER_EXISTED_ID = 999999999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 只有"旧副本存回去"那条用例用得上: 它要亲手读出并写回一个实体 */
    @Autowired
    private ReviewRepository reviewRepository;

    // ========== 造数据 ==========

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** 注册一个普通用户并登录 —— 注册接口本身是公开的 */
    private String registerAndLogin(String username) throws Exception {
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return login(username, password);
    }

    /** 让这个用户在某部番下写一条评论, 返回它在库里的 id */
    private long writeReview(String token, int subjectId, String content) throws Exception {
        mockMvc.perform(post("/api/review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"rating\":8,\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk());
        // 直接问库要 id: 列表接口按时间倒序, 同一个 subject 下多条时要挑出自己那条,
        // 而 created_at 的先后在同一个用例里未必分得开(同一微秒内落两条是常有的事)
        return jdbc.queryForObject(
                "SELECT id FROM review WHERE subject_id = ? ORDER BY id DESC", Long.class, subjectId);
    }

    /** 库里那一条的冗余计数 —— 与接口返回的数字对账用 */
    private long likeCountInDb(long reviewId) {
        Long value = jdbc.queryForObject(
                "SELECT like_count FROM review WHERE id = ?", Long.class, reviewId);
        return value == null ? -1L : value;
    }

    /** 库里真的有那几行赞 */
    private int likeRowsInDb(long reviewId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_like WHERE review_id = ?", Integer.class, reviewId);
        return n == null ? -1 : n;
    }

    private JsonNode like(String token, long reviewId) throws Exception {
        String body = mockMvc.perform(post("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    // ========== HTTP 契约 ==========

    @Test
    @DisplayName("点赞的响应就是 {liked, likeCount}, 计数由服务端给")
    void likeReturnsTheTwoFieldsTheClientNeeds() throws Exception {
        String author = registerAndLogin("likekey1");
        String liker = registerAndLogin("likekey2");
        long reviewId = writeReview(author, SUBJECT_ONE, "点赞契约用");

        mockMvc.perform(post("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        // 取消: 同一对键, liked 翻成 false
        mockMvc.perform(delete("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));
    }

    /**
     * 重复 POST 是幂等的: 第二次仍是 200 + {@code liked=true}, 而且计数**不变**。
     *
     * <p>这正是刻意不做单个 toggle 端点的理由 —— 一次点击因为超时重发变成两次请求时,
     * toggle 会把用户的赞翻回去, 而两次都是 200, 日志和服务端状态全都正常。
     */
    @Test
    @DisplayName("重复 POST: 仍然 200 + liked=true, 计数只加一次")
    void likingTwiceIsIdempotentOverHttp() throws Exception {
        String author = registerAndLogin("likeidem1");
        String liker = registerAndLogin("likeidem2");
        long reviewId = writeReview(author, SUBJECT_TWO, "幂等用");

        like(liker, reviewId);
        JsonNode second = like(liker, reviewId);

        assertThat(second.path("liked").asBoolean()).isTrue();
        assertThat(second.path("likeCount").asLong()).isEqualTo(1L);
        assertThat(likeRowsInDb(reviewId)).as("连点两下不能插出两行").isEqualTo(1);
        assertThat(likeCountInDb(reviewId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("点赞要登录: 匿名 POST -> 401")
    void anonymousCannotLike() throws Exception {
        String author = registerAndLogin("likeanon1");
        long reviewId = writeReview(author, SUBJECT_THREE, "匿名用");

        mockMvc.perform(post("/api/review/" + reviewId + "/like"))
                .andExpect(status().isUnauthorized());

        assertThat(likeRowsInDb(reviewId)).isZero();
    }

    @Test
    @DisplayName("赞一条不存在的评论 -> 404 评论不存在")
    void likingAMissingReviewIsNotFound() throws Exception {
        String liker = registerAndLogin("likemissing");

        mockMvc.perform(post("/api/review/" + NEVER_EXISTED_ID + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("评论不存在"));
    }

    /**
     * 「谁赞了」是公开的 —— 评论列表本身公开, 这一块是它的附属信息。
     *
     * <p>这条同时是 SecurityConfig 公开清单里那条通配规则
     * ({@code GET /api/review/{reviewId}/likes})的哨兵: 漏了它, 未登录访客看得见评论、
     * 一点"谁赞了"就 401, 而那不是权限设计, 是漏配。
     */
    @Test
    @DisplayName("谁赞了: 匿名也能看, 返回 {total, list}")
    void anyoneCanSeeWhoLiked() throws Exception {
        String author = registerAndLogin("likershow1");
        String liker = registerAndLogin("likershow2");
        long reviewId = writeReview(author, SUBJECT_FOUR, "谁赞了用");
        like(liker, reviewId);

        String body = mockMvc.perform(get("/api/review/" + reviewId + "/likes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(1L);
        assertThat(data.path("list").size()).isEqualTo(1);
        assertThat(data.path("list").get(0).path("username").asText()).isEqualTo("likershow2");
    }

    // ========== 计数对账 ==========

    /**
     * <b>连点序列 like -> unlike -> like 之后, {@code like_count} 恰好等于
     * {@code review_like} 的行数。</b>
     *
     * <p>这是冗余计数唯一的守卫, 也是整个设计里最容易被后来的改动破坏的一处:
     * 计数不是算出来的, 是**维护**出来的 —— 每一次加分都要有一次对应的减分, 而多减
     * 一次、漏减一次都不会报错, 界面上只是数字慢慢偏掉。三步都断言, 是因为偏差可能
     * 只在其中一个方向上: 只验最后一步的话, "unlike 忘了减"与"第二次 like 又加了一次"
     * 恰好能互相抵消, 结果看起来是对的。
     */
    @Test
    @DisplayName("对账: like→unlike→like 的每一步, 计数都等于点赞行数")
    void theCounterAlwaysMatchesTheRows() throws Exception {
        String author = registerAndLogin("likerec1");
        String liker = registerAndLogin("likerec2");
        long reviewId = writeReview(author, SUBJECT_FIVE, "对账用");

        like(liker, reviewId);
        assertThat(likeCountInDb(reviewId)).as("点第一次之后").isEqualTo(likeRowsInDb(reviewId))
                .isEqualTo(1L);

        mockMvc.perform(delete("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isOk());
        assertThat(likeCountInDb(reviewId)).as("取消之后").isEqualTo(likeRowsInDb(reviewId))
                .isZero();

        like(liker, reviewId);
        assertThat(likeCountInDb(reviewId)).as("再点一次之后").isEqualTo(likeRowsInDb(reviewId))
                .isEqualTo(1L);
    }

    /**
     * 计数已经偏到 0(而赞行还在)时, 取消点赞**不能**把它减成负数。
     *
     * <p>这条验的是 {@code decrementLikeCount} 里的 {@code AND r.likeCount > 0}。
     * 偏成 0 是可能发生的(手工改库、历史上某次漏减), 而守卫的作用是"止住", 不是
     * "纠正" —— 没有它, 这个数字会变成 -1 并一直往下走, 界面上显示"-1 人赞过"。
     *
     * <p>做法是把计数直接改小, 而不是去构造并发: 守卫要防的正是"计数与行数已经不一致"
     * 这个状态, 与它是怎么变成那样的无关。
     */
    @Test
    @DisplayName("计数已偏到 0 时取消点赞: 停在 0, 不会变成 -1")
    void theDecrementGuardStopsAtZero() throws Exception {
        String author = registerAndLogin("likeguard1");
        String liker = registerAndLogin("likeguard2");
        long reviewId = writeReview(author, SUBJECT_SIX, "守卫用");
        like(liker, reviewId);

        jdbc.update("UPDATE review SET like_count = 0 WHERE id = ?", reviewId);

        mockMvc.perform(delete("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(0));

        assertThat(likeCountInDb(reviewId)).as("减到负数就再也回不来了").isZero();
        assertThat(likeRowsInDb(reviewId)).as("行还是照样删掉").isZero();
    }

    /**
     * 改评论正文**不会**把别人点的赞抹掉。
     *
     * <p>{@code Review.likeCount} 上的 {@code updatable=false} 就是在防这条路径, 而它
     * 是一条真实的丢数据路径: Hibernate 的默认 UPDATE 会把所有可更新列写进 SET 子句,
     * 于是"编辑自己的评论"这个动作会把**读实体那一刻**的计数整个写回去 —— 这期间
     * 别人点的赞全部消失, 而且没有任何报错。改前没有这一列, 所以这条用例只有
     * 加了计数之后才存在。
     */
    @Test
    @DisplayName("作者改评论正文: 正文变了, 别人点的赞还在")
    void editingTheReviewDoesNotClobberTheCounter() throws Exception {
        String author = registerAndLogin("likeedit1");
        String liker = registerAndLogin("likeedit2");
        long reviewId = writeReview(author, SUBJECT_SEVEN, "改之前的正文");
        like(liker, reviewId);

        // 同一个人对同一部番再发一次 = 编辑原来那条(见 ReviewService.saveReview)
        mockMvc.perform(post("/api/review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + SUBJECT_SEVEN
                                + ",\"rating\":9,\"content\":\"改之后的正文\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT content FROM review WHERE id = ?",
                String.class, reviewId))
                .as("编辑要真的落库, 否则这条用例是空过")
                .isEqualTo("改之后的正文");
        assertThat(likeCountInDb(reviewId))
                .as("Hibernate 的默认 UPDATE 会把内存里的旧计数写回去, 于是赞没了")
                .isEqualTo(1L);
        assertThat(likeRowsInDb(reviewId)).isEqualTo(1);
    }

    /**
     * <b>一份"读过之后才被点赞"的实体再存回去, 不能把别人点的赞一并带走。</b>
     *
     * <p>这条盯的是 {@code Review.likeCount} 上的 {@code updatable=false}, 而它是
     * 上面那条 {@code editingTheReviewDoesNotClobberTheCounter} **盖不住**的:
     * 那条走的是网页那条路(read 与 write 在同一个持久化上下文里), 它读到的计数
     * 本来就是新的, 所以去掉 {@code updatable=false} 它照样是绿的(实测确认过)。
     *
     * <p>真正会丢数据的窗口是"实体在别处被读出来、再被写回去": Hibernate 的默认
     * UPDATE 会把**所有**可更新列写进 SET, 于是内存里那个旧计数覆盖掉这期间落地的赞。
     * 这个窗口在网页路径上只有几微秒(读与写之间), 造不出来, 但它在这套代码里是
     * 真实存在的形状 —— {@code IsolatedInsert} 的注释就明说它返回的是**游离实体**,
     * 而 {@code ReviewService.saveReview} 的冲突重试分支也是"拿到一个实体再存回去"。
     *
     * <p>所以这里不模拟时序, 直接把那个状态摆出来: 先读一份旧副本, 再让计数往前走,
     * 最后把旧副本存回去。断言的是"存回去之后计数还是新的", 而不是"这条路有多常见"。
     */
    @Test
    @DisplayName("旧副本存回去: 这期间别人点的赞不被刷掉")
    void savingAStaleReviewDoesNotClobberTheCounter() throws Exception {
        String author = registerAndLogin("likestale1");
        String liker = registerAndLogin("likestale2");
        long reviewId = writeReview(author, SUBJECT_NINE, "旧副本用");

        // 读一份出来 —— 此刻计数是 0, 之后它就是"那个旧副本"
        Review stale = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(stale.getLikeCount()).as("前提: 读的时候还没人赞").isZero();

        like(liker, reviewId);
        assertThat(likeCountInDb(reviewId)).isEqualTo(1L);

        stale.setContent("改过的正文");
        reviewRepository.saveAndFlush(stale);

        assertThat(jdbc.queryForObject("SELECT content FROM review WHERE id = ?",
                String.class, reviewId))
                .as("这次写要真的生效, 否则这条用例是空过")
                .isEqualTo("改过的正文");
        assertThat(likeCountInDb(reviewId))
                .as("默认的 UPDATE 会把游离实体里那个 0 写回来, 赞就没了 —— 而且不报错")
                .isEqualTo(1L);
    }

    /**
     * 评论列表里带上 {@code likeCount} 与 {@code likedByMe}, 而且 {@code likedByMe}
     * 是**按人**算的。
     *
     * <p>这条盯的是 service 里那条批量查询真的把结果合并进了每一项: 三个调用方
     * (作者本人、点过赞的人、匿名访客)看到的是同一份列表、不同的 {@code likedByMe}。
     * 少一个键或者恒为 false 都不会报错, 前端只是"心形永远不亮"。
     */
    @Test
    @DisplayName("评论列表: likeCount 与 likedByMe 按人算 (作者 false / 赞过的人 true / 匿名 false)")
    void theListCarriesTheCounterAndWhoLikedIt() throws Exception {
        String author = registerAndLogin("likelist1");
        String liker = registerAndLogin("likelist2");
        long reviewId = writeReview(author, SUBJECT_EIGHT, "列表载荷用");
        like(liker, reviewId);

        JsonNode asGuest = entryFor(SUBJECT_EIGHT, null);
        assertThat(asGuest.path("likeCount").asLong()).isEqualTo(1L);
        assertThat(asGuest.path("likedByMe").asBoolean())
                .as("匿名访客没有'赞过'这回事").isFalse();
        assertThat(entryFor(SUBJECT_EIGHT, liker).path("likedByMe").asBoolean()).isTrue();
        assertThat(entryFor(SUBJECT_EIGHT, author).path("likedByMe").asBoolean())
                .as("作者没点过自己的赞 —— likedByMe 是'我赞过没有', 不是'赞数大于 0'")
                .isFalse();
    }

    /** 从评论列表里挑出目标评论那一项. token 传 null 就是匿名访客(不带 Authorization 头) */
    private JsonNode entryFor(int subjectId, String token) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/review/list").param("subjectId", String.valueOf(subjectId));
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode item : objectMapper.readTree(body).path("data")) {
            if (item.path("content").asText().equals("列表载荷用")) {
                return item;
            }
        }
        throw new AssertionError("评论列表里没找到刚写的那条, 返回的是: " + body);
    }
}

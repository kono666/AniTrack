package com.animetracker.controller;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回复这条路的对外契约。
 *
 * <p>为什么这些必须在真库上跑一遍, service 单测盖不住:
 *
 * <ul>
 *   <li><b>删除权限是三支</b>(回复作者 ∪ 评论作者 ∪ 管理员), 而它要读的是
 *       {@code reply.getReview().getUser()} —— 一条<b>懒加载的关联</b>。这条关联在
 *       真环境下有没有被正确取出来({@code findByIdWithUser} 那两个 fetch join 够不够)、
 *       会不会在事务外炸, mock 里永远是对的;</li>
 *   <li><b>删回复要同时动两张表</b>(删 {@code review_reply} 行 + 给
 *       {@code review.reply_count} 减一)在同一个事务里, 还有库级的两级级联 ——
 *       这些全是事务边界与库行为, 不是 mock 的射程;</li>
 *   <li>{@code review.reply_count} 是**冗余计数**, 唯一的守卫就是一条对账断言
 *       (计数恒等于行数), 而它只能在真库上量。</li>
 * </ul>
 *
 * <p>限流属性与 {@code ReviewLikeIntegrationTest} 同一条理由: 每个用例都要造两三个
 * 用户, 不放开会跑到一半开始 429, 而失败信息会是"注册接口返回 429", 与回复毫无关系。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-review-reply;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        "app.security.rate-limit.register-per-minute=1000",
        "app.security.rate-limit.login-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReviewReplyIntegrationTest {

    /**
     * Bangumi 不可能返回的 id 段: 断言只在自己造的行上做, 不受任何真实数据影响。
     *
     * <p><b>每个用例一个自己的番剧 id, 不能合用。</b> 这些用例共用一个 Spring 上下文
     * 和同一个内存库, 合用的那一刻 {@code writeReview} 里那句「按 subject_id 取最新
     * 一条评论 id」就会返回两行, 报 {@code IncorrectResultSizeDataAccessException} ——
     * 而报错的用例与真正肇事的那条毫无关系, 查起来极难。
     */
    private static final int SUBJECT_FLAT = 999301;
    private static final int SUBJECT_EDIT = 999302;
    private static final int SUBJECT_EDIT_STAMP = 999310;
    private static final int SUBJECT_DELETE = 999303;
    private static final int SUBJECT_COUNT = 999304;
    private static final int SUBJECT_COUNT_LIST = 999311;
    private static final int SUBJECT_LIKE = 999305;
    private static final int SUBJECT_LIKE_WHO = 999312;
    private static final int SUBJECT_LIKE_SHOWN = 999313;
    private static final int SUBJECT_LIKE_ANON = 999314;
    private static final int SUBJECT_INBOX = 999306;
    private static final int SUBJECT_INBOX_SNIPPET = 999315;
    private static final int SUBJECT_INBOX_BLANK = 999316;
    private static final int SUBJECT_VALIDATION = 999307;
    private static final int SUBJECT_GUARDS = 999308;
    private static final int SUBJECT_CASCADE = 999309;
    private static final int SUBJECT_GUARD_ZERO = 999317;
    private static final int SUBJECT_GUARD_ZERO_COUNT = 999318;

    private static final long NEVER_EXISTED_ID = 999999999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ========== 造数据 ==========

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

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
        // MAX(id) 而不是 ORDER BY id DESC: 有几条用例**故意**在同一个 subject 下写两条
        // 评论(比如"有回复的"与"没回复的"那对), 而 queryForObject 碰上两行会抛
        // IncorrectResultSizeDataAccessException —— 报错的却是后面那一句, 查起来很远.
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM review WHERE subject_id = ?", Long.class, subjectId);
    }

    /** 发一条回复, 返回它在库里的 id。MAX(id) 的理由同 {@link #writeReview} */
    private long reply(String token, long reviewId, String content) throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk());
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM review_reply WHERE review_id = ?", Long.class, reviewId);
    }

    /** 读一条评论下的回复列表 */
    private JsonNode replies(String token, long reviewId) throws Exception {
        var request = get("/api/review/" + reviewId + "/replies");
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode likeReply(String token, long replyId) throws Exception {
        String body = mockMvc.perform(post("/api/reply/" + replyId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private long replyCountInDb(long reviewId) {
        Long value = jdbc.queryForObject(
                "SELECT reply_count FROM review WHERE id = ?", Long.class, reviewId);
        return value == null ? -1L : value;
    }

    private int replyRowsInDb(long reviewId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_reply WHERE review_id = ?", Integer.class, reviewId);
        return n == null ? -1 : n;
    }

    private long replyLikeCountInDb(long replyId) {
        Long value = jdbc.queryForObject(
                "SELECT like_count FROM review_reply WHERE id = ?", Long.class, replyId);
        return value == null ? -1L : value;
    }

    private int replyLikeRowsInDb(long replyId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reply_like WHERE reply_id = ?", Integer.class, replyId);
        return n == null ? -1 : n;
    }

    // ========== 列表 ==========

    /**
     * 回复是**扁平一层**, 按时间正序(先发生的在前), 而且 {@code isOwner} 按人算。
     *
     * <p>正序不是随手定的: 一楼的回复读起来像对话, 倒序会让它从下往上读。评论列表
     * 用的是倒序(最新的评论值得先看), 两者刻意不同 —— 这也是为什么这里的断言单独写一条。
     */
    @Test
    @DisplayName("回复列表: 扁平一层、按时间正序、isOwner 按人算")
    void repliesAreFlatAndOldestFirst() throws Exception {
        String author = registerAndLogin("replyflat1");
        String other = registerAndLogin("replyflat2");
        long reviewId = writeReview(author, SUBJECT_FLAT, "扁平用");
        reply(author, reviewId, "第一句");
        reply(other, reviewId, "第二句");
        reply(author, reviewId, "第三句");

        JsonNode list = replies(null, reviewId);

        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).path("content").asText()).isEqualTo("第一句");
        assertThat(list.get(2).path("content").asText()).isEqualTo("第三句");
        // 每一项的键一个都不能少: 前端那一行要用户名、头像、时间、计数、以及"是不是我的"
        JsonNode first = list.get(0);
        assertThat(first.hasNonNull("id")).isTrue();
        assertThat(first.path("username").asText()).isEqualTo("replyflat1");
        assertThat(first.path("likeCount").asLong()).isZero();
        assertThat(first.has("avatar")).isTrue();
        assertThat(first.hasNonNull("createdAt")).isTrue();
        assertThat(first.hasNonNull("updatedAt")).isTrue();

        assertThat(replies(author, reviewId).get(0).path("isOwner").asBoolean())
                .as("作者看自己那条").isTrue();
        assertThat(replies(other, reviewId).get(0).path("isOwner").asBoolean())
                .as("别人看同一条 —— 是'这条是不是我的', 不是'我有没有回复过'").isFalse();
    }

    // ========== 公开与鉴权 ==========

    /**
     * 「这条评论下的回复」是公开的, 而"写"不是。
     *
     * <p>这条同时是 SecurityConfig 里 {@code /api/review/<id>/replies} 那条通配的哨兵:
     * 漏了它, 未登录访客看得见评论、点开回复就 401 —— 那不是权限设计, 是漏配。
     */
    @Test
    @DisplayName("回复列表匿名可读; 匿名发回复 401")
    void anonymousCanReadButNotWrite() throws Exception {
        String author = registerAndLogin("replyanon1");
        long reviewId = writeReview(author, SUBJECT_GUARDS, "匿名用");
        reply(author, reviewId, "一条回复");

        assertThat(replies(null, reviewId).size()).isEqualTo(1);

        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"访客想说话\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(replyRowsInDb(reviewId)).as("401 之后不能留下行").isEqualTo(1);
    }

    /** 「谁回复了我」答的是"回给我的", 必须知道"我"是谁 —— 匿名 401 */
    @Test
    @DisplayName("谁回复了我: 匿名 401")
    void theInboxNeedsALogin() throws Exception {
        mockMvc.perform(get("/api/user/received-replies"))
                .andExpect(status().isUnauthorized());
    }

    // ========== 404 ==========

    @Test
    @DisplayName("评论不存在时: 读回复 404, 发回复 404")
    void aMissingReviewIsNotFound() throws Exception {
        String token = registerAndLogin("reply404a");

        mockMvc.perform(get("/api/review/" + NEVER_EXISTED_ID + "/replies"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("评论不存在"));
        mockMvc.perform(post("/api/review/" + NEVER_EXISTED_ID + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"喂\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("评论不存在"));
    }

    @Test
    @DisplayName("回复不存在时: 改 / 删 / 赞 / 取消赞 / 看谁赞了 都是 404")
    void aMissingReplyIsNotFoundEverywhere() throws Exception {
        String token = registerAndLogin("reply404b");

        mockMvc.perform(put("/api/reply/" + NEVER_EXISTED_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"改\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("回复不存在"));
        mockMvc.perform(delete("/api/reply/" + NEVER_EXISTED_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/reply/" + NEVER_EXISTED_ID + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/reply/" + NEVER_EXISTED_ID + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/reply/" + NEVER_EXISTED_ID + "/likes"))
                .andExpect(status().isNotFound());
    }

    // ========== 校验 ==========

    /**
     * 回复只有文字, 空回复没有任何含义 —— 接口层与库两层都挡。
     *
     * <p>与评论的区别是刻意的: 评论是「评分 + 可选文字」, 只打分不写字是合法用法;
     * 回复没有评分可打, 所以 {@code ReplyRequest} 比 {@code ReviewRequest} 多一个
     * {@code @NotBlank}。
     */
    @Test
    @DisplayName("空回复 / 超长回复: 400, 一行都不落库")
    void blankAndOversizedRepliesAreRejected() throws Exception {
        String token = registerAndLogin("replyvalid");
        long reviewId = writeReview(token, SUBJECT_VALIDATION, "校验用");

        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("回复内容不能为空"));

        String tooLong = "字".repeat(5001);
        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("回复内容不能超过 5000 个字符"));

        assertThat(replyRowsInDb(reviewId)).as("两条都被挡在库外").isZero();
        assertThat(replyCountInDb(reviewId)).as("计数也不能动").isZero();
    }

    // ========== 编辑 ==========

    /**
     * 只有作者能改 —— **不**把权限放宽给评论作者。
     *
     * <p>与删除的判据刻意不同: 删是"我的地盘我做主"(一条辱骂回复不能等人来处理),
     * 改是"替别人说话"。这两件事性质不同, 所以两条路径的权限也不同, 这条用例把它钉住。
     */
    @Test
    @DisplayName("改回复: 作者可以; 评论作者与他人都不行(403)")
    void onlyTheAuthorCanEdit() throws Exception {
        String reviewAuthor = registerAndLogin("replyedit1");
        String replyAuthor = registerAndLogin("replyedit2");
        String stranger = registerAndLogin("replyedit3");
        long reviewId = writeReview(reviewAuthor, SUBJECT_EDIT, "编辑用");
        long replyId = reply(replyAuthor, reviewId, "原来的话");

        mockMvc.perform(put("/api/reply/" + replyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"外人改的\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只能编辑自己的回复"));
        mockMvc.perform(put("/api/reply/" + replyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewAuthor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"楼主的修改\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只能编辑自己的回复"));

        mockMvc.perform(put("/api/reply/" + replyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + replyAuthor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我改的\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("我改的"));

        assertThat(jdbc.queryForObject("SELECT content FROM review_reply WHERE id = ?",
                String.class, replyId)).isEqualTo("我改的");
    }

    /**
     * 「已编辑」的判据是 {@code updatedAt > createdAt}, 由 {@code @PreUpdate} 写。
     *
     * <p>两个时间戳在插入时是同一次 {@code now()}, 所以刚发的回复**不会**被标成已编辑 ——
     * 这一点与"改过之后真的会被标上"同样重要: 前者错了是满屏的「已编辑」。
     */
    @Test
    @DisplayName("改过的回复: updatedAt 走到 createdAt 之后(前端据此显示「已编辑」)")
    void editingMovesUpdatedAtPastCreatedAt() throws Exception {
        String author = registerAndLogin("replystamp");
        long reviewId = writeReview(author, SUBJECT_EDIT_STAMP, "时间戳用");
        long replyId = reply(author, reviewId, "刚发的");

        JsonNode fresh = replies(null, reviewId).get(0);
        assertThat(fresh.path("updatedAt").asText())
                .as("刚插入时两条时间戳来自同一次 now(), 不能被标成已编辑")
                .isEqualTo(fresh.path("createdAt").asText());

        mockMvc.perform(put("/api/reply/" + replyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"改过的\"}"))
                .andExpect(status().isOk());

        JsonNode edited = replies(null, reviewId).get(0);
        assertThat(edited.path("content").asText()).isEqualTo("改过的");
        assertThat(edited.path("updatedAt").asText())
                .as("前端比较的就是这两个字符串, 它们必须真的分开")
                .isGreaterThan(edited.path("createdAt").asText());
    }

    // ========== 删除权限(三支) ==========

    /**
     * <b>删除是三个身份的并集</b>: 回复作者、评论作者、管理员。
     *
     * <p>「评论作者也能删自己楼里的回复」是拍板的决定, 理由是那一栏毕竟是他的地盘 ——
     * 没有这个权限, 一条辱骂回复只能等管理员来处理。三条各走一遍, 是因为它们落在
     * {@code canDelete} 的三个不同分支上, 而那三个分支读的关联不同(回复的作者、
     * 评论的作者), 少取一个 fetch 就只在其中一条上炸。
     */
    @Test
    @DisplayName("删回复: 回复作者 / 评论作者 / 管理员都能删, 陌生人 403")
    void threeIdentitiesCanDelete() throws Exception {
        String reviewAuthor = registerAndLogin("replydel1");
        String replyAuthor = registerAndLogin("replydel2");
        String stranger = registerAndLogin("replydel3");
        String admin = login("admin", "admin123");
        long reviewId = writeReview(reviewAuthor, SUBJECT_DELETE, "删除权限用");

        // 1) 回复作者删自己的
        long byAuthor = reply(replyAuthor, reviewId, "自己删");
        mockMvc.perform(delete("/api/reply/" + byAuthor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + replyAuthor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("回复已删除"));

        // 2) 评论作者删**别人**在自己楼下的 —— 这一支是"我的地盘我做主"的全部内容,
        //    也是与编辑权限(不给评论作者)刻意区分开的那一支
        long byReviewAuthor = reply(replyAuthor, reviewId, "楼主删");
        mockMvc.perform(delete("/api/reply/" + byReviewAuthor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewAuthor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("回复已删除"));

        // 3) 管理员删
        long byAdmin = reply(replyAuthor, reviewId, "管理员删");
        mockMvc.perform(delete("/api/reply/" + byAdmin)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());

        // 4) 陌生人不行
        long kept = reply(replyAuthor, reviewId, "陌生人删不掉");
        mockMvc.perform(delete("/api/reply/" + kept)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权删除该回复"));

        assertThat(replyRowsInDb(reviewId)).as("只剩最后那条没被删的").isEqualTo(1);
        assertThat(replyCountInDb(reviewId)).as("计数与行数一起走到 1").isEqualTo(1L);
    }

    // ========== 冗余计数对账 ==========

    /**
     * <b>{@code review.reply_count} 从头到尾等于 {@code review_reply} 的行数。</b>
     *
     * <p>这是这个冗余计数唯一的守卫。它不是算出来的, 是**维护**出来的 —— 发一条加一、
     * 删一条减一, 而多减一次、漏减一次都不报错, 界面上只是评论列表里那个「N 条回复」
     * 慢慢偏掉。每一步都断言, 是因为偏差可能只在其中一个方向上: 只验最后一步的话,
     * "删的时候忘了减"与"发的时候多加了"恰好能互相抵消, 看起来是对的。
     *
     * <p>顺带钉住: <b>删一条不存在的回复不会把计数减掉</b> —— 那条路径在 404 之前
     * 就返回了, 而如果哪天有人把减计数提到权限检查之前, 这个数字会被一个恶意请求
     * 一路减到 0(防负数守卫只挡在 0)。
     */
    @Test
    @DisplayName("对账: 发→删→发 之后, review.reply_count 恰好等于回复行数")
    void theCounterAlwaysMatchesTheRows() throws Exception {
        String author = registerAndLogin("replyrec1");
        String other = registerAndLogin("replyrec2");
        long reviewId = writeReview(author, SUBJECT_COUNT, "对账用");

        long first = reply(other, reviewId, "第一条");
        assertThat(replyCountInDb(reviewId)).as("发一条之后")
                .isEqualTo(replyRowsInDb(reviewId)).isEqualTo(1L);

        mockMvc.perform(delete("/api/reply/" + first)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isOk());
        assertThat(replyCountInDb(reviewId)).as("删掉之后")
                .isEqualTo(replyRowsInDb(reviewId)).isZero();

        reply(other, reviewId, "第二条");
        assertThat(replyCountInDb(reviewId)).as("再发一条之后")
                .isEqualTo(replyRowsInDb(reviewId)).isEqualTo(1L);

        // 删一条不存在的: 404 而已, 计数不能被动过
        mockMvc.perform(delete("/api/reply/" + NEVER_EXISTED_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
                .andExpect(status().isNotFound());
        assertThat(replyCountInDb(reviewId)).as("删不掉的东西不能影响计数").isEqualTo(1L);
    }

    /**
     * 计数已经偏到 0(而赞行还在)时, 取消回复的赞**不能**把它减成负数。
     *
     * <p>与评论那一侧的 {@code ReviewLikeIntegrationTest#theDecrementGuardStopsAtZero}
     * 是同一条, 理由也一样: 验的是 {@code decrementReplyCount} 里的
     * {@code AND r.replyCount > 0}。计数偏成 0 是可能发生的(手工改库、历史上某次漏减),
     * 守卫的作用是"止住"而不是"纠正" —— 没有它, 这个数字会变成 -1 并一直往下走,
     * 界面上显示"-1 人赞过"。
     *
     * <p><b>回复这一侧的守卫必须单独验一遍</b>: 它是另一条 JPQL(另一张表、另一个仓储),
     * 不是把评论那条参数化出来的 —— 评论那条绿着, 完全不能说明回复这条写对了。
     * 做法是把计数直接改小, 而不是去构造并发: 守卫要防的正是"计数与行数已经不一致"
     * 这个状态, 与它是怎么变成那样的无关。
     */
    @Test
    @DisplayName("计数已偏到 0 时取消回复的赞: 停在 0, 不会变成 -1")
    void theReplyDecrementGuardStopsAtZero() throws Exception {
        String author = registerAndLogin("replyguard1");
        String liker = registerAndLogin("replyguard2");
        long reviewId = writeReview(author, SUBJECT_GUARD_ZERO, "守卫用");
        long replyId = reply(author, reviewId, "被赞的回复");
        likeReply(liker, replyId);

        jdbc.update("UPDATE review_reply SET like_count = 0 WHERE id = ?", replyId);

        mockMvc.perform(delete("/api/reply/" + replyId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(0));

        assertThat(replyLikeCountInDb(replyId)).as("减到负数就再也回不来了").isZero();
        assertThat(replyLikeRowsInDb(replyId)).as("行还是照样删掉").isZero();
    }

    /**
     * {@code review.reply_count} 已经偏到 0(而回复行还在)时, 删这条回复**不能**把它减成负数。
     *
     * <p>与上面那条是**两个不同的守卫**: 这条验的是 {@code ReviewRepository.decrementReplyCount}
     * 里的 {@code AND r.replyCount > 0}, 落在 {@code review} 表上; 上面那条验的是
     * {@code ReviewReplyRepository.decrementLikeCount}, 落在 {@code review_reply} 表上。
     * 两条 JPQL 分属两个仓储、两张表, 一条绿着完全不能说明另一条写对了 —— 这正是
     * 第一次跑反向验证时发现的洞: 两条守卫当时都**没有任何用例**盯着。
     *
     * <p>偏成 0 是可能发生的(手工改库、历史上某次漏减), 而守卫的作用是"止住"而不是
     * "纠正"。没有它, 评论列表里那个「N 条回复」会变成 -1 并一直往下走。
     */
    @Test
    @DisplayName("回复数已偏到 0 时再删一条回复: 停在 0, 不会变成 -1")
    void theReplyCountDecrementGuardStopsAtZero() throws Exception {
        String author = registerAndLogin("replyguard3");
        long reviewId = writeReview(author, SUBJECT_GUARD_ZERO_COUNT, "回复数守卫用");
        long replyId = reply(author, reviewId, "会被删掉的回复");

        jdbc.update("UPDATE review SET reply_count = 0 WHERE id = ?", reviewId);

        mockMvc.perform(delete("/api/reply/" + replyId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author))
                .andExpect(status().isOk());

        assertThat(replyCountInDb(reviewId)).as("减到负数就再也回不来了").isZero();
        assertThat(replyRowsInDb(reviewId)).as("行还是照样删掉").isZero();
    }

    /**
     * 评论列表载荷里的 {@code replyCount} 跟着回复走, 而且是**每条各自的数**。
     *
     * <p>这一列存在的全部意义就是让详情页不必为了每条评论再发一次 COUNT ——
     * 所以它必须真的等于那条评论的回复数, 而不是整页的总和或常量。
     */
    @Test
    @DisplayName("评论列表: 每条评论的 replyCount 是自己那一串回复的数")
    void theReviewListCarriesThePerReviewReplyCount() throws Exception {
        String author = registerAndLogin("replycnt1");
        String other = registerAndLogin("replycnt2");
        long twoReplies = writeReview(author, SUBJECT_COUNT_LIST, "有两条回复");
        long noReply = writeReview(other, SUBJECT_COUNT_LIST, "一条回复都没有");
        reply(other, twoReplies, "一条");
        reply(author, twoReplies, "两条");

        String body = mockMvc.perform(get("/api/review/list")
                        .param("subjectId", String.valueOf(SUBJECT_COUNT_LIST)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long seen = 0;
        for (JsonNode item : objectMapper.readTree(body).path("data")) {
            if (item.path("id").asLong() == twoReplies) {
                assertThat(item.path("replyCount").asLong()).isEqualTo(2L);
                seen++;
            } else if (item.path("id").asLong() == noReply) {
                assertThat(item.path("replyCount").asLong())
                        .as("没有回复的那条是 0, 不是 NULL、也不是别人的数").isZero();
                seen++;
            }
        }
        assertThat(seen).as("两条评论都要出现在这一页里, 否则上面的断言是空过").isEqualTo(2);
    }

    /**
     * 删掉一条短评, 它下面的回复跟着走 —— 走的是**网页那条删除路径**。
     *
     * <p>库级的级联本身由 {@code ReviewReplyMigrationTest} 直接对着库验(那条更重要,
     * 因为它覆盖了绕过全部 Java 代码的管理端路径)。这里验的是另一件事:
     * {@code ReviewService.deleteReview} 删实体时, <b>Hibernate 不会先跑去把子行的
     * 外键置空</b> —— 那正是"在 Review 上映射一个 {@code @OneToMany} 集合"会招来的
     * 麻烦, 也是为什么回复一律走仓储查询。
     */
    @Test
    @DisplayName("走接口删掉评论: 它下面的回复一起消失, 请求本身不报错")
    void deletingAReviewOverHttpTakesItsRepliesWithIt() throws Exception {
        String author = registerAndLogin("replycas1");
        String other = registerAndLogin("replycas2");
        long reviewId = writeReview(author, SUBJECT_CASCADE, "级联用");
        reply(other, reviewId, "一条回复");

        mockMvc.perform(delete("/api/review/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + author))
                .andExpect(status().isOk());

        assertThat(replyRowsInDb(reviewId)).isZero();
        mockMvc.perform(get("/api/review/" + reviewId + "/replies"))
                .andExpect(status().isNotFound());
    }

    // ========== 回复的赞 ==========

    /**
     * 回复的赞与评论的赞是同一套契约: 幂等、计数由服务端给、并对得上行数。
     *
     * <p>重复 POST 之后计数**只加一次**, 这是"幂等"在数字上的样子。
     */
    @Test
    @DisplayName("回复的赞: 重复 POST 仍 200 + liked=true, 计数等于赞行数")
    void likingAReplyIsIdempotent() throws Exception {
        String author = registerAndLogin("replylike1");
        String liker = registerAndLogin("replylike2");
        long reviewId = writeReview(author, SUBJECT_LIKE, "赞回复用");
        long replyId = reply(author, reviewId, "值得一赞");

        assertThat(likeReply(liker, replyId).path("likeCount").asLong()).isEqualTo(1L);
        JsonNode second = likeReply(liker, replyId);
        assertThat(second.path("liked").asBoolean()).isTrue();
        assertThat(second.path("likeCount").asLong()).as("连点两下只加一次").isEqualTo(1L);
        assertThat(replyLikeRowsInDb(replyId)).isEqualTo(1);
        assertThat(replyLikeCountInDb(replyId)).isEqualTo(replyLikeRowsInDb(replyId)).isEqualTo(1L);

        mockMvc.perform(delete("/api/reply/" + replyId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + liker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));
        assertThat(replyLikeCountInDb(replyId)).isEqualTo(replyLikeRowsInDb(replyId)).isZero();

        // 取消之后再点回来 —— 计数要能重新涨上去
        assertThat(likeReply(liker, replyId).path("likeCount").asLong()).isEqualTo(1L);
    }

    /** 回复列表里的 {@code likedByMe} 是按人算的, 与评论列表同一条规矩 */
    @Test
    @DisplayName("回复列表: likedByMe 按人算 (赞过的人 true / 作者 false / 匿名 false)")
    void theReplyListCarriesWhoLikedIt() throws Exception {
        String author = registerAndLogin("replylk1");
        String liker = registerAndLogin("replylk2");
        long reviewId = writeReview(author, SUBJECT_LIKE_WHO, "谁赞了用");
        long replyId = reply(author, reviewId, "这条会被赞");
        likeReply(liker, replyId);

        assertThat(replies(liker, reviewId).get(0).path("likedByMe").asBoolean()).isTrue();
        assertThat(replies(author, reviewId).get(0).path("likedByMe").asBoolean())
                .as("作者没点过自己的回复").isFalse();
        assertThat(replies(null, reviewId).get(0).path("likedByMe").asBoolean())
                .as("匿名访客没有'赞过'这回事").isFalse();
        assertThat(replies(null, reviewId).get(0).path("likeCount").asLong()).isEqualTo(1L);
    }

    /** 「谁赞了这条回复」必须公开 —— 与评论那一侧同一条理由, 也是 SecurityConfig 那条通配的哨兵 */
    @Test
    @DisplayName("谁赞了这条回复: 匿名也能看, 返回 {total, list}")
    void anyoneCanSeeWhoLikedAReply() throws Exception {
        String author = registerAndLogin("replysh1");
        String liker = registerAndLogin("replysh2");
        long reviewId = writeReview(author, SUBJECT_LIKE_SHOWN, "谁赞了回复用");
        long replyId = reply(author, reviewId, "被赞的回复");
        likeReply(liker, replyId);

        String body = mockMvc.perform(get("/api/reply/" + replyId + "/likes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(1L);
        assertThat(data.path("list").size()).isEqualTo(1);
        assertThat(data.path("list").get(0).path("username").asText()).isEqualTo("replysh2");
    }

    /** 回复的赞也要登录才能点 */
    @Test
    @DisplayName("匿名赞回复: 401, 一行都不落库")
    void anonymousCannotLikeAReply() throws Exception {
        String author = registerAndLogin("replyalk1");
        long reviewId = writeReview(author, SUBJECT_LIKE_ANON, "匿名赞用");
        long replyId = reply(author, reviewId, "别赞我");

        mockMvc.perform(post("/api/reply/" + replyId + "/like"))
                .andExpect(status().isUnauthorized());

        assertThat(replyLikeRowsInDb(replyId)).isZero();
        assertThat(replyLikeCountInDb(replyId)).isZero();
    }

    // ========== 「谁回复了我」 ==========

    /**
     * 「谁回复了我」= 我写的短评下面、**别人**发的回复。
     *
     * <p>三个边界一起验, 因为每一个漏掉都是一种静默的错误列表:
     * <ul>
     *   <li>只列<b>我的</b>评论下的回复 —— 别人家楼里发生的事不该出现在这里;</li>
     *   <li>排除<b>我自己</b>发的回复 —— 那不是"谁回复了我";</li>
     *   <li>带上被回复的那条评论的<b>摘要</b>与 {@code subjectId} —— 光有回复正文的话,
     *       用户不知道这是在哪条番剧下说的什么(摘要而不是原文, 见 service 里的 {@code snippet})。</li>
     * </ul>
     */
    @Test
    @DisplayName("谁回复了我: 只列我评论下的、别人发的回复, 并带上被回复评论的摘要")
    void theInboxOnlyListsOtherPeoplesRepliesUnderMyReviews() throws Exception {
        String me = registerAndLogin("replyinbox1");
        String other = registerAndLogin("replyinbox2");
        long myReview = writeReview(me, SUBJECT_INBOX, "我的评论");
        long theirReview = writeReview(other, SUBJECT_INBOX, "别人的评论");

        reply(other, myReview, "回给我的");
        reply(other, myReview, "又回了我一条");
        reply(me, myReview, "我自己在自己楼下说的");
        reply(me, theirReview, "我去别人楼里说的");
        reply(other, theirReview, "别人楼里别人说的");

        JsonNode mine = inbox(me);
        assertThat(mine.size())
                .as("两条是别人回我的; 我自己在自己楼下的那条不算'谁回复了我'")
                .isEqualTo(2);

        JsonNode theirs = inbox(other);
        assertThat(theirs.size())
                .as("别人收到的同理: 只算我去他楼里说的那条")
                .isEqualTo(1);

        for (JsonNode row : mine) {
            assertThat(row.path("reviewId").asLong()).as("只能是我那条评论下的")
                    .isEqualTo(myReview);
            assertThat(row.path("subjectId").asInt()).isEqualTo(SUBJECT_INBOX);
            assertThat(row.path("reviewContent").asText())
                    .as("要能看出是回在哪条评论下").isEqualTo("我的评论");
            assertThat(row.path("username").asText())
                    .as("我自己发的回复不该出现在这里").isEqualTo("replyinbox2");
        }
        assertThat(mine.get(0).path("content").asText())
                .as("按时间倒序: 最新的一条在前").isEqualTo("又回了我一条");
        assertThat(mine.get(1).path("content").asText()).isEqualTo("回给我的");
    }

    /**
     * 评论正文只带**摘要**, 不是原文。
     *
     * <p>这一条防的是一份会随互动量悄悄变大的响应: 评论正文最长 5000 字, 30 行原样带出去
     * 就是几十 KB —— 而列表里每一项真正要回答的只是"你回的是哪条"。
     */
    @Test
    @DisplayName("谁回复了我: 评论只带摘要(超长截断成 60 字 + 省略号)")
    void theInboxCarriesASnippetNotTheWholeReview() throws Exception {
        String me = registerAndLogin("replysnip1");
        String other = registerAndLogin("replysnip2");
        String longText = "长".repeat(200);
        long reviewId = writeReview(me, SUBJECT_INBOX_SNIPPET, longText);
        reply(other, reviewId, "回一条很长的");

        JsonNode row = inbox(me).get(0);

        assertThat(row.path("reviewContent").asText())
                .as("60 个字 + 一个省略号; 原样带全文的话 30 行就是几十 KB")
                .hasSize(61)
                .endsWith("…");
        assertThat(row.path("content").asText())
                .as("摘要只裁评论正文; 回复本身是这一行的主角, 一个字都不能少")
                .isEqualTo("回一条很长的");
    }

    /**
     * 评论是可以**只打分不写字**的, 摘要这时必须是 {@code null} 而不是空串。
     *
     * <p>前端的判据是"有没有内容", 空串会让它变成"有内容但看不见" —— 那一行会显示成
     * 一片空白, 而不是「（无文字）」。
     */
    @Test
    @DisplayName("谁回复了我: 被回复的评论没写字时, 摘要给 null 而不是空串")
    void theInboxGivesNullWhenTheReviewHasNoText() throws Exception {
        String me = registerAndLogin("replyblank1");
        String other = registerAndLogin("replyblank2");
        long reviewId = writeReview(me, SUBJECT_INBOX_BLANK, "");
        reply(other, reviewId, "回一条只有评分的");

        JsonNode row = inbox(me).get(0);

        assertThat(row.path("content").asText()).as("前提: 那条回复真的发出去了")
                .isEqualTo("回一条只有评分的");
        assertThat(row.path("reviewContent").isNull())
                .as("空串会让前端的判断变成'有内容但看不见'").isTrue();
    }

    /** 没有人回我的时候是一个空列表, 不是 404、也不是 null —— 个人页那一块据此显示空态 */
    @Test
    @DisplayName("没人回复我: 空列表")
    void theInboxIsEmptyWhenNobodyReplied() throws Exception {
        String lonely = registerAndLogin("replyinbox3");

        assertThat(inbox(lonely).size()).isZero();
    }

    /** 读自己的收件箱 */
    private JsonNode inbox(String token) throws Exception {
        String body = mockMvc.perform(get("/api/user/received-replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("list");
    }
}

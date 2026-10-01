package com.animetracker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.animetracker.util.TextSnippet;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三个通知端点的对外契约: {@code GET /api/user/notifications}、
 * {@code GET /api/user/notifications/unread-count}、{@code PUT /api/user/notifications/read}。
 *
 * <p><b>它同时接过了 {@code GET /api/user/received-replies} 那一整段用例(V8 的)。</b>
 * 那个端点被删除而不是保留, 所以旧用例里每一条断言都得在这里有继任者, 一个都不能少:
 * 只列别人对我做的事(不含自己对自己)、摘要截到 60 字、只打分不写字时是 null 而不是空串、
 * 一条都没有时是空列表、匿名 401。少一条就意味着"某个当年守住的行为现在没人守了"。
 *
 * <p>而 service 层那两个测试({@code NotificationWriteTest} / {@code NotificationReadTest})
 * 盖不住的是这一层独有的一件东西: <b>「我」是谁</b>。三个端点的路径里都没有用户 id,
 * 收件人全靠登录态推出来 —— 也就是说, 这里唯一能验的是"拿别人的 token 看不到我的"。
 * 那一句是这张表全部安全性的所在(仓储里每条查询都带 {@code recipient.id}, 但那只在
 * 有人真的把当前用户传进去时才成立)。
 *
 * <p>限流: 这个类要两个账号, 注册与登录各来一次就够 —— token 是静态的, 整个类共用。
 * 属性把窗口放宽到 1000 只是防止将来有人加用例时撞上 429(那个报错会伪装成
 * "注册接口返回 429", 与通知毫无关系)。这里刻意**不**按用例注册: 10 次/分钟的登录
 * 限流下, 那样写会在本机与 CI 上随机红。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-notification-http;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        "app.security.rate-limit.register-per-minute=1000",
        "app.security.rate-limit.login-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class NotificationIntegrationTest {

    /**
     * 每个用例一段自己的作品 id。合用的那一刻 {@code writeReview} 里那句
     * "按 subject_id 取最新一条评论 id" 会返回两行, 报的事与肇事者毫无关系 ——
     * 与 {@code ReviewReplyIntegrationTest} 同一条理由。
     */
    private static final int SUBJECT_BASIC = 960401;
    private static final int SUBJECT_TYPES = 960402;
    /** 只打分不写字的那条评论 —— 它要是和 SUBJECT_TYPES 同号, "最新一条"那条断言就是在两堆数据里挑 */
    private static final int SUBJECT_TYPES_BLANK = 960407;
    private static final int SUBJECT_SELF = 960403;
    private static final int SUBJECT_PAGING = 960404;
    private static final int SUBJECT_CASCADE = 960405;
    private static final int SUBJECT_GUARDS = 960406;

    /** 清库时按这个区间删 —— 上面那些 id 全落在里面 */
    private static final int SUBJECT_FROM = 960401;
    private static final int SUBJECT_TO = 960499;

    private static final String ME = "notifme";
    private static final String ACTOR = "notifactor";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 两个账号整个类共用一次 —— 登录按 IP 限流(10 次/分钟) */
    private static String meToken;
    private static String actorToken;

    @BeforeEach
    void seedAndLogin() throws Exception {
        // 删评论会级联带走回复与通知, 但顺序写清楚更好读: 通知 → 赞 → 回复 → 评论
        jdbc.update("DELETE FROM review WHERE subject_id BETWEEN " + SUBJECT_FROM + " AND " + SUBJECT_TO);

        if (meToken == null) {
            meToken = registerAndLogin(ME);
        }
        if (actorToken == null) {
            actorToken = registerAndLogin(ACTOR);
        }
    }

    // ========== 动作(都走真接口, 通知才会真的被写出来) ==========

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** 注册一个账号并登录, 返回 token。{@code passw0rd123} 是 {@code PasswordPolicy} 能过的最小形状 */
    private String registerAndLogin(String username) throws Exception {
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return login(username, password);
    }

    private long writeReview(String token, int subjectId, String content) throws Exception {
        mockMvc.perform(post("/api/review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"rating\":8,"
                                + "\"content\":" + json(content) + "}"))
                .andExpect(status().isOk());
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM review WHERE subject_id = ?", Long.class, subjectId);
    }

    private long reply(String token, long reviewId, String content) throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":" + json(content) + "}"))
                .andExpect(status().isOk());
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM review_reply WHERE review_id = ?", Long.class, reviewId);
    }

    private void likeReview(String token, long reviewId) throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void likeReply(String token, long replyId) throws Exception {
        mockMvc.perform(post("/api/reply/" + replyId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** 一段文本变成 JSON 字符串字面量 —— 用 ObjectMapper 而不是拼引号, 免得正文里的引号把请求拼坏 */
    private String json(String text) throws Exception {
        return text == null ? "null" : objectMapper.writeValueAsString(text);
    }

    // ========== 三个端点 ==========

    /** 取通知列表的 {@code data} 节点(顺带钉住 200 + code 200) */
    private JsonNode notifications(String token, String... params) throws Exception {
        var request = get("/api/user/notifications");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private long unreadCount(String token) throws Exception {
        String body = mockMvc.perform(get("/api/user/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("count").asLong();
    }

    private void markRead(String token) throws Exception {
        mockMvc.perform(put("/api/user/notifications/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ========== 鉴权 ==========

    /**
     * 三个端点都要登录 —— 它们答的都是"我的", 不知道"我"是谁就没法回答。
     *
     * <p>三个都要各来一遍: 它们是三条独立的路径, 而 SecurityConfig 里漏配一条的症状
     * 只是那一条 401(看起来还挺像"设计如此"), 于是漏掉的那条会一直漏着。
     */
    @Test
    @DisplayName("匿名访问三个端点都是 401")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/user/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/user/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/user/notifications/read")).andExpect(status().isUnauthorized());
    }

    /**
     * <b>拿别人的 token 看不到我的通知。</b>
     *
     * <p>这是这个类唯一不可替代的一条: 三个端点的路径里没有用户 id, 收件人只能从登录态
     * 推出来 —— 一旦有人把 {@code @CurrentUser} 换成一个请求参数, 或者在哪条查询上漏了
     * {@code recipient.id}, 这里就是唯一会响的地方。而"越权读到别人的通知"不会有任何
     * 报错, 只是我的列表里出现了别人的事。
     */
    @Test
    @DisplayName("只能看到自己的: 各人的列表互不串门, 计数也是各算各的")
    void notificationsArePerRecipient() throws Exception {
        long myReview = writeReview(meToken, SUBJECT_BASIC, "只给我看的评论");
        long hisReview = writeReview(actorToken, SUBJECT_BASIC, "他家的楼");
        reply(actorToken, myReview, "回给我的");
        reply(actorToken, myReview, "又回了我一条");
        // 我去他楼里说的话: 该进他的收件箱, 与我的一条都不能混
        reply(meToken, hisReview, "我去别人楼里说的");

        JsonNode mine = notifications(meToken);
        assertThat(mine.path("total").asLong())
                .as("两条是别人回我的; 我在别人楼里说的那条不算'我的通知'").isEqualTo(2L);
        for (JsonNode row : mine.path("list")) {
            assertThat(row.path("reviewId").asLong()).as("只能是我那条评论下的")
                    .isEqualTo(myReview);
            assertThat(row.path("subjectId").asInt()).isEqualTo(SUBJECT_BASIC);
            assertThat(row.path("actorName").asText())
                    .as("我自己发的不该出现在这里").isEqualTo(ACTOR);
        }
        assertThat(mine.path("list").get(0).path("replyContent").asText())
                .as("按时间倒序: 最新的在前").isEqualTo("又回了我一条");
        assertThat(mine.path("list").get(1).path("replyContent").asText()).isEqualTo("回给我的");
        assertThat(unreadCount(meToken)).isEqualTo(2L);

        // 他手上只有我去他楼里说的那一条 —— 我收到的两条一条都不该漏过去
        JsonNode theirs = notifications(actorToken);
        assertThat(theirs.path("total").asLong()).isEqualTo(1L);
        assertThat(theirs.path("list").get(0).path("actorName").asText())
                .as("对他来说是'我'回了他").isEqualTo(ME);
        assertThat(theirs.path("list").get(0).path("reviewId").asLong()).isEqualTo(hisReview);
        assertThat(unreadCount(actorToken))
                .as("计数也必须按人算, 不能是「全表未读」这个数").isEqualTo(1L);
    }

    // ========== 列表的形状 ==========

    /**
     * 三类通知各显示哪几段 —— 一行渲染在三条类型上共用, 所以要钉住"哪些字段该有、哪些该空"。
     *
     * <p>摘要截到 60 字、以及"只打分不写字时是 null 而不是空串"这两条是从
     * {@code received-replies} 那几条旧用例搬过来的, 前端正是靠后者显示「（无文字）」。
     */
    @Test
    @DisplayName("三类通知: 各自的字段都在; 摘要截到 60 字; 只打分不写字时摘要是 null")
    void everyTypeCarriesWhatThePageNeeds() throws Exception {
        long myReview = writeReview(meToken, SUBJECT_TYPES, "甲".repeat(200));
        long myReply = reply(meToken, myReview, "我自己补一句");
        long actorReview = writeReview(actorToken, SUBJECT_TYPES, "他的评论");
        long myReplyOnHis = reply(meToken, actorReview, "我回他的");
        // 三类都产生在我头上: 他的回复 / 他的赞 / 他赞我的回复
        reply(actorToken, myReview, "他回我一句");
        likeReview(actorToken, myReview);
        likeReply(actorToken, myReplyOnHis);

        JsonNode list = notifications(meToken).path("list");
        assertThat(list.size()).as("三类各一条").isEqualTo(3);

        JsonNode replyRow = rowOfType(list, "REPLY");
        assertThat(replyRow.path("actorName").asText()).isEqualTo(ACTOR);
        assertThat(replyRow.path("subjectId").asInt()).isEqualTo(SUBJECT_TYPES);
        assertThat(replyRow.path("reviewId").asLong()).isEqualTo(myReview);
        assertThat(replyRow.hasNonNull("replyId")).isTrue();
        assertThat(replyRow.path("replyContent").asText()).isEqualTo("他回我一句");
        assertThat(replyRow.path("reviewContent").asText())
                .as("200 字的评论截成 60 字 + 省略号")
                .hasSize(TextSnippet.LENGTH + 1)
                .endsWith("…");
        assertThat(replyRow.path("read").asBoolean()).isFalse();
        assertThat(replyRow.hasNonNull("createdAt")).isTrue();
        assertThat(replyRow.has("actorAvatar")).isTrue();

        JsonNode likeRow = rowOfType(list, "REVIEW_LIKE");
        assertThat(likeRow.path("reviewId").asLong()).isEqualTo(myReview);
        assertThat(likeRow.path("replyId").isNull())
                .as("赞评论那条本来就没有回复 —— 它不能被 INNER JOIN 吃掉, 也不该编一个 id")
                .isTrue();
        assertThat(likeRow.path("replyContent").isNull()).isTrue();

        JsonNode replyLikeRow = rowOfType(list, "REPLY_LIKE");
        assertThat(replyLikeRow.path("replyId").asLong()).isEqualTo(myReplyOnHis);
        assertThat(replyLikeRow.path("replyContent").asText()).isEqualTo("我回他的");
        assertThat(replyLikeRow.path("subjectId").asInt())
                .as("三种类型都带 review_id, 点一行就能跳回那部番").isEqualTo(SUBJECT_TYPES);

        /* 只打分不写字的评论: 摘要是 null(前端据此显示「（无文字）」), 而不是空串。
           评论必须是**我的**、回复必须是他发的, 否则收件人就不是我了 —— 自己回自己
           那条路根本不写通知, 这条断言会变成在看一个空列表。 */
        long silent = writeReview(meToken, SUBJECT_TYPES_BLANK, "");
        reply(actorToken, silent, "他有话说");

        JsonNode newest = notifications(meToken).path("list").get(0);
        assertThat(newest.path("reviewId").asLong()).as("最新的一条在最上面").isEqualTo(silent);
        assertThat(newest.path("reviewContent").isNull())
                .as("空串会让前端的判断变成'有内容但看不见'").isTrue();
        assertThat(newest.path("replyContent").asText())
                .as("前提: 那条回复真的发出去了").isEqualTo("他有话说");
    }

    private static JsonNode rowOfType(JsonNode list, String type) {
        for (JsonNode row : list) {
            if (type.equals(row.path("type").asText())) {
                return row;
            }
        }
        throw new AssertionError("列表里没有 " + type + " 这一类: " + list);
    }

    /**
     * 一条通知都没有时是空列表 + total 0, 不是错误。
     *
     * <p>与旧端点同一条: 个人页在没收到过任何互动时也要正常渲染。
     */
    @Test
    @DisplayName("一条都没有: 空列表 + total 0, 不是 404 也不是报错")
    void anEmptyInboxIsNotAnError() throws Exception {
        JsonNode data = notifications(actorToken);

        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("list").size()).isZero();
        assertThat(unreadCount(actorToken)).isZero();
    }

    // ========== 自己对自己做的事不通知 ==========

    /**
     * <b>自己回复自己的评论、赞自己的评论、赞自己的回复, 都不该收到通知。</b>
     *
     * <p>与旧端点那条"只列别人回复我的"是同一条规矩, 只是现在它同时覆盖另外两类。
     * 不只是噪音: 那个红点会永远点不掉 —— 它只在查看时被清掉, 而下一次自赞又亮起来。
     */
    @Test
    @DisplayName("自回 / 自赞都不产生通知: 三个动作做完, 收件箱仍然是空的")
    void selfActionsAreNotNotified() throws Exception {
        long reviewId = writeReview(meToken, SUBJECT_SELF, "我自己的评论");
        long replyId = reply(meToken, reviewId, "我自己回一句");
        likeReview(meToken, reviewId);
        likeReply(meToken, replyId);

        assertThat(notifications(meToken).path("total").asLong())
                .as("三次自互动一条都不该记").isZero();
        assertThat(unreadCount(meToken)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .as("库里也不该有行 —— 上面那条断言可能是被别的过滤条件挡出来的")
                .isZero();
    }

    // ========== 已读 ==========

    @Test
    @DisplayName("未读计数: 进来是 N, 标记已读之后归零, 再标一次仍然是 0")
    void readingClearsTheCounter() throws Exception {
        long reviewId = writeReview(meToken, SUBJECT_GUARDS, "两条互动");
        reply(actorToken, reviewId, "一");
        likeReview(actorToken, reviewId);

        assertThat(unreadCount(meToken)).isEqualTo(2L);

        markRead(meToken);
        assertThat(unreadCount(meToken)).isZero();
        // 幂等: 再标一次不报错, 数字也不变
        markRead(meToken);
        assertThat(unreadCount(meToken)).isZero();

        // 列表仍然读得出来, 只是都成了已读 —— 已读不清空内容, 那是"删"才做的事
        JsonNode list = notifications(meToken).path("list");
        assertThat(list.size()).isEqualTo(2);
        for (JsonNode row : list) {
            assertThat(row.path("read").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("标记已读只动自己的: 另一个人的未读不受影响")
    void markingReadDoesNotTouchOthers() throws Exception {
        long mine = writeReview(meToken, SUBJECT_GUARDS + 1, "我的");
        long his = writeReview(actorToken, SUBJECT_GUARDS + 2, "他的");
        reply(actorToken, mine, "回我");
        reply(meToken, his, "回他");
        assertThat(unreadCount(meToken)).isEqualTo(1L);
        assertThat(unreadCount(actorToken)).isEqualTo(1L);

        markRead(meToken);

        assertThat(unreadCount(meToken)).isZero();
        assertThat(unreadCount(actorToken)).as("他的未读不能被我清掉").isEqualTo(1L);
    }

    // ========== 分页与校验 ==========

    @Test
    @DisplayName("分页: 信封是 {list,total,page}, 翻页不重不漏, 越界页报真实 total")
    void pagingEnvelope() throws Exception {
        long reviewId = writeReview(meToken, SUBJECT_PAGING, "分页用");
        for (int i = 0; i < 3; i++) {
            reply(actorToken, reviewId, "第 " + i + " 条");
        }

        JsonNode first = notifications(meToken, "page", "1", "limit", "2");
        assertThat(first.path("total").asLong()).isEqualTo(3L);
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(first.path("list").size()).isEqualTo(2);

        JsonNode second = notifications(meToken, "page", "2", "limit", "2");
        assertThat(second.path("total").asLong()).as("每一页都报同一个 total").isEqualTo(3L);
        assertThat(second.path("list").size()).isEqualTo(1);
        assertThat(second.path("list").get(0).path("id").asLong())
                .as("与第 1 页不重复")
                .isNotEqualTo(first.path("list").get(0).path("id").asLong());

        JsonNode beyond = notifications(meToken, "page", "99", "limit", "2");
        assertThat(beyond.path("list").size()).isZero();
        assertThat(beyond.path("total").asLong())
                .as("越界页报真实 total —— 报 0 的话翻页控件会自己消失").isEqualTo(3L);
    }

    /** 分页参数由接口这一层挡下 400, 而不是让 0 或 51 悄悄进到查询里 */
    @Test
    @DisplayName("分页参数越界: 400, 不是被悄悄夹掉")
    void pagingParamsAreValidated() throws Exception {
        for (String[] bad : new String[][]{
                {"page", "0"}, {"page", "-1"}, {"limit", "0"}, {"limit", "51"}}) {
            mockMvc.perform(get("/api/user/notifications")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken)
                            .param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== 级联 ==========

    /**
     * 删掉一条评论之后, 它的通知从我的收件箱里消失 —— <b>读路径不做任何过滤, 全靠库级联</b>。
     *
     * <p>这条走的是网页那条删除路径({@code DELETE /api/review/{id}}), 与
     * {@code ReviewReplyService} 里那句"删回复靠级联"是同一个形状的验证: 少配一条
     * 外键, 要么这一句 DELETE 报错, 要么列表里留下一条点进去什么都没有的通知。
     */
    @Test
    @DisplayName("删掉评论: 它下面的三类通知一起从收件箱消失")
    void deletingAReviewClearsTheInbox() throws Exception {
        long reviewId = writeReview(meToken, SUBJECT_CASCADE, "会被删掉的评论");
        long replyId = reply(meToken, reviewId, "我自己的回复");
        reply(actorToken, reviewId, "他回的");
        likeReview(actorToken, reviewId);
        likeReply(actorToken, replyId);
        assertThat(notifications(meToken).path("total").asLong())
                .as("先确认三类真在, 否则下面那条断言是空过").isEqualTo(3L);

        mockMvc.perform(delete("/api/review/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken))
                .andExpect(status().isOk());

        assertThat(notifications(meToken).path("total").asLong()).isZero();
        assertThat(unreadCount(meToken))
                .as("被级联带走的未读不能继续占着红点").isZero();
    }
}

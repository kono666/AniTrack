package com.animetracker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 举报这条线的对外契约: 提交({@code POST /api/review/{id}/report})、看明细
 * ({@code GET /api/admin/reviews/{id}/reports})、忽略
 * ({@code PUT /api/admin/reports/{id}/dismiss})。
 *
 * <p>这一层才拦得住的三类错, 全都是"接口返回 200"的:
 *
 * <ul>
 *   <li><b>权限跨在过滤器链上</b> —— 举报是写路径, 匿名必须 401。它<b>不在</b>
 *       {@code SecurityConfig} 的公开清单里, 靠 {@code anyRequest().authenticated()}
 *       兜着; 哪天有人"顺手"把它加进那份只认 GET 的名单, 只有这里会红。
 *       管理侧两条反过来: 非管理员 403;</li>
 *   <li><b>DTO 上的校验注解有没有被静默忽略</b> —— {@code @Valid} 掉了的话
 *       {@code @NotBlank}/{@code @Size} 一次都不跑。理由那一条 service 里还有第二道
 *       兜着, 而 {@code detail} 的长度只有 DTO 那一层拦得住;</li>
 *   <li><b>query/body 参数真的接到了 service 上</b> —— 一个把 {@code reason} 丢在地上
 *       的 controller 能通过所有 service 层用例。</li>
 * </ul>
 *
 * <p>库是独立的内存库({@code anitrack-review-report}), 且关掉了启动预加载(否则这个
 * 类会去调 api.bgm.tv, 让一组本地用例依赖外网)。数据落在 {@code SUBJECT_BASE} 起的一段
 * id 上, 每条用例开头清干净。
 *
 * <p>四个 token 各<b>整个类共用一次</b>: 登录限流是 10 次/分钟/IP, 逐用例登录会在第 11 个
 * 用例上拿到 429, 而失败信息是"登录失败", 与被测接口毫无关系。
 *
 * <p>用 MockMvc: 要验的是状态码与响应体, 不涉及连接器行为。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-review-report;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReviewReportIntegrationTest {

    /** Bangumi 不会给出的 id 段: 断言只在自己造的这一段上做, 不受任何真实数据影响 */
    private static final int SUBJECT_BASE = 97100000;

    /** 灌进去的时间基准. 取未来是为了与 dev 初始化那两个账号的真实注册时间错开 */
    private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 1, 0, 0);

    /** 种子评论的作者. 举报他这条评论的是下面两个"别人" */
    private static final String AUTHOR = "u_report_author";

    /** 举报人一 —— 大多数用例用它 */
    private static final String REPORTER = "u_report_reporter";

    /**
     * 举报人二。<b>非有不可</b>: {@code (review_id, reporter_id)} 上有唯一约束,
     * 于是"两个不同的人举报同一条评论"这个场景只有换人才造得出来, 而它恰好是
     * 「唯一约束有没有写宽」的唯一哨兵。
     */
    private static final String OTHER = "u_report_other";

    private static final String PASSWORD = "passw0rd123";

    /** 一条绝不存在的评论 id —— 404 那几条用例用它 */
    private static final long MISSING_REVIEW = 999999999L;

    /** 同上, 举报那一侧 */
    private static final long MISSING_REPORT = 999999999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private static String adminToken;
    private static String authorToken;
    private static String reporterToken;
    private static String otherToken;

    @BeforeEach
    void seedAndLogin() throws Exception {
        // 类里所有用例共用同一个 Spring 上下文, 也就共用同一个库 —— 先清掉上一轮灌的。
        // review_report 挂在 review 上有 ON DELETE CASCADE, 所以删 review 就把它一起
        // 带走了, 不需要单独一条 DELETE(那也正好是 migration 测试验过的那条级联)。
        jdbc.update("DELETE FROM review WHERE subject_id BETWEEN ? AND ?",
                SUBJECT_BASE, SUBJECT_BASE + 99);

        ensureUser(AUTHOR);
        ensureUser(REPORTER);
        ensureUser(OTHER);
        if (adminToken == null) {
            adminToken = login("admin", "admin123");
        }
        if (authorToken == null) {
            authorToken = login(AUTHOR, PASSWORD);
        }
        if (reporterToken == null) {
            reporterToken = login(REPORTER, PASSWORD);
        }
        if (otherToken == null) {
            otherToken = login(OTHER, PASSWORD);
        }

        // 一条由 AUTHOR 写的评论: 举报它的是 REPORTER 与 OTHER, 于是「不能举报自己的
        // 评论」那条规则有一个真的反例可用(见 reportsOnYourOwnReviewAreRejected)
        jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, like_count,"
                        + " reply_count, created_at) VALUES (?, ?, 8, '被举报的那条', 0, 0, ?)",
                userIdOf(AUTHOR), SUBJECT_BASE, Timestamp.valueOf(BASE));
    }

    // ==================== 提交 ====================

    /**
     * 匿名举报 → 401。
     *
     * <p>这条路刻意<b>不在</b> {@code SecurityConfig} 的公开清单里 —— 举报是写, 匿名举报
     * 既没有意义(没有人能复核)也是一个现成的灌水入口。它靠
     * {@code anyRequest().authenticated()} 兜着, 所以这条用例守的其实是"没人往那份
     * 只认 GET 的名单里加东西"。
     */
    @Test
    @DisplayName("匿名举报 → 401(举报是写路径, 不在公开清单里)")
    void anonymousCannotReport() throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId() + "/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("SPAM", null)))
                .andExpect(status().isUnauthorized());

        assertThat(countReports()).as("被拒的那次不该留下任何东西").isZero();
    }

    @Test
    @DisplayName("举报成功 → 200 + {reported:true, duplicate:false}")
    void reportingSucceeds() throws Exception {
        JsonNode data = dataOf(report(reporterToken, reviewId(), reportBody("SPAM", "这是广告")));

        assertThat(data.path("reported").asBoolean()).isTrue();
        assertThat(data.path("duplicate").asBoolean()).isFalse();
        assertThat(countReports()).isEqualTo(1);
    }

    /**
     * 重复举报: <b>200 + {@code duplicate=true}</b>, 不是 409 也不是 500。
     *
     * <p>这一条同时是"唯一约束真的在挡"的哨兵: 没有那条约束, 第二次会安安稳稳插进去,
     * 于是 {@code duplicate} 永远是 false、{@code countReports()} 变成 2 —— 而接口
     * 照样 200。所以这里连库里的行数一起断, 只断响应体是拦不住的。
     */
    @Test
    @DisplayName("重复举报 → 200 + duplicate=true, 库里仍然只有一行")
    void aSecondReportFromTheSamePersonIsIdempotent() throws Exception {
        long reviewId = reviewId();
        report(reporterToken, reviewId, reportBody("SPAM", null));

        JsonNode data = dataOf(report(reporterToken, reviewId, reportBody("ABUSE", null)));

        assertThat(data.path("reported").asBoolean()).isTrue();
        assertThat(data.path("duplicate").asBoolean())
                .as("第二次举报要能被认出来, 前端才能说「你已经举报过这条评论」")
                .isTrue();
        assertThat(countReports())
                .as("唯一约束没在挡的话这里会是 2 —— 而响应体照样是 200")
                .isEqualTo(1);
    }

    /**
     * 换一个人举报同一条评论照常成功 —— 上一条的反例。
     *
     * <p>少了它, 一个把唯一约束写成只按 {@code review_id}(漏掉 {@code reporter_id})
     * 的实现会让上面那条用例照样绿, 而所有人的第二条举报都被静默吞掉。
     */
    @Test
    @DisplayName("换一个人举报同一条评论: 成功, 而且是 duplicate=false")
    void aDifferentPersonCanReportTheSameReview() throws Exception {
        long reviewId = reviewId();
        report(reporterToken, reviewId, reportBody("SPAM", null));

        JsonNode data = dataOf(report(otherToken, reviewId, reportBody("ABUSE", null)));

        assertThat(data.path("duplicate").asBoolean()).isFalse();
        assertThat(countReports()).isEqualTo(2);
    }

    @Test
    @DisplayName("举报自己的评论 → 400")
    void reportsOnYourOwnReviewAreRejected() throws Exception {
        // 这条评论的作者就是 AUTHOR, 让他用自己的 token 举报它
        mockMvc.perform(post("/api/review/" + reviewId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("SPAM", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能举报自己的评论"));

        assertThat(countReports()).isZero();
    }

    @Test
    @DisplayName("举报一条不存在的评论 → 404")
    void reportingAMissingReviewIsNotFound() throws Exception {
        mockMvc.perform(post("/api/review/" + MISSING_REVIEW + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("SPAM", null)))
                .andExpect(status().isNotFound());
    }

    /**
     * 空理由与不在白名单里的理由都是 400, 且一条都不写。
     *
     * <p>两者分别由两道不同的闸拦下来: 空白走 {@code @NotBlank}(DTO 那一层), 未知值走
     * service 的白名单。这里只断状态码, 不断 message —— 两道给的文案本来就不同,
     * 混在一起断反而会让用例在"是哪一道在拦"上撒谎。要分清楚的话看
     * {@code ReviewReportServiceTest}, 那里连"一次库都没碰"一起断。
     */
    @ParameterizedTest(name = "reason=<{0}>")
    @ValueSource(strings = {"", "   ", "BOGUS"})
    @DisplayName("理由为空或不在白名单里 → 400, 且一条都不写")
    void badReasonsAreRejected(String reason) throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody(reason, null)))
                .andExpect(status().isBadRequest());

        assertThat(countReports()).isZero();
    }

    /**
     * 理由的大小写不敏感 —— {@code spam} 与 {@code SPAM} 是同一件事。
     *
     * <p>接口是公开的, 而为大小写回一个 400 是在惩罚一个没有歧义的输入。落库的一定是
     * 大写那个: 管理端按 {@code reason} 精确筛(读路径对未知值沉默放行), 存成小写不会
     * 报错, 只会让这条举报**永远筛不出来**。
     */
    @Test
    @DisplayName("理由大小写不敏感, 落库的是大写那个")
    void theReasonIsCaseInsensitiveAndStoredUppercase() throws Exception {
        report(reporterToken, reviewId(), reportBody("spam", null));

        assertThat(jdbc.queryForObject("SELECT reason FROM review_report", String.class))
                .as("存成小写的话, 管理端按理由精确筛就永远找不到它")
                .isEqualTo("SPAM");
    }

    /**
     * 补充说明超长 → 400。<b>这一条只有 DTO 上的 {@code @Size} 拦得住</b> ——
     * service 里那道是"第二道", 请求到不了 controller 就无从谈起。
     */
    @Test
    @DisplayName("补充说明超过 500 字 → 400, 且一条都不写")
    void anOverlongDetailIsRejected() throws Exception {
        mockMvc.perform(post("/api/review/" + reviewId() + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("SPAM", "x".repeat(501))))
                .andExpect(status().isBadRequest());

        assertThat(countReports()).isZero();
    }

    // ==================== 明细 ====================

    @Test
    @DisplayName("明细: 非管理员 403, 匿名 401 —— 两者不是同一件事")
    void onlyAdminsCanReadTheDetails() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/" + reviewId() + "/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporterToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/reviews/" + reviewId() + "/reports"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 明细的形状: {@code {list, total}}, 行里八个键都在, 未处理时处理人/处理时间是 null。
     *
     * <p>{@code handlerName} 与 {@code handledAt} 在未处理时<b>都要在</b>(值为 null)——
     * 与 {@code animeTitle} 同一条理由: 少一个键与"这个值恰好为空"在界面上长得一样,
     * 而前端那一格会走 undefined 分支。
     *
     * <p>两条举报刻意来自<b>两个人</b>、理由不同, 且<b>最新的在前</b>: 顺序反了的话,
     * 面板上管理员先看到的是最旧的那条。
     */
    @Test
    @DisplayName("明细: {list,total} 信封, 最新的在前, 未处理时 handlerName/handledAt 是 null")
    void detailsCarryEveryKey() throws Exception {
        long reviewId = reviewId();
        report(reporterToken, reviewId, reportBody("SPAM", "第一条"));
        report(otherToken, reviewId, reportBody("ABUSE", "第二条"));

        JsonNode data = dataOf(get("/api/admin/reviews/" + reviewId + "/reports"), adminToken);

        assertThat(data.path("total").asInt()).isEqualTo(2);
        JsonNode list = data.path("list");
        assertThat(list).hasSize(2);

        JsonNode newest = list.get(0);
        assertThat(newest.path("reason").asText()).as("最新的在前").isEqualTo("ABUSE");
        assertThat(newest.path("detail").asText()).isEqualTo("第二条");
        assertThat(newest.path("reporterName").asText()).isEqualTo(OTHER);
        assertThat(newest.path("status").asText()).isEqualTo("PENDING");
        assertThat(newest.path("handlerName").isNull())
                .as("键在、值为 null —— 少了键的话前端那一格走的是 undefined 分支")
                .isTrue();
        assertThat(newest.path("handledAt").isNull()).isTrue();
        assertThat(newest.has("createdAt")).isTrue();

        assertThat(list.get(1).path("reason").asText()).isEqualTo("SPAM");
    }

    @Test
    @DisplayName("明细: 评论不存在 → 404, 不是空列表")
    void detailsOfAMissingReviewAreNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/reviews/" + MISSING_REVIEW + "/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("明细: 没有人举报过 → 200 + 空列表 + total 0(与 404 不是同一件事)")
    void detailsOfAnUnreportedReviewAreEmptyNotMissing() throws Exception {
        JsonNode data = dataOf(get("/api/admin/reviews/" + reviewId() + "/reports"), adminToken);

        assertThat(data.path("total").asInt()).isZero();
        assertThat(data.path("list")).isEmpty();
    }

    // ==================== 忽略 ====================

    /**
     * 忽略: 回 {@code {status: DISMISSED}}, 库里三个字段一起写上。
     *
     * <p>{@code status} 由服务端给而不是让前端自己猜 —— 与点赞那条路的
     * {@code likeCount} 同一个取舍: 前端猜的话, 它和服务端对"忽略之后是什么状态"
     * 就有了两处定义。
     */
    @Test
    @DisplayName("忽略 → 200 + {status:DISMISSED}, 处理人与处理时间一起落库")
    void dismissingWritesAllThreeFields() throws Exception {
        long reportId = reportAndReturnId(reporterToken, reviewId(), reportBody("SPAM", null));

        JsonNode data = dataOf(put("/api/admin/reports/" + reportId + "/dismiss"), adminToken);

        assertThat(data.path("status").asText()).isEqualTo("DISMISSED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review_report WHERE id = ?", String.class, reportId))
                .isEqualTo("DISMISSED");
        assertThat(jdbc.queryForObject(
                "SELECT handled_by FROM review_report WHERE id = ?", Long.class, reportId))
                .as("处理人必须落库 —— 只剩一个 status 的话, 面板说不了「谁处理的」")
                .isEqualTo(userIdOf("admin"));
        assertThat(jdbc.queryForObject(
                "SELECT handled_at FROM review_report WHERE id = ?", Timestamp.class, reportId))
                .isNotNull();
    }

    /**
     * 再忽略一次: 仍然 200, 而 {@code handled_by} / {@code handled_at} <b>不被覆盖</b>。
     *
     * <p>只断状态是拦不住覆盖的 —— 覆盖之后状态照样是 DISMISSED, 而"谁在什么时候处理的"
     * 变成了一个会漂移的答案。所以先把那一行改成"已被另一个人处理过"(裸 SQL), 再忽略
     * 一次, 看它有没有被改回去: 这样"覆盖与否"在断言里就是一个能看见的差异。
     */
    @Test
    @DisplayName("再忽略一次: 200, 状态不变, 已有的处理人与处理时间不被覆盖")
    void dismissingTwiceKeepsTheOriginalHandler() throws Exception {
        long reportId = reportAndReturnId(reporterToken, reviewId(), reportBody("SPAM", null));
        LocalDateTime original = LocalDateTime.of(2026, 3, 4, 5, 6);
        jdbc.update("UPDATE review_report SET status = 'DISMISSED', handled_by = ?, handled_at = ?"
                + " WHERE id = ?", userIdOf(REPORTER), Timestamp.valueOf(original), reportId);

        JsonNode data = dataOf(put("/api/admin/reports/" + reportId + "/dismiss"), adminToken);

        assertThat(data.path("status").asText()).isEqualTo("DISMISSED");
        assertThat(jdbc.queryForObject(
                "SELECT handled_by FROM review_report WHERE id = ?", Long.class, reportId))
                .as("被覆盖的话, 每多点一次忽略这条记录就换一个人")
                .isEqualTo(userIdOf(REPORTER));
        assertThat(jdbc.queryForObject(
                "SELECT handled_at FROM review_report WHERE id = ?", Timestamp.class, reportId))
                .isEqualTo(Timestamp.valueOf(original));
    }

    @Test
    @DisplayName("忽略: 非管理员 403, 匿名 401")
    void onlyAdminsCanDismiss() throws Exception {
        long reportId = reportAndReturnId(reporterToken, reviewId(), reportBody("SPAM", null));

        mockMvc.perform(put("/api/admin/reports/" + reportId + "/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reporterToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/reports/" + reportId + "/dismiss"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM review_report WHERE id = ?", String.class, reportId))
                .as("被拒的那两次都不该改动那一行")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("忽略一条不存在的举报 → 404")
    void dismissingAMissingReportIsNotFound() throws Exception {
        mockMvc.perform(put("/api/admin/reports/" + MISSING_REPORT + "/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    // ==================== 工具 ====================

    private String reportBody(String reason, String detail) {
        String detailPart = detail == null ? "" : ",\"detail\":\"" + detail + "\"";
        return "{\"reason\":\"" + reason + "\"" + detailPart + "}";
    }

    /** 发一次举报并钉住 200 + code 200 —— 大多数用例都从"举报成功了"这一步开始 */
    private ResultActions report(String token, long reviewId, String body) throws Exception {
        return mockMvc.perform(post("/api/review/" + reviewId + "/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private long reportAndReturnId(String token, long reviewId, String body) throws Exception {
        report(token, reviewId, body);
        Long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM review_report WHERE review_id = ?", Long.class, reviewId);
        assertThat(id).as("举报行应当刚写进去").isNotNull();
        return id;
    }

    /** 取出 {@code data} 节点(顺带钉住 200 + code 200) */
    private JsonNode dataOf(ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    /** 带一个 token 发一次请求并取出 {@code data} */
    private JsonNode dataOf(MockHttpServletRequestBuilder request, String token) throws Exception {
        return dataOf(mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
    }

    private long reviewId() {
        Long id = jdbc.queryForObject(
                "SELECT id FROM review WHERE subject_id = ?", Long.class, SUBJECT_BASE);
        assertThat(id).as("种子评论应当刚灌进去").isNotNull();
        return id;
    }

    private int countReports() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM review_report", Integer.class);
        return n == null ? 0 : n;
    }

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("SELECT id FROM \"user\" WHERE username = ?", Long.class, username);
        assertThat(id).as("账号 <%s> 应当存在", username).isNotNull();
        return id;
    }

    /** 注册只做一次(限流 5 次/分钟), 之后的用例只复用 token */
    private void ensureUser(String username) throws Exception {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"user\" WHERE username = ?", Integer.class, username);
        if (existing != null && existing > 0) {
            return;
        }
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }
}

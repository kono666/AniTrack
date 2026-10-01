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
 * {@code GET /api/admin/actions} 的对外契约, 以及**账本真的被写下来了**这件事。
 *
 * <p><b>为什么这一组必须走 HTTP, 而 {@code AdminServiceTest} 里的 mock 桩不够。</b>
 * 那边把仓储换成了 mock, 于是它问到的是"服务层有没有调用 save"; 而这条链上还有三样
 * 东西在 mock 后面: 事务边界({@code IsolatedInsert} 的代理)是不是真的把动作与记账
 * 圈在了一起、{@code @Min}/{@code @Max} 有没有因为类上少了 {@code @Validated} 而被
 * 静默忽略、以及四个动作**经由真实入口**走完之后库里到底留下了什么。
 *
 * <p><b>数据是自己灌的。</b> dev 只有 admin / test 两个账号, 而这里每个用例都要一个
 * "可以被动"的普通用户与一条可删的评论 —— 拿真账号当靶子会让用例之间互相干扰
 * (封一次就再也封不出 USER_BAN 了)。
 *
 * <p><b>为什么每条断言都从 {@code GET /api/admin/actions} 读, 而不是直接查库。</b>
 * 直接查库只能证明"有行", 证明不了它**读得出来** —— 而那正是这一页存在的全部意义。
 * 只有"插入"那一层需要直接查库(见 {@code failedActionLeavesNoRow}: 那条要断言的是
 * 没有任何行, 而空结果在两种读法下长得一样)。
 *
 * <p>登录按 IP 限流(10 次/分钟), 所以两个 token 都是整个类共用一次的静态量。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-action-log;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminActionLogIntegrationTest {

    /** 灌进去的用户名前缀. 清理时用 {@code al%} —— `_` 在 LIKE 里是通配符, 能用就别用 */
    private static final String PREFIX = "al_";

    /** 灌进去的评论挂在那个作品下, 清理时按它删 —— 先删评论再删用户, 否则撞 review 的外键 */
    private static final int SUBJECT_ID = 96000301;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private static String adminToken;
    private static String userToken;

    @BeforeEach
    void seedAndLogin() throws Exception {
        // 账本没有外键, 所以清它不必排在别人后面; 但 review 指着 user, 顺序不能反
        jdbc.update("DELETE FROM admin_action_log");
        jdbc.update("DELETE FROM review WHERE subject_id = " + SUBJECT_ID);
        jdbc.update("DELETE FROM \"user\" WHERE username LIKE 'al%'");

        if (adminToken == null) {
            adminToken = login("admin", "admin123");
        }
        if (userToken == null) {
            userToken = login("test", "test123");
        }
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** 灌一个普通用户, 返回 id */
    private long seedUser(String username, String status) {
        jdbc.update("INSERT INTO \"user\" (username, password, email, role, status, created_at) "
                        + "VALUES (?, ?, ?, 'USER', ?, ?)",
                username, "$2a$10$thisIsNotAValidHashButTheColumnOnlyNeedsChars",
                username + "@example.com", status, Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT id FROM \"user\" WHERE username = ?", Long.class, username);
    }

    /** 灌一条评论, 返回 id */
    private long seedReview(long authorId, String content) {
        jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                        + "VALUES (?, ?, 8, ?, ?)",
                authorId, SUBJECT_ID, content, Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject(
                "SELECT id FROM review WHERE subject_id = ?", Long.class, SUBJECT_ID);
    }

    /** 发一次请求(带管理员 token). 参数按 key,value 成对给 */
    private ResultActions request(MockHttpServletRequestBuilder builder, String... params)
            throws Exception {
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));
    }

    /** 取操作日志并拿出 {@code data} 节点(顺带钉住 200 + code 200) */
    private JsonNode actions(String... params) throws Exception {
        String body = request(get("/api/admin/actions"), params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private static List<String> actionsOf(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.path("list").forEach(row -> out.add(row.path("action").asText()));
        return out;
    }

    /** 库里实际有几条账 —— 只在"应该一条都没有"那类断言里用 */
    private int ledgerRowsInDb() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log", Integer.class);
    }

    // ========== 四个动作各留一条 ==========

    /**
     * 四个动作走一遍, 四条账都得在, 而且 {@code action} 的值各不相同。
     *
     * <p>这一条是整组的地基: 上面四个接口在加账本之前就已经能用了, 所以"动作成功"这件事
     * 一点也不能说明账本被写了 —— 它只会在事后有人翻账本时才发现是空的。
     */
    @Test
    @DisplayName("封禁 / 解封 / 改角色 / 解锁 / 删评论: 五条账, 五个不同的 action")
    void everyDestructiveActionLeavesARow() throws Exception {
        long target = seedUser(PREFIX + "target", "ACTIVE");
        long author = seedUser(PREFIX + "author", "ACTIVE");
        long reviewId = seedReview(author, "这条会被管理员删掉");

        request(put("/api/admin/users/" + target + "/toggle")).andExpect(status().isOk());
        request(put("/api/admin/users/" + target + "/toggle")).andExpect(status().isOk());
        request(put("/api/admin/users/" + target + "/role"), "role", "ADMIN")
                .andExpect(status().isOk());
        request(put("/api/admin/users/" + target + "/unlock")).andExpect(status().isOk());
        request(delete("/api/admin/reviews/" + reviewId)).andExpect(status().isOk());

        JsonNode data = actions();
        assertThat(data.path("total").asInt()).isEqualTo(5);
        // 最新的在最前: 删评论是最后做的
        assertThat(actionsOf(data)).containsExactly(
                "REVIEW_DELETE", "USER_UNLOCK", "USER_ROLE", "USER_UNBAN", "USER_BAN");
    }

    /**
     * 行的七个键与内容。
     *
     * <p>{@code actorName} 与 {@code detail} 是这一页**唯一可读**的两样东西 ——
     * {@code targetId} 是原样的 id(账本不回表查名字, 那会推翻它"不依赖目标还在不在"的前提),
     * 所以把目标说清楚的责任全在 detail 上, 而 detail 是写的时候拼的。
     */
    @Test
    @DisplayName("行里是 {id,action,actorName,targetType,targetId,detail,createdAt}, detail 是人话")
    void rowShape() throws Exception {
        long target = seedUser(PREFIX + "bob", "ACTIVE");

        request(put("/api/admin/users/" + target + "/toggle")).andExpect(status().isOk());

        JsonNode row = actions().path("list").get(0);
        List<String> keys = new ArrayList<>();
        row.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("id", "action", "actorName", "targetType",
                "targetId", "detail", "createdAt");

        assertThat(row.path("action").asText()).isEqualTo("USER_BAN");
        assertThat(row.path("actorName").asText()).isEqualTo("admin");
        assertThat(row.path("targetType").asText()).isEqualTo("USER");
        assertThat(row.path("targetId").asLong()).isEqualTo(target);
        // 写的是**用户名快照**, 不是 id —— 账本要活到那个账号改名或消失之后
        assertThat(row.path("detail").asText()).isEqualTo("禁用用户 " + PREFIX + "bob");
    }

    /** 删评论的账要带上作者与正文摘要, 否则事后只知道"删了一条评论" */
    @Test
    @DisplayName("删评论的 detail 里有作者名、作品 id 与正文摘要")
    void reviewDeletionRecordsWhatWasDeleted() throws Exception {
        long author = seedUser(PREFIX + "carol", "ACTIVE");
        long reviewId = seedReview(author, "这动画不错");

        request(delete("/api/admin/reviews/" + reviewId)).andExpect(status().isOk());

        JsonNode row = actions().path("list").get(0);
        assertThat(row.path("action").asText()).isEqualTo("REVIEW_DELETE");
        assertThat(row.path("targetType").asText()).isEqualTo("REVIEW");
        assertThat(row.path("detail").asText())
                .isEqualTo("移除用户 " + PREFIX + "carol 在作品 " + SUBJECT_ID + " 下的评论：这动画不错");
    }

    // ========== 筛选与分页 ==========

    /**
     * {@code action} 是精确筛, 认不出来的当**不筛**(而不是 400) —— 与用户列表对未知
     * role/status 的口径一致: 手改过的 URL 不该把页面变成一个错误屏。
     */
    @Test
    @DisplayName("action 精确筛; 认不出来的值当作不筛, 两个都返回 200")
    void actionFilter() throws Exception {
        long target = seedUser(PREFIX + "target", "ACTIVE");
        request(put("/api/admin/users/" + target + "/toggle")).andExpect(status().isOk());
        request(put("/api/admin/users/" + target + "/unlock")).andExpect(status().isOk());

        JsonNode banned = actions("action", "USER_BAN");
        assertThat(banned.path("total").asInt()).isEqualTo(1);
        assertThat(actionsOf(banned)).containsExactly("USER_BAN");

        JsonNode bogus = actions("action", "BOGUS");
        assertThat(bogus.path("total").asInt()).isEqualTo(2);
    }

    /**
     * 账本仓储多了「按 target 筛」两个谓词之后, <b>这个接口的行为必须一个字都不变</b>。
     *
     * <p>谓词写的是 {@code :targetType IS NULL OR l.targetType = :targetType}, 而
     * {@code getActionPage} 恒传 null, 于是两个分支短路成真。将来谁把 {@code IS NULL OR}
     * 去掉、只留 {@code AND l.targetType = :targetType}, 这个接口会**静默变成空页**:
     * 200、没有异常、{@code total} 是 0 —— 而 {@code AdminServiceTest} 里那一组用的是
     * {@code any()} 桩, 参数本身不参与匹配, 它们会全绿。
     *
     * <p>所以这条必须走 HTTP: 只有把真语句交给 H2 编译、把真数据对着看, 才看得见那个
     * 「一条都没有」。
     */
    @Test
    @DisplayName("不带 target 参数时仍返回全部账, 不是空页")
    void listingIsUnaffectedByTheNewTargetPredicates() throws Exception {
        long target = seedUser(PREFIX + "target", "ACTIVE");
        request(put("/api/admin/users/" + target + "/toggle")).andExpect(status().isOk());
        request(put("/api/admin/users/" + target + "/unlock")).andExpect(status().isOk());

        JsonNode all = actions();

        assertThat(all.path("total").asInt()).isEqualTo(2);
        assertThat(actionsOf(all)).containsExactlyInAnyOrder("USER_BAN", "USER_UNLOCK");
    }

    /**
     * 越界页: 200 + 空 list + **真实 total**。
     *
     * <p>total 报 0 是最容易写出来的错(取页返回空, 顺手拿 {@code list.size()} 当总数),
     * 而前端按 {@code ceil(total/limit)} 画翻页控件 —— 少报总数等于让用户从最后一页
     * 往回点不回去。
     */
    @Test
    @DisplayName("page=99999 仍是 200, list 空, total 报真实的 1")
    void outOfRangePageStillReportsTheRealTotal() throws Exception {
        long target = seedUser(PREFIX + "target", "ACTIVE");
        request(put("/api/admin/users/" + target + "/toggle")).andExpect(status().isOk());

        JsonNode data = actions("page", "99999", "limit", "20");

        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asInt()).isEqualTo(1);
        assertThat(data.path("page").asInt()).isEqualTo(99999);
    }

    @Test
    @DisplayName("data 是 {list,total,page} 信封, 不是裸数组")
    void responseIsAPagedEnvelope() throws Exception {
        JsonNode data = actions();

        assertThat(data.path("list").isArray()).isTrue();
        assertThat(data.path("total").asInt()).isZero();
        assertThat(data.path("page").asInt()).isEqualTo(1);
    }

    /**
     * 类上的 {@code @Validated} 就是这条用例存在的全部理由。
     *
     * <p>没有它, 参数上的 {@code @Min}/{@code @Max} 被静默忽略: {@code limit=100000}
     * 会真的去拉十万行账 —— 而账本是**只增不减**的一张表, 它只会越来越大。
     */
    @Test
    @DisplayName("page / limit 越界是 400, 不是被静默夹取")
    void pageAndLimitBoundsAreRejected() throws Exception {
        request(get("/api/admin/actions"), "page", "0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("页码从 1 开始"));
        request(get("/api/admin/actions"), "limit", "101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页最多 100 条"));

        // 边界本身合法 —— 否则一个"永远 400"的实现也能过上面两条
        request(get("/api/admin/actions"), "limit", "100").andExpect(status().isOk());
    }

    // ========== 失败的动作不留账 ==========

    /**
     * 动作失败时**一条账都不该有**。
     *
     * <p>「账本里出现一条『封了一个不存在的用户』」不会让任何东西报错, 但它是账本可信度的
     * 全部来源 —— 一条记录不实的账, 让整本账都失去了被相信的资格。四种失败路径各来一次:
     * 目标不存在(404)、目标是不许动的人(400)、目标类型不对(404)。
     */
    @Test
    @DisplayName("动作失败(404/400)时账本里一条都没有")
    void failedActionsLeaveNoRow() throws Exception {
        long admin = jdbc.queryForObject(
                "SELECT id FROM \"user\" WHERE username = 'admin'", Long.class);

        request(put("/api/admin/users/999999/toggle")).andExpect(status().isNotFound());
        request(put("/api/admin/users/999999/role"), "role", "USER").andExpect(status().isNotFound());
        request(put("/api/admin/users/999999/unlock")).andExpect(status().isNotFound());
        request(delete("/api/admin/reviews/999999")).andExpect(status().isNotFound());
        // 管理员账号不能被封: 400, 而且同样不许留账
        request(put("/api/admin/users/" + admin + "/toggle")).andExpect(status().isBadRequest());

        assertThat(ledgerRowsInDb())
                .as("失败的动作留下账, 等于账本里混进了没发生过的事")
                .isZero();
        assertThat(actions().path("total").asInt()).isZero();
    }

    // ========== 403 ==========

    /**
     * 普通用户拿到 403 而不是 401, 也不返回任何一行操作记录。
     *
     * <p>账本是这个系统里**信息密度最高**的一份数据: 它列出了谁被封过、谁被提过权。
     * 漏出来比漏一份用户名单严重。
     */
    @Test
    @DisplayName("普通用户访问操作日志: 403, 且不返回任何记录")
    void nonAdminIsForbidden() throws Exception {
        String body = mockMvc.perform(get("/api/admin/actions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("actorName").doesNotContain("USER_BAN");
    }

    @Test
    @DisplayName("匿名访问操作日志: 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/actions")).andExpect(status().isUnauthorized());
    }
}

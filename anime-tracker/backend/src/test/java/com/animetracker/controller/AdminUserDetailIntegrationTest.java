package com.animetracker.controller;

import com.animetracker.util.CoverImages;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/users/{id}} 的对外契约: 键集、四个计数的口径、三个小列表、
 * 以及四种失败答复(401 / 403 / 404 / 400).
 *
 * <p><b>为什么这一组必须走 HTTP.</b> {@code AdminServiceTest} 里的桩问的是"发给仓储的
 * 参数对不对", 而这里要问的是另外几件事: 键集本身(那是**对外契约**, 前端按它取值)、
 * 四个计数是不是真的从各自那张表里数出来的(桩里数什么都是假的)、以及
 * {@code @PathVariable} 绑不上时 Spring 给的是 400 还是 500. 后面这一类错全都不以
 * 异常的形式出现在任何日志里 —— 接口照样 200, 只有把响应体和库里的真数据对着看才发现得了.
 *
 * <p><b>"在架"与"被移除"这一对计数是这一页的核心.</b> 它俩的意义只在**移除一条评论之后**
 * 才显形: 走查里管理员最常做的一件事就是移除一条评论, 然后想确认自己刚做的事做成了.
 * 所以 {@link #removingAReviewMovesItBetweenTheTwoCounters()} 会真的调一次
 * {@code DELETE /api/admin/reviews/{id}}, 再重读这一页比对两个数字。
 *
 * <p>登录按 IP 限流(10 次/分钟), 所以两个 token 都是整个类共用一次的静态量 ——
 * 逐用例登录会在第 11 个用例上拿到 429, 而失败信息是"登录失败", 与被测的那条接口
 * 毫无关系.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-user-detail;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminUserDetailIntegrationTest {

    /** 灌进去的账号一律以它开头, 好与 dev 自带的 admin / test 两个账号分开 */
    private static final String PREFIX = "td_";

    /**
     * 番剧 id 取一个不会被真实数据撞上的区间.
     *
     * <p>{@code anime} 表在 {@code anitrack.preload.enabled=false} 下是空的, 但把它当成
     * 空的来写会让"本地缓存过这部番"这条用例依赖启动配置 —— 显式灌自己那几行,
     * 断言就只与自己灌的数据有关.
     */
    private static final int ANIME_CACHED = 910001;
    private static final int ANIME_ALSO_CACHED = 910002;
    /** 本地缓存过、且封面是个**真地址**的那一部 —— 见 {@link #REAL_COVER} */
    private static final int ANIME_PROXIED = 910003;

    /**
     * 一个白名单内的真封面.
     *
     * <p>本文件里其它几处用的是 {@code "cover-a"} 这种假地址, 而它们<b>验不出代理有没有
     * 接上</b>: 白名单外的地址由 {@code CoverImages.proxied} 原样返回, 于是"过没过 proxied"
     * 两种写法产出完全一样的一行. 要证明接线真的接上了, 只能用真地址。
     */
    private static final String REAL_COVER = "https://lain.bgm.tv/pic/cover/l/aa/01/910003.jpg";

    /**
     * 只被评论提到、{@code anime} 表里**没有**的那一部.
     *
     * <p>它存在是因为番剧数据是按需从外部 API 拉进 {@code anime} 表的, 而
     * {@code review.subject_id} 与 {@code anime} 之间没有外键 —— 所以"这部番还没进本地库"
     * 是一个真实且常见的状态, 不是编出来边界. 这一页必须给它 {@code animeTitle: null},
     * 由前端退化成「番剧 #656083」, <b>而不是</b>用「未知作品」兜底.
     */
    private static final int ANIME_MISSING = 910999;

    private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 1, 0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 管理员 token, 整个类共用一次(理由见类注释) */
    private static String adminToken;

    /** 普通用户 token, 只给 403 那一条用 */
    private static String userToken;

    @BeforeEach
    void cleanAndLogin() throws Exception {
        // 先删子行再删用户: 这四张表都指着 "user", 顺序反了会被外键拒掉. 类里的用例
        // 共用同一个 Spring 上下文、也就共用同一个库, 所以每轮都得清.
        // 账本表**零外键**(全 schema 唯一一张), 因此它得自己删.
        jdbc.update("DELETE FROM review WHERE user_id IN "
                + "(SELECT id FROM \"user\" WHERE username LIKE 'td%')");
        jdbc.update("DELETE FROM anime_tracking WHERE user_id IN "
                + "(SELECT id FROM \"user\" WHERE username LIKE 'td%')");
        jdbc.update("DELETE FROM episode_watched WHERE user_id IN "
                + "(SELECT id FROM \"user\" WHERE username LIKE 'td%')");
        jdbc.update("DELETE FROM admin_action_log");
        jdbc.update("DELETE FROM \"user\" WHERE username LIKE 'td%'");
        jdbc.update("DELETE FROM anime WHERE id IN (?, ?, ?)",
                ANIME_CACHED, ANIME_ALSO_CACHED, ANIME_PROXIED);

        if (adminToken == null) {
            adminToken = login("admin", "admin123");
        }
        if (userToken == null) {
            userToken = login("test", "test123");
        }
    }

    // ========== 灌数据 ==========

    private long seedUser(String username, String role, String status, LocalDateTime lockedUntil) {
        jdbc.update("INSERT INTO \"user\" (username, password, email, role, status, locked_until, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                username,
                "$2a$10$thisIsNotAValidHashButTheColumnOnlyNeedsChars", // 从不拿它登录
                username + "@example.com",
                role,
                status,
                lockedUntil == null ? null : Timestamp.valueOf(lockedUntil),
                Timestamp.valueOf(BASE));
        return userIdOf(username);
    }

    private long userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM \"user\" WHERE username = ?", Long.class, username);
    }

    private void seedAnime(int id, String title, String titleCn, String cover) {
        jdbc.update("INSERT INTO anime (id, title, title_cn, cover_url) VALUES (?, ?, ?, ?)",
                id, title, titleCn, cover);
    }

    /** 灌一条追番. {@code minutes} 决定 updated_at, 也就是列表里的先后 */
    private void seedTracking(long userId, int subjectId, String status, int progress, int minutes) {
        jdbc.update("INSERT INTO anime_tracking "
                        + "(user_id, subject_id, status, progress, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                userId, subjectId, status, progress,
                Timestamp.valueOf(BASE), Timestamp.valueOf(BASE.plusMinutes(minutes)));
    }

    /** 灌一条评论, 返回 id. {@code minutes} 决定 created_at */
    private long seedReview(long userId, int subjectId, String content, int minutes) {
        jdbc.update("INSERT INTO review (user_id, subject_id, rating, content, created_at) "
                        + "VALUES (?, ?, 8, ?, ?)",
                userId, subjectId, content, Timestamp.valueOf(BASE.plusMinutes(minutes)));
        return jdbc.queryForObject(
                "SELECT id FROM review WHERE user_id = ? AND subject_id = ?",
                Long.class, userId, subjectId);
    }

    private void seedWatched(long userId, int animeId, int episodeNum) {
        jdbc.update("INSERT INTO episode_watched (user_id, anime_id, episode_num, watched_at) "
                        + "VALUES (?, ?, ?, ?)",
                userId, animeId, episodeNum, Timestamp.valueOf(BASE));
    }

    // ========== 发请求 ==========

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private ResultActions getDetail(Object userId, String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/users/" + userId);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    /** 取详情并返回 {@code data} 节点(顺带钉住 200 + code 200) */
    private JsonNode detailOf(long userId) throws Exception {
        String body = getDetail(userId, adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<String> contentsOf(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(row -> out.add(row.path("content").asText()));
        return out;
    }

    // ========== 键集: 这就是对外契约 ==========

    /**
     * 顶层 14 个键 + {@code counts} 的 4 个键, 一个不多一个不少。
     *
     * <p>用 {@code containsExactlyInAnyOrder} 而不是 {@code containsAll}/{@code contains}:
     * 那两个对**多出来的键**是瞎的, 而"服务端多发了一个键"正是这条契约要挡的事
     * (前端按键取值, 多发的那些没人会去读, 也没人会发现自己读到的是错的).
     *
     * <p>三个列表在空账号上仍然要是**空数组而不是缺键**: 缺键会让前端的
     * {@code list.length} 落到 undefined 上, 看着与空数组一样, 但「服务端没发这个键」
     * 这种真实故障就再也看不出来了.
     */
    @Test
    @DisplayName("空账号: 顶层 14 键 + counts 4 键, 三个列表是空数组不是缺键")
    void responseShape() throws Exception {
        long target = seedUser(PREFIX + "empty", "USER", "ACTIVE", null);

        JsonNode data = detailOf(target);

        assertThat(keysOf(data)).containsExactlyInAnyOrder(
                "id", "username", "email", "avatar", "role", "status",
                "createdAt", "lastLoginAt", "locked", "lockedUntil",
                "counts", "trackings", "reviews", "actions");
        assertThat(keysOf(data.path("counts")))
                .containsExactlyInAnyOrder("trackings", "reviewsAlive",
                        "reviewsRemoved", "episodesWatched");

        assertThat(data.path("trackings").isArray()).isTrue();
        assertThat(data.path("reviews").isArray()).isTrue();
        assertThat(data.path("actions").isArray()).isTrue();
        assertThat(data.path("trackings")).isEmpty();
        assertThat(data.path("reviews")).isEmpty();
        assertThat(data.path("actions")).isEmpty();

        assertThat(data.path("username").asText()).isEqualTo(PREFIX + "empty");
        assertThat(data.path("counts").path("trackings").asInt()).isZero();
        assertThat(data.path("counts").path("episodesWatched").asInt()).isZero();
    }

    /**
     * 四个计数各自数各自那张表。
     *
     * <p>灌的是**互不相同**的四个数(3 / 2 / 1 / 5): 如果哪个计数接错了表或者接错了
     * 筛选条件, 结果就不会恰好是这四个数 —— 全灌成一样的数则四种错法会长得一模一样.
     */
    @Test
    @DisplayName("四个计数: 3 追番 / 2 在架 / 1 被移除 / 5 已看")
    void countsComeFromTheirOwnTables() throws Exception {
        long target = seedUser(PREFIX + "bob", "USER", "ACTIVE", null);
        seedAnime(ANIME_CACHED, "Sousou no Frieren", "葬送的芙莉莲", "cover-a");

        seedTracking(target, ANIME_CACHED, "WATCHING", 5, 1);
        seedTracking(target, ANIME_MISSING, "PLAN", 0, 2);
        seedTracking(target, ANIME_ALSO_CACHED, "COMPLETED", 12, 3);

        seedReview(target, ANIME_CACHED, "第一条", 1);
        seedReview(target, ANIME_ALSO_CACHED, "第二条", 2);
        long removed = seedReview(target, ANIME_MISSING, "会被移除的那条", 3);
        adminDeleteReview(removed);

        for (int ep = 1; ep <= 5; ep++) {
            seedWatched(target, ANIME_CACHED, ep);
        }

        JsonNode counts = detailOf(target).path("counts");

        assertThat(counts.path("trackings").asInt()).isEqualTo(3);
        assertThat(counts.path("reviewsAlive").asInt()).isEqualTo(2);
        assertThat(counts.path("reviewsRemoved").asInt()).isEqualTo(1);
        assertThat(counts.path("episodesWatched").asInt()).isEqualTo(5);
    }

    /**
     * 移除一条评论之后, 两个计数**同时**动: 在架 −1、被移除 +1。列表里那一条也还在。
     *
     * <p>这是"在架/被移除"这一对唯一的意义所在。管理员最常做的一件事就是移除一条评论,
     * 然后要能从这个页面确认自己刚做的事做成了 —— 计数动了而列表里找不到那一条,
     * 他会以为是自己看错了, 或者反过来去点第二次.
     *
     * <p>所以 {@code reviews} 列表刻意**含被移除的**(语句里没有 {@code deletedAt IS NULL}),
     * 每行用 {@code deletedAt} 非空表示「已移除」.
     */
    @Test
    @DisplayName("移除一条评论: 在架 2→1、被移除 1→2, 列表里那一条仍在且带 deletedAt")
    void removingAReviewMovesItBetweenTheTwoCounters() throws Exception {
        long target = seedUser(PREFIX + "carol", "USER", "ACTIVE", null);
        seedReview(target, ANIME_CACHED, "留下的", 1);
        seedReview(target, ANIME_ALSO_CACHED, "留下的二", 2);
        long doomed = seedReview(target, ANIME_MISSING, "要被移除的", 3);

        // 移除之前: 三条全在架上, 一条都不算"被移除"
        JsonNode before = detailOf(target).path("counts");
        assertThat(before.path("reviewsAlive").asInt()).isEqualTo(3);
        assertThat(before.path("reviewsRemoved").asInt()).isZero();
        assertThat(contentsOf(detailOf(target).path("reviews")))
                .containsExactly("要被移除的", "留下的二", "留下的");

        adminDeleteReview(doomed);

        // 移除之后: 在架少一条、被移除多一条 —— 两个数字同时动, 且加起来仍是三条
        JsonNode after = detailOf(target).path("counts");
        assertThat(after.path("reviewsAlive").asInt()).isEqualTo(2);
        assertThat(after.path("reviewsRemoved").asInt()).isEqualTo(1);

        JsonNode reviews = detailOf(target).path("reviews");
        assertThat(contentsOf(reviews)).containsExactly("要被移除的", "留下的二", "留下的");
        JsonNode removedRow = reviews.get(0);
        assertThat(removedRow.path("deletedAt").isNull()).isFalse();
        assertThat(reviews.get(1).path("deletedAt").isNull()).isTrue();
    }

    /**
     * 番剧名: 本地缓存过的给名字与封面, 没缓存过的给 {@code null}。
     *
     * <p><b>不用「未知作品」兜底</b> —— 编一个假名字比空着更糟: 几条不同 subjectId 的记录
     * 会挤在同一个假名字下面, 而"这部番还没进本地库"本身是真信息. 前端拿到 null 时
     * 退化成「番剧 #910999」.
     */
    @Test
    @DisplayName("番剧名: 缓存过给中文名与封面, 没缓存过给 null 而不是「未知作品」")
    void animeTitleIsNullWhenNotCachedLocally() throws Exception {
        long target = seedUser(PREFIX + "dave", "USER", "ACTIVE", null);
        seedAnime(ANIME_CACHED, "Sousou no Frieren", "葬送的芙莉莲", "cover-a");
        // ANIME_ALSO_CACHED 刻意**不灌**: 只有日文原名都没有的才是"没进本地库"
        seedAnime(ANIME_ALSO_CACHED, "Bocchi the Rock!", null, "cover-b");
        // 第三个刻意给一个**真封面**: "cover-a" 那种假地址是验不出代理有没有接上的 ——
        // 白名单外的地址原样返回, 于是"过没过 CoverImages.proxied"产出完全一样的一行。
        seedAnime(ANIME_PROXIED, "Yofukashi no Uta", "彻夜之歌", REAL_COVER);

        seedTracking(target, ANIME_CACHED, "WATCHING", 5, 2);
        seedTracking(target, ANIME_MISSING, "PLAN", 0, 1);
        seedTracking(target, ANIME_PROXIED, "PLAN", 0, 0);
        seedReview(target, ANIME_MISSING, "没缓存过那部的评论", 1);

        JsonNode data = detailOf(target);

        // 追番按 updated_at 倒序: 后灌的在前
        JsonNode trackings = data.path("trackings");
        assertThat(trackings.get(0).path("animeTitle").asText()).isEqualTo("葬送的芙莉莲");
        assertThat(trackings.get(0).path("animeCover").asText()).isEqualTo("cover-a");
        assertThat(trackings.get(1).path("subjectId").asInt()).isEqualTo(ANIME_MISSING);
        assertThat(trackings.get(1).path("animeTitle").isNull()).isTrue();
        assertThat(trackings.get(1).path("animeCover").isNull()).isTrue();
        assertThat(trackings.get(2).path("animeCover").asText())
                .as("白名单内的封面必须换成本站代理地址")
                .startsWith(CoverImages.PROXY_PATH + "?url=");

        // title_cn 为空时回退日文原名 —— 与 displayName 的口径一致
        JsonNode orphanReview = data.path("reviews").get(0);
        assertThat(orphanReview.path("animeTitle").isNull()).isTrue();
        assertThat(orphanReview.path("subjectId").asInt()).isEqualTo(ANIME_MISSING);
    }

    /**
     * 账本列表**只含这个账号**, 是「别人对他做过什么」。
     *
     * <p>这一条同时钉住那两条仓储语句里 {@code targetType}/{@code targetId} 谓词真的生效:
     * 只改 {@code findPage} 不改 {@code countPage} 之类的错法在这一页上看不出来(详情页
     * 不走 count), 但"筛到别的用户"这种错法在这里会立刻显形 —— 另一个账号的动作混进来.
     */
    @Test
    @DisplayName("账本列表只含这个账号的动作, 别人身上的动作不混进来")
    void actionsAreScopedToThisUser() throws Exception {
        long target = seedUser(PREFIX + "erin", "USER", "ACTIVE", null);
        long bystander = seedUser(PREFIX + "frank", "USER", "ACTIVE", null);

        adminToggle(target);
        adminToggle(bystander);
        adminToggle(bystander);
        adminToggle(target);

        JsonNode actions = detailOf(target).path("actions");

        // 正好两条, 且都是这个账号的. 点两次 toggle 得到 BAN 再 UNBAN —— 断言两个都出现,
        // 而不是断言"两条都是 USER_BAN": 后者会把这个用例变成在测 toggle 的语义,
        // 而它要测的是**筛得对不对**(旁观者身上那两条不该进来).
        assertThat(actions).hasSize(2);
        Set<String> seen = new HashSet<>();
        actions.forEach(row -> {
            assertThat(row.path("targetType").asText()).isEqualTo("USER");
            assertThat(row.path("targetId").asLong()).isEqualTo(target);
            seen.add(row.path("action").asText());
        });
        assertThat(seen).containsExactlyInAnyOrder("USER_BAN", "USER_UNBAN");
    }

    /**
     * <b>被禁用、被锁定的账号照样打得开。</b>
     *
     * <p>这是读操作, 不该继承任何写入限制 —— 而管理员要处理的往往正是这些号. 打不开就等于
     * 这一页在最需要它的时候不可用, 且没有任何东西会提示这一点.
     *
     * <p>顺带钉住 {@code locked} 的判据: 过期的 {@code lockedUntil} 不算锁定
     * (与列表行 {@code AdminService.toAdminUserRow} 用的是同一个 {@code isLocked()}).
     */
    @Test
    @DisplayName("被禁用/被锁定的账号仍能打开; 过期的 lockedUntil 不算锁定")
    void readOnlyDetailWorksForDisabledAndLockedUsers() throws Exception {
        long locked = seedUser(PREFIX + "locked", "USER", "ACTIVE", LocalDateTime.now().plusDays(1));
        long disabled = seedUser(PREFIX + "disabled", "USER", "DISABLED", null);
        long expired = seedUser(PREFIX + "expired", "USER", "ACTIVE", LocalDateTime.now().minusDays(1));

        JsonNode lockedData = detailOf(locked);
        assertThat(lockedData.path("locked").asBoolean()).isTrue();
        assertThat(lockedData.path("lockedUntil").isNull()).isFalse();

        assertThat(detailOf(disabled).path("status").asText()).isEqualTo("DISABLED");

        // 早已自动解锁的账号: 时间戳还在字段里, 但 locked 必须是 false —— 否则管理员会去
        // 点一个没有意义的解锁按钮.
        JsonNode expiredData = detailOf(expired);
        assertThat(expiredData.path("locked").asBoolean()).isFalse();
        assertThat(expiredData.path("lockedUntil").isNull()).isTrue();
    }

    // ========== 四种失败答复 ==========

    @Test
    @DisplayName("不存在的用户: 404, 不是 200 的空壳")
    void missingUserIsNotFound() throws Exception {
        getDetail(99999999L, adminToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 路径参数不是数字: 400。
     *
     * <p>这是 Spring 在参数绑定阶段抛 {@code MethodArgumentTypeMismatchException} 的结果,
     * 不需要我们写任何代码 —— 但要把它钉住, 因为前端正是**靠这个答复**决定"要在发请求
     * 之前就先校验 id": 一个 400 如果被前端当成"加载失败", 就会给出一个点多少次都还是
     * 400 的重试按钮.
     */
    @Test
    @DisplayName("id 不是数字: 400")
    void nonNumericIdIsBadRequest() throws Exception {
        getDetail("abc", adminToken).andExpect(status().isBadRequest());
    }

    /**
     * 普通用户: 403, 而且**响应体里不含被查账号的用户名**。
     *
     * <p>后半句才是这条用例的重点: 这一页的响应体里有邮箱、追番、评论全文. 只要
     * {@code checkAdmin} 被挪到读之后, 泄漏就是静默的 —— 状态码还是 403 的话更糟,
     * 看起来像拦住了.
     */
    @Test
    @DisplayName("普通用户: 403, 且响应体里不含被查账号的用户名")
    void nonAdminIsForbidden() throws Exception {
        long target = seedUser(PREFIX + "grace", "USER", "ACTIVE", null);

        String body = getDetail(target, userToken)
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PREFIX + "grace");
        assertThat(body).doesNotContain("@example.com");
    }

    @Test
    @DisplayName("匿名: 401")
    void anonymousIsUnauthorized() throws Exception {
        long target = seedUser(PREFIX + "heidi", "USER", "ACTIVE", null);

        getDetail(target, null).andExpect(status().isUnauthorized());
    }

    // ========== 小工具 ==========

    private void adminToggle(long userId) throws Exception {
        mockMvc.perform(put("/api/admin/users/" + userId + "/toggle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private void adminDeleteReview(long reviewId) throws Exception {
        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}

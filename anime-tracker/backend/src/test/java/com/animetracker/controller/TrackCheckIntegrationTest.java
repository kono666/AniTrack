package com.animetracker.controller;

import com.animetracker.util.CoverImages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 打卡({@code /api/stats/toggle-episode})与追番({@code /api/track/**})这两条路在 HTTP 上的契约.
 *
 * <p>为什么这一层非补不可: 这两个入口此前<b>一条用例都没有</b>. service 单测验的是
 * 「调了哪个方法、传了什么参数」, 而「打完勾之后 {@code /api/track/list} 里到底有没有
 * 那部番」横跨两个控制器、一次落库与一次序列化 —— 中间任何一段断掉, 单测里都是绿的.
 *
 * <p>用 MockMvc + 内存库: 要验的是状态码、过滤器链与落库结果. 关掉启动预加载, 否则这个
 * JVM 会去调 api.bgm.tv(同 {@code AdminReviewDeleteIntegrationTest}); 放开注册与登录限流,
 * 否则用例一多就开始 429, 而失败信息看起来与打卡毫无关系(同
 * {@code ReviewLikeIntegrationTest}).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-track-check;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        "app.security.rate-limit.register-per-minute=1000",
        "app.security.rate-limit.login-per-minute=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TrackCheckIntegrationTest {

    /** Bangumi 不可能返回的 id 段: 断言只在自己造的行上做, 不受任何真实数据影响 */
    private static final int SUBJECT_NOT_CACHED = 998101;
    private static final int SUBJECT_CACHED = 998102;
    /** 本地缓存过、且知道总集数, 用来验「看完了就不在继续看里」的那一部 */
    private static final int SUBJECT_FINISHED = 998103;

    /**
     * 一个**真封面**(白名单内的 lain.bgm.tv 地址).
     *
     * <p>本文件里其它地方用的 {@code "cover-a"} 那种假地址是验不出代理有没有接上的:
     * 白名单外的地址原样返回, 于是"过没过 {@code CoverImages.proxied}"两种写法产出完全
     * 相同的一行. 要证明接线接上了, 只能用真地址。
     */
    private static final String REAL_COVER = "https://lain.bgm.tv/pic/cover/l/aa/01/998102.jpg";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ========== 造数据 ==========

    /** 注册一个普通用户并登录 —— 注册接口本身是公开的 */
    private String registerAndLogin() throws Exception {
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        String body = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void toggle(String token, int subjectId, int episodeNum) throws Exception {
        mockMvc.perform(post("/api/stats/toggle-episode")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("animeId", String.valueOf(subjectId))
                        .param("episodeNum", String.valueOf(episodeNum)))
                .andExpect(status().isOk());
    }

    /** POST /api/track, 返回原始响应 —— 状态码与响应体两边都要看 */
    private MockHttpServletResponse postTrack(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/track")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse();
    }

    private JsonNode bodyOf(MockHttpServletResponse res) throws Exception {
        return objectMapper.readTree(res.getContentAsString());
    }

    /** /api/track/list 的 data 数组 */
    private JsonNode trackingList(String token) throws Exception {
        return getJson("/api/track/list", token).path("data");
    }

    /** 列表里 subjectId 等于这一部的那一行 */
    private static JsonNode rowFor(JsonNode list, int subjectId) {
        for (JsonNode row : list) {
            if (row.path("subjectId").asInt() == subjectId) {
                return row;
            }
        }
        throw new AssertionError("追番列表里没有 subjectId=" + subjectId + " 的那一行");
    }

    /** 那一行的键集合 */
    private static Set<String> keysOf(JsonNode row) {
        Set<String> keys = new HashSet<>();
        row.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    // ========== 打卡 → 追番列表 ==========

    @Test
    @DisplayName("打勾之后追番列表里就有这部番了: 状态是在看, 进度是这一集")
    void togglingAnEpisodeShowsUpInTheTrackingList() throws Exception {
        String token = registerAndLogin();

        mockMvc.perform(post("/api/stats/toggle-episode")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("animeId", String.valueOf(SUBJECT_NOT_CACHED))
                        .param("episodeNum", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.watched").value(true))
                .andExpect(jsonPath("$.data.episodeNum").value(3));

        JsonNode row = rowFor(trackingList(token), SUBJECT_NOT_CACHED);
        assertThat(row.path("status").asText()).isEqualTo("watching");
        assertThat(row.path("progress").asInt()).isEqualTo(3);

        // /status 这条独立的路也要一致 —— 详情页正是靠它决定追番栏显不显示
        JsonNode status = getJson("/api/track/status?subjectId=" + SUBJECT_NOT_CACHED, token)
                .path("data");
        assertThat(status.path("tracked").asBoolean()).isTrue();
        assertThat(status.path("status").asText()).isEqualTo("watching");
    }

    @Test
    @DisplayName("取消打勾: 勾没了, 追番记录与进度都还在")
    void unmarkingKeepsTheTrackingRow() throws Exception {
        String token = registerAndLogin();
        toggle(token, SUBJECT_NOT_CACHED, 3);

        mockMvc.perform(post("/api/stats/toggle-episode")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("animeId", String.valueOf(SUBJECT_NOT_CACHED))
                        .param("episodeNum", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.watched").value(false));

        assertThat(getJson("/api/stats/watched-episodes?animeId=" + SUBJECT_NOT_CACHED, token)
                .path("data").size()).isZero();
        JsonNode row = rowFor(trackingList(token), SUBJECT_NOT_CACHED);
        assertThat(row.path("status").asText()).isEqualTo("watching");
        assertThat(row.path("progress").asInt()).isEqualTo(3);
    }

    /**
     * 这一条是给「抽出公共行构造器」上的保险.
     *
     * <p>追番列表的行以前没有任何用例在断言它的<b>完整</b>键集合 —— 少一个 {@code notes}
     * 或 {@code totalEpisodes} 不会有任何东西红. 而番剧名/封面/总集数这三个键是按
     * 「本地有没有缓存过这部番」**有条件**加上去的: 补不到时**不加键**, 不是给个 null
     * (前端要靠这个区分「这部番没有总集数」与「服务端没发这个字段」). 两个分支都钉住.
     */
    @Test
    @DisplayName("追番行的形状: 本地没缓存这部番是 8 个键, 缓存了才是 12 个")
    void trackingRowCarriesTheWholeShapeInBothBranches() throws Exception {
        String token = registerAndLogin();

        toggle(token, SUBJECT_NOT_CACHED, 1);
        assertThat(keysOf(rowFor(trackingList(token), SUBJECT_NOT_CACHED))).containsOnly(
                "id", "subjectId", "status", "progress", "score", "notes", "createdAt", "updatedAt");

        // anime.id 就是 Bangumi 的 subject id, 由同步逻辑写入 —— 这里手工造一行来走另一支.
        // episode_total 特意取一个与 total_episodes **不等**的值: 这两个数在前端是两个不同的
        // 东西(声明值 vs 本地收齐的条数), 取相同值的话"发的是哪一个"就验不出来了.
        //
        // cover_url 给一个**真封面**: 这一条断言就是 TrackService 那个 proxied 调用点的哨兵.
        // 给 "cover-a" 那样的假地址是验不出来的 —— 非白名单**原样返回**, 于是"过没过
        // proxied"两种写法产出一模一样.
        jdbc.update("INSERT INTO anime (id, title, title_cn, total_episodes, episode_total, cover_url) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                SUBJECT_CACHED, "original title", "中文名", 12, 9, REAL_COVER);
        toggle(token, SUBJECT_CACHED, 1);

        JsonNode withAnime = rowFor(trackingList(token), SUBJECT_CACHED);
        assertThat(keysOf(withAnime)).containsOnly(
                "id", "subjectId", "status", "progress", "score", "notes", "createdAt", "updatedAt",
                "animeTitle", "animeCover", "totalEpisodes", "episodeTotal");
        assertThat(withAnime.path("animeTitle").asText()).as("titleCn 优先").isEqualTo("中文名");
        assertThat(withAnime.path("animeCover").asText())
                .as("白名单内的封面必须换成本站代理地址 —— 首页/追番页的封面就是这一行")
                .isEqualTo(CoverImages.proxied(REAL_COVER))
                .startsWith(CoverImages.PROXY_PATH + "?url=");
        assertThat(withAnime.path("totalEpisodes").asInt()).isEqualTo(12);
        // 两个键都得真的带上值 —— 只断键存在的话, 发一个恒 null 的 episodeTotal 也会绿,
        // 而前端拿它当分母时 null 与 0 一样是"不知道", 进度条仍然不画
        assertThat(withAnime.path("episodeTotal").asInt()).isEqualTo(9);
    }

    // ========== 边界 ==========

    @Test
    @DisplayName("未登录: 打勾、列表、状态三个入口都回 401")
    void everythingUnderTrackAndToggleNeedsALogin() throws Exception {
        mockMvc.perform(post("/api/stats/toggle-episode")
                        .param("animeId", String.valueOf(SUBJECT_NOT_CACHED))
                        .param("episodeNum", "1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/track/list")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/track/status")
                        .param("subjectId", String.valueOf(SUBJECT_NOT_CACHED)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/stats/watched-episodes")
                        .param("animeId", String.valueOf(SUBJECT_NOT_CACHED)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("打卡是私事: 另一个账号既看不到这条追番, 也看不到这个勾")
    void anotherAccountSeesNothingOfYours() throws Exception {
        String mine = registerAndLogin();
        String theirs = registerAndLogin();
        toggle(mine, SUBJECT_NOT_CACHED, 5);

        assertThat(trackingList(theirs).size()).isZero();
        assertThat(getJson("/api/stats/watched-episodes?animeId=" + SUBJECT_NOT_CACHED, theirs)
                .path("data").size()).isZero();
        assertThat(getJson("/api/track/status?subjectId=" + SUBJECT_NOT_CACHED, theirs)
                .path("data").path("tracked").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("特番的集号 0: 勾打上了, 但不建追番记录")
    void episodeNumberZeroMarksButDoesNotTrack() throws Exception {
        String token = registerAndLogin();

        toggle(token, SUBJECT_NOT_CACHED, 0);

        assertThat(getJson("/api/stats/watched-episodes?animeId=" + SUBJECT_NOT_CACHED, token)
                .path("data").size()).as("勾是算数的").isEqualTo(1);
        assertThat(trackingList(token).size()).as("但不该因此建一条追番记录").isZero();
    }

    // ========== 首页「继续看」 ==========

    /** /api/track/continue 的 data 数组. limit 为空则不带那个参数 */
    private JsonNode continueWatching(String token, Integer limit) throws Exception {
        String path = limit == null ? "/api/track/continue" : "/api/track/continue?limit=" + limit;
        return getJson(path, token).path("data");
    }

    @Test
    @DisplayName("继续看: 打完勾自动建出的那行就在里面, 行形状与追番列表逐字相同")
    void continueWatchingCarriesTheRowTogglingJustCreated() throws Exception {
        String token = registerAndLogin();
        assertThat(continueWatching(token, null).size()).as("还没看任何番时是空数组").isZero();

        toggle(token, SUBJECT_NOT_CACHED, 3);

        JsonNode rows = continueWatching(token, null);
        assertThat(rows.size()).isEqualTo(1);
        // 与 /list 的行**同一套键** —— 两处共用行构造器, 飘开了的表现是首页少一个标题
        assertThat(keysOf(rows.get(0)))
                .isEqualTo(keysOf(rowFor(trackingList(token), SUBJECT_NOT_CACHED)));
        assertThat(rows.get(0).path("subjectId").asInt()).isEqualTo(SUBJECT_NOT_CACHED);
        assertThat(rows.get(0).path("progress").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("继续看: 只想看的不算, 已经追平总集数的也不算")
    void continueWatchingSkipsBothNotStartedAndFinished() throws Exception {
        String token = registerAndLogin();

        // ① 「想看」的不进来: 状态过滤在 SQL 里, 不是把全部取回来再在 Java 里删
        mockMvc.perform(post("/api/track")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + SUBJECT_NOT_CACHED
                                + ",\"status\":\"want_to_watch\",\"progress\":0}"))
                .andExpect(status().isOk());
        assertThat(continueWatching(token, null).size()).isZero();

        // ② 看完了的不进来. 本地有这部番的行、且知道它一共 12 集 —— 只有这一种情况下
        //    服务端才判得出"追平了"(totalEpisodes 为 null 时判不了, 那条代价写在
        //    TrackService#getContinueWatching 上)
        jdbc.update("INSERT INTO anime (id, title, total_episodes) VALUES (?, ?, ?)",
                SUBJECT_FINISHED, "finished", 12);
        toggle(token, SUBJECT_FINISHED, 12);

        assertThat(continueWatching(token, null).size()).isZero();
        // 但它**仍在**追番列表里, 而且状态是在看 —— 滤掉的只是继续看这一个入口
        JsonNode row = rowFor(trackingList(token), SUBJECT_FINISHED);
        assertThat(row.path("status").asText()).isEqualTo("watching");
        assertThat(row.path("progress").asInt()).isEqualTo(12);
    }

    @Test
    @DisplayName("继续看: limit 越界不报错, 而且不会把全部读出来")
    void continueWatchingClampsItsLimit() throws Exception {
        String token = registerAndLogin();
        for (int i = 0; i < 5; i++) {
            toggle(token, SUBJECT_NOT_CACHED + i, 1);
        }

        assertThat(continueWatching(token, 2).size()).isEqualTo(2);
        // 0 会让 PageRequest.of 直接抛 —— 类上没加 @Validated, 所以这里的夹取
        // 只能是服务层手写的, 真按 0 传下去就是 500
        assertThat(continueWatching(token, 0).size()).as("0 回默认 10").isEqualTo(5);
        assertThat(continueWatching(token, -3).size()).as("负数同样回默认").isEqualTo(5);
        assertThat(continueWatching(token, 999999).size()).as("封顶 20, 不是 500 也不是全量").isEqualTo(5);
    }

    @Test
    @DisplayName("继续看也是私事: 未登录 401, 另一个账号看不到")
    void continueWatchingIsPrivateToo() throws Exception {
        mockMvc.perform(get("/api/track/continue")).andExpect(status().isUnauthorized());

        String mine = registerAndLogin();
        String theirs = registerAndLogin();
        toggle(mine, SUBJECT_NOT_CACHED, 2);

        assertThat(continueWatching(mine, null).size()).isEqualTo(1);
        assertThat(continueWatching(theirs, null).size()).isZero();
    }

    // ========== POST /api/track 是局部更新 ==========

    /**
     * 这一组钉的是「没传的字段一律不动」在 HTTP 这一层真的成立.
     *
     * <p>服务层的三条用例验的是实体上的字段, 但整行覆盖的入口在 HTTP 这一侧:
     * 只要 DTO 还要求 status 必填, 每个调用方就只能把整行发回来 ——
     * 而"整行发回来"正是「个人页改个状态, 进度被写回旧值」的来源. 所以这里要的不只是
     * 「结果对」, 而是「只带一个字段的请求本身能被收下」.
     */
    @Test
    @DisplayName("只发 progress: 请求被收下, 而且状态一个字节都没动")
    void aProgressOnlyRequestLeavesTheStatusAlone() throws Exception {
        String token = registerAndLogin();
        postTrack(token, "{\"subjectId\":" + SUBJECT_NOT_CACHED + ",\"status\":\"on_hold\"}");

        // 改前这一句就是 400(缺少追番状态), 于是"只改进度"只能整行发
        MockHttpServletResponse res = postTrack(token,
                "{\"subjectId\":" + SUBJECT_NOT_CACHED + ",\"progress\":5}");
        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(bodyOf(res).path("data").path("progress").asInt()).isEqualTo(5);

        JsonNode row = rowFor(trackingList(token), SUBJECT_NOT_CACHED);
        assertThat(row.path("status").asText()).as("没传 status 就该保持 on_hold").isEqualTo("on_hold");
        assertThat(row.path("progress").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("只发 status: 进度保持原值, 不会被清零或写回旧值")
    void aStatusOnlyRequestLeavesTheProgressAlone() throws Exception {
        String token = registerAndLogin();
        postTrack(token, "{\"subjectId\":" + SUBJECT_NOT_CACHED
                + ",\"status\":\"watching\",\"progress\":8}");

        postTrack(token, "{\"subjectId\":" + SUBJECT_NOT_CACHED + ",\"status\":\"watched\"}");

        JsonNode row = rowFor(trackingList(token), SUBJECT_NOT_CACHED);
        assertThat(row.path("status").asText()).isEqualTo("watched");
        assertThat(row.path("progress").asInt()).as("只改状态不该动进度").isEqualTo(8);
    }

    @Test
    @DisplayName("status 整个缺席可以, 传了键给空值不行")
    void statusMayBeOmittedButNotBlank() throws Exception {
        String token = registerAndLogin();

        // 传了键却给空值 = 调用方有 bug: 空串不是一个合法状态, 放进去会变成
        // 一个五个统计口径都不认的值(「追番了但总数没变」)
        assertThat(postTrack(token, "{\"subjectId\":" + SUBJECT_NOT_CACHED + ",\"status\":\"\"}").getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        // 键整个不在 = 合法, 新建那一行按默认的「想看」建(库里那一列 NOT NULL)
        MockHttpServletResponse res = postTrack(token,
                "{\"subjectId\":" + SUBJECT_NOT_CACHED + ",\"score\":9}");
        assertThat(res.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(bodyOf(res).path("data").path("status").asText()).isEqualTo("want_to_watch");

        JsonNode row = rowFor(trackingList(token), SUBJECT_NOT_CACHED);
        assertThat(row.path("status").asText()).isEqualTo("want_to_watch");
        assertThat(row.path("score").asInt()).isEqualTo(9);
        assertThat(row.path("progress").asInt()).isZero();
    }
}

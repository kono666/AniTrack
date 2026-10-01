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
    @DisplayName("追番行的形状: 本地没缓存这部番是 8 个键, 缓存了才是 11 个")
    void trackingRowCarriesTheWholeShapeInBothBranches() throws Exception {
        String token = registerAndLogin();

        toggle(token, SUBJECT_NOT_CACHED, 1);
        assertThat(keysOf(rowFor(trackingList(token), SUBJECT_NOT_CACHED))).containsOnly(
                "id", "subjectId", "status", "progress", "score", "notes", "createdAt", "updatedAt");

        // anime.id 就是 Bangumi 的 subject id, 由同步逻辑写入 —— 这里手工造一行来走另一支
        jdbc.update("INSERT INTO anime (id, title, title_cn, total_episodes) VALUES (?, ?, ?, ?)",
                SUBJECT_CACHED, "original title", "中文名", 12);
        toggle(token, SUBJECT_CACHED, 1);

        JsonNode withAnime = rowFor(trackingList(token), SUBJECT_CACHED);
        assertThat(keysOf(withAnime)).containsOnly(
                "id", "subjectId", "status", "progress", "score", "notes", "createdAt", "updatedAt",
                "animeTitle", "animeCover", "totalEpisodes");
        assertThat(withAnime.path("animeTitle").asText()).as("titleCn 优先").isEqualTo("中文名");
        assertThat(withAnime.path("totalEpisodes").asInt()).isEqualTo(12);
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
}

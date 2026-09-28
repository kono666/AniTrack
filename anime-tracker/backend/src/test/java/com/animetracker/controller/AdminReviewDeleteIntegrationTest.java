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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员删一条不存在的评论, 应当是 404, 而不是 500.
 *
 * <p>这事看着小, 但它是「服务端坏了」和「这条已经没有了」的区别: 管理端列表是
 * 有缓存的, 作者自己删掉评论、或者另一个管理员先点了一下, 列表上那条还在 ——
 * 再点删除就会走到这里. 回 500 的话, 管理员看到的是服务出错, 于是去翻日志、重启,
 * 而真相是「目标不在了」这件再正常不过的事.
 *
 * <p>为什么值得一组集成用例而不是 service 单测: 这里要钉的是**对外状态码**.
 * deleteById 在目标不存在时抛的是 Spring Data 的 EmptyResultDataAccessException,
 * 属于 DataAccessException 的一种; 全局异常处理器没有为它登记分支, 于是落到兜底的
 * handleOther 上变成 500. 这条链子跨了 service / 异常处理器 / 过滤器链三层,
 * 只测 service 是看不到 500 的.
 *
 * <p>两条用例是一对: 只验「不存在 -> 404」的话, 一个「永远返回 404」的实现照样绿,
 * 所以另一条把「存在的删得掉」也钉住 —— 一条评论从建出来、到删掉、再到第二次删
 * 变成 404, 走的是同一个 id.
 *
 * <p>用 MockMvc: 要验的是状态码与过滤器链, 不涉及异步/连接器行为. 内存库跑 dev
 * profile, 并关掉启动预加载(否则这个 JVM 会去调 api.bgm.tv, 让用例依赖外网).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-review-delete;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminReviewDeleteIntegrationTest {

    /** Bangumi 不可能返回的 id: 断言只在自己造的这一行上做, 不受任何真实数据影响. */
    private static final int SUBJECT_ID = 999101;

    private static final String NEVER_EXISTED_ID = "999999999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /** 注册一个普通用户并登录 —— 评论作者. 注册接口本身是公开的. */
    private String registerAuthor(String username) throws Exception {
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return login(username, password);
    }

    /** 让作者写一条评论, 返回它的 id(从列表接口里取, 因为保存接口不回 id). */
    private long authorWritesAReview(String token) throws Exception {
        mockMvc.perform(post("/api/review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + SUBJECT_ID + ",\"rating\":8,\"content\":\"删除用例造的数据\"}"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/review/list").param("subjectId", String.valueOf(SUBJECT_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        return data.get(0).path("id").asLong();
    }

    @Test
    @DisplayName("删一条从来没存在过的评论 -> 404 评论不存在, 而不是 500")
    void deletingAReviewThatNeverExistedIsNotFound() throws Exception {
        String adminToken = login("admin", "admin123");

        mockMvc.perform(delete("/api/admin/reviews/" + NEVER_EXISTED_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("评论不存在"));
    }

    @Test
    @DisplayName("同一条评论: 存在的删得掉(200), 删完再删一次变 404")
    void deletingAnExistingReviewRemovesItAndTheSecondAttemptIsNotFound() throws Exception {
        String authorToken = registerAuthor("delreviewauthor");
        long reviewId = authorWritesAReview(authorToken);
        String adminToken = login("admin", "admin123");

        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 真的没了: 列表里不再有它
        String body = mockMvc.perform(get("/api/review/list").param("subjectId", String.valueOf(SUBJECT_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        for (JsonNode item : data) {
            assertThatIdIsNot(item, reviewId);
        }

        // 再删一次: 同一条评论, 这次它已经不在了 —— 应当是 404, 而不是「删了两次」的 500
        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    private static void assertThatIdIsNot(JsonNode review, long deletedId) {
        if (review.path("id").asLong() == deletedId) {
            throw new AssertionError("评论 " + deletedId + " 应当已经被删除, 但它还在列表里: " + review);
        }
    }
}

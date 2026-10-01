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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 变成 400, 走的是同一个 id.
 *
 * <p><b>V14 起这里多了一条分界, 它是本类最重要的一句话: 404 与 400 是两个不同的答复.</b>
 * 「这个 id 上没有东西」是 404; 「这条评论已经被移除了, 别再删一次」是 400 —— 后者在管理端
 * 列表里明明看得见(标记着「已移除」), 回 404 会变成一句自相矛盾的谎话. 而且它挡掉的是
 * **第二笔账**: 重复点删除(或两个管理员同时点)不该在操作日志里写出两条 REVIEW_DELETE.
 *
 * <p>另一条用例钉住软删本身: 移除之后评论**从用户侧消失**(列表里没有它), 但**管理端能把它
 * 恢复**, 恢复之后又出现在用户侧列表里. 这三步是一个整体 —— 只说"删掉了"的话, 一个真的
 * 把行删掉的实现(改回 V14 之前的硬删)照样能让前两句绿, 只有恢复那一步会红.
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
    @DisplayName("同一条评论: 存在的删得掉(200), 删完再删一次变 400 该评论已被移除")
    void deletingAnExistingReviewRemovesItAndTheSecondAttemptIsRejected() throws Exception {
        String authorToken = registerAuthor("delreviewauthor");
        long reviewId = authorWritesAReview(authorToken);
        String adminToken = login("admin", "admin123");

        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 从用户侧真的不见了
        assertThatThePublicListDoesNotContain(reviewId, "评论 " + reviewId + " 应当已被移除");

        // 再删一次: 同一条评论, 它已经是"已移除"了 —— 400 而不是"删了两次"的 500,
        // 也不是 404(那一行还在管理端列表里给管理员看着, 见类注释)
        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该评论已被移除"));
    }

    /**
     * 软删的另一半: 能恢复, 而且恢复之后再恢复一次是 400.
     *
     * <p>"移除后能恢复"与"再恢复一次被挡住"要一起断, 与上面那条删除用例是同一个形状 ——
     * 只验前者的话, 一个"恢复接口永远回 200"的实现在账本里写出第二条 REVIEW_RESTORE,
     * 而操作日志页上看起来就是"恢复了两次".
     */
    @Test
    @DisplayName("恢复: 被移除的评论放回架上(用户侧又看得见), 再恢复一次变 400 该评论未被移除")
    void restoringPutsItBackOnTheShelf() throws Exception {
        String authorToken = registerAuthor("restorereviewauthor");
        long reviewId = authorWritesAReview(authorToken);
        String adminToken = login("admin", "admin123");

        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThatThePublicListDoesNotContain(reviewId, "前提: 移除之后用户侧看不见了");

        mockMvc.perform(put("/api/admin/reviews/" + reviewId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 真的回来了 —— 这一句同时证明上一句的"移除"是**软删**而不是删行:
        // 硬删过的行恢复不出来, 只会回 404「评论不存在」
        String body = mockMvc.perform(get("/api/review/list").param("subjectId", String.valueOf(SUBJECT_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        boolean found = false;
        for (JsonNode item : data) {
            found |= item.path("id").asLong() == reviewId;
        }
        if (!found) {
            throw new AssertionError("评论 " + reviewId + " 恢复之后应当重新出现在用户侧列表里, 实际: " + data);
        }

        mockMvc.perform(put("/api/admin/reviews/" + reviewId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该评论未被移除"));
    }

    /**
     * <b>被移除的评论在用户侧不只是"列表里没有", 它整个人都不见了。</b>
     *
     * <p>评论的 id 是一串连号数字, 而用户手上到处都是 id(分享链接、历史记录、别人发的截图)。
     * 只在列表上过滤掉、别处照常放行的话, 那都是"同一条评论的两副面孔": 用户在别人发的链接里
     * 点进去还能点赞、还能回复、还能举报一条他根本看不见的评论 —— 而这几件事**都是写**,
     * 写进去之后管理端看起来就是一条被移除的评论底下莫名其妙多了一堆互动。
     *
     * <p>六条路各来一次, 因为它们落在**四处不同的查询**上(评论仓储的 {@code findByIdWithUser}
     * 与 {@code existsByIdAndDeletedAtIsNull}、回复仓储上那句 join 条件): 只验一条的话,
     * 剩下的可以各自静默地漏着。这也是本类里唯一验得到那四处的地方 —— service 单测用的是
     * Mockito 桩, 桩不会去执行 JPQL。
     *
     * <p>顺带钉住"评论没了, 它下面的回复也读不出来": 那一步靠的是回复仓储上的 join 条件,
     * 与评论自己的过滤是两处代码。
     */
    @Test
    @DisplayName("被移除的评论: 点赞/取消赞/回复/读回复/举报/赞它下面的回复, 六条路全是 404")
    void aRemovedReviewIsGoneFromEveryUserFacingPath() throws Exception {
        String authorToken = registerAuthor("removedpathsowner");
        String otherToken = registerAuthor("removedpathsother");
        long reviewId = authorWritesAReview(authorToken);
        // 先在它下面留一条回复, 好在评论被移除之后验证"回复也跟着读不出来"
        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"先留一句\"}"))
                .andExpect(status().isOk());
        long replyId = firstReplyIdOf(reviewId);

        String adminToken = login("admin", "admin123");
        mockMvc.perform(delete("/api/admin/reviews/" + reviewId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        String bearer = "Bearer " + otherToken;
        mockMvc.perform(post("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("评论不存在"));
        mockMvc.perform(delete("/api/review/" + reviewId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/review/" + reviewId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"还回得去吗\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/review/" + reviewId + "/replies"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("评论不存在"));
        mockMvc.perform(post("/api/review/" + reviewId + "/report")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isNotFound());
        // 这一条走的是回复仓储: 回复行本身还在, 是它上面那句 join 把它挡住的
        mockMvc.perform(post("/api/reply/" + replyId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("回复不存在"));
    }

    /** 那条评论下的第一条回复的 id */
    private long firstReplyIdOf(long reviewId) throws Exception {
        String body = mockMvc.perform(get("/api/review/" + reviewId + "/replies"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").get(0).path("id").asLong();
    }

    /** 用户侧那条评论列表里不该有它 */
    private void assertThatThePublicListDoesNotContain(long reviewId, String reason) throws Exception {
        String body = mockMvc.perform(get("/api/review/list").param("subjectId", String.valueOf(SUBJECT_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode item : objectMapper.readTree(body).path("data")) {
            if (item.path("id").asLong() == reviewId) {
                throw new AssertionError(reason + ", 但它还在列表里: " + item);
            }
        }
    }
}

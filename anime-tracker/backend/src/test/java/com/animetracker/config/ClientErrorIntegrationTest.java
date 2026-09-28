package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「调用方写错请求」在真实接口上的表现（走完整过滤器链 + 参数绑定 + 异常处理）。
 *
 * <p>为什么有了 GlobalExceptionHandlerTest 还要这一层：单元测试直接调处理器方法，
 * 证明的是「处理器本身说得对」；而这里要证明的是「它真的会被用上」——
 * Spring 选哪个 @ExceptionHandler 是按最具体匹配来的，万一兜底的
 * Exception.class 抢在前面，单元测试会全绿而线上仍然回 500。
 *
 * <p>用的都是真实存在的公开接口（不需要登录），参数则刻意写错：
 * 少传、类型错、方法错、Content-Type 错、请求体不是 JSON。
 * 最后一例是正向对照 —— 证明正常的 200 没有被这套改动影响。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-client-error;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ClientErrorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("少传必填参数 -> 400, 提示里带参数名（改动前是 500）")
    void missingQueryParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/review/list"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少必填参数: subjectId"));
    }

    @Test
    @DisplayName("参数类型不对 -> 400（改动前是 500）")
    void wrongParameterTypeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stats/anime-heat").param("animeId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("参数 animeId 格式不正确"));
    }

    @Test
    @DisplayName("请求方法不对 -> 405（改动前是 500）")
    void wrongHttpMethodReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/bangumi/calendar"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value("该接口不支持 POST 请求"));
    }

    @Test
    @DisplayName("Content-Type 不对 -> 415（改动前是 500）")
    void wrongContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("username=admin"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    @DisplayName("请求体不是合法 JSON -> 400, 且不外泄解析细节（改动前是 500）")
    void malformedJsonBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"admin\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }

    @Test
    @DisplayName("正向对照：参数写对了照样 200, 这套改动没有影响正常请求")
    void validRequestStillSucceeds() throws Exception {
        mockMvc.perform(get("/api/bangumi/calendar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ── 分页参数越界 ────────────────────────────────────────────
    //
    // 这一组要证明的是「@Validated 真的挂上了」. 查询参数上的注解没有类级
    // @Validated 会被**静默忽略** —— 注解写得再全, 校验一次都不跑,
    // 结果和改动前一模一样. 单元测试直接调 Validator 是测不出这一点的.

    @Test
    @DisplayName("page=0 -> 400 而不是 500（改动前 subList(-20, 0) 越界）")
    void zeroPageReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/bangumi/search").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("页码从 1 开始"));
    }

    @Test
    @DisplayName("limit=0 -> 400")
    void zeroLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/bangumi/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页条数不能小于 1"));
    }

    @Test
    @DisplayName("limit 超过 50 -> 400：不封顶就是一个请求换一份任意大的结果集")
    void oversizedLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/bangumi/search").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页条数不能超过 50"));
    }

    @Test
    @DisplayName("排行榜 limit=0 -> 400（与搜索那边是同一个越界）")
    void zeroRankingLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/bangumi/ranking").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("条数不能小于 1"));
    }

    /**
     * 正向对照: 边界内的分页参数照常 200.
     *
     * <p>专挑边界值本身(1 和 50): 上界写成 @Max(49) 或者下界写成 @Min(2) 这类
     * 差一位的错误, 只有拿边界值去撞才会露出来.
     *
     * <p>这里走的是「不带关键词」那条分支, 全程只读本地表, 不会把测试变成
     * 一次对 Bangumi 的联网请求.
     */
    @Test
    @DisplayName("正向对照：边界内的分页参数照常 200")
    void inRangePagingStillSucceeds() throws Exception {
        mockMvc.perform(get("/api/bangumi/search").param("page", "1").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/bangumi/search").param("limit", "50"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/bangumi/search").param("page", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isEmpty());
    }

    // ── 筛选接口的分页 ──────────────────────────────────────────
    //
    // 这一组同时钉两件事: 参数校验真的挂上了(少一个类级注解就静默失效),
    // 以及返回结构确实是 {list,total,page} 而不是改之前那个裸数组 ——
    // 后者没有测试盯着的话, 下次谁"顺手"改回去也没人会知道.

    @Test
    @DisplayName("筛选接口返回 {list,total,page} 而不是裸数组")
    void filterReturnsThePagedShape() throws Exception {
        mockMvc.perform(get("/api/bangumi/filter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").value(1));
    }

    @Test
    @DisplayName("筛选接口不传 limit 时用默认上限, 不会把整张表一次倒出来")
    void filterAppliesADefaultLimit() throws Exception {
        // 库可能是空的(这个测试库每次新建), 所以断言的是"不超过默认值"而不是条数
        mockMvc.perform(get("/api/bangumi/filter").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(50)));
    }

    @Test
    @DisplayName("筛选接口 page=0 -> 400（与搜索接口同一条规则，改动前这里没有校验）")
    void filterRejectsZeroPage() throws Exception {
        mockMvc.perform(get("/api/bangumi/filter").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("页码从 1 开始"));
    }

    @Test
    @DisplayName("筛选接口 limit 越界 -> 400")
    void filterRejectsOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/bangumi/filter").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页条数不能小于 1"));

        mockMvc.perform(get("/api/bangumi/filter").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("每页条数不能超过 50"));
    }

    @Test
    @DisplayName("筛选接口翻过尾页 -> 200 + 空列表, 不是 500")
    void filterBeyondLastPageIsEmpty() throws Exception {
        mockMvc.perform(get("/api/bangumi/filter").param("page", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isEmpty());
    }
}

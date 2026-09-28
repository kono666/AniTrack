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
}

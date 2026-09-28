package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dev profile 下接口文档必须照旧可用 —— 这是上一条用例的对照组.
 *
 * <p>为什么非要有这一条: 「生产关掉接口文档」有两种写法, 一种对一种错, 而错的写法在
 * 生产那边看起来完全一样. 写对了是关在 postgres profile 里, 写错了是关在主配置块里
 * —— 后者会让本地 /swagger-ui.html 直接 404, 而只跑生产那一条用例是发现不了的
 * (它在生产 profile 下本来就该是 404). 所以这里从 dev 这一侧把「它还开着」钉住.
 *
 * <p>顺带把 dev 的现状写进断言: 这三条路径在 SecurityConfig 里只对 dev 放行, 而且是
 * **匿名**放行 —— 本地调试不该为了看一眼接口定义先去登录. 它之所以不算问题, 是因为
 * dev profile 只在本机使用; 一旦它被带上线(见 4.5 去掉主配置块里的 active: dev),
 * 这套东西连同 H2、admin/admin123 会一起暴露 —— 两条改动是互补的.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-apidocs-dev;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiDocsDevProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("dev: 接口文档匿名可访问, swagger-ui 也在")
    void docsStayAvailableInDev() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }
}

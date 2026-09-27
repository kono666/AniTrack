package com.animetracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用启动冒烟测试 — 验证 Spring 上下文能正常加载
 *
 * 刻意用内存库而不是配置里的文件库: 否则本地开着开发服务器时, H2 的文件锁会让
 * 这个测试必定失败, 而失败原因和代码毫无关系. 测试不该依赖「你有没有先关掉服务」.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-smoke;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("dev")
class AnimeTrackerApplicationTests {

    @Test
    void contextLoads() {
        // 验证所有 Bean 能正确装配
    }
}

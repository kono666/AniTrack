package com.animetracker;

import com.animetracker.config.AnimeBackfillRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

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

    /**
     * 全量回填**默认不装配**.
     *
     * <p>它是约 588 次外部请求、十分量级的长跑, 必须由人显式打开. 这条断言挡的是
     * 反向的坏法: 有人把 {@code anitrack.backfill.enabled} 的默认值改成 true, 或者
     * 把 {@code @ConditionalOnProperty} 去掉 —— 那样每次启动(以及 CI 每次冒烟)都会
     * 去抓三万个条目, 而且要过十分钟才发现.
     */
    @Test
    void backfillRunnerIsOffByDefault(ApplicationContext ctx) {
        assertThat(ctx.getBeanNamesForType(AnimeBackfillRunner.class))
                .as("没设 anitrack.backfill.enabled=true 时不该有这个 bean")
                .isEmpty();
    }
}

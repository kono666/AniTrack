package com.animetracker.config;

import com.animetracker.service.AnimeBackfillService;
import com.animetracker.service.AnimeBackfillService.BackfillResult;
import com.animetracker.service.AnimeBackfillService.StopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开关真的接上了: {@code anitrack.backfill.enabled=true} 时, 启动时那轮回填**确实跑了**.
 *
 * <p>为什么值得为此启一次 Spring: 这一路是"用户改一个环境变量, 然后等十分钟"的用法,
 * 而它最坏的失败方式是**什么都不发生** —— 属性名写错一个字母、{@code @ConditionalOnProperty}
 * 没被扫描器当成条件、线程没起来, 三种都是"应用照常启动、日志里没有回填"的样子, 而
 * 使用者会以为是自己配的 offset 不对. 纯单测覆盖不到这条缝(它验的是 service 内部的循环,
 * 而这里坏的是"那位循环有没有被叫起来"), 所以只能启一次容器来钉.
 *
 * <p>回填整条 service 被换成一个桩: 真的 {@code AnimeBackfillService} 会在这一步
 * **对着 api.bgm.tv 打出 588 次请求**(它跑起来就是那个意思). 测试绝不能碰外网.
 *
 * <p>这个桩必须在**容器刷新之前**就位, 所以用 {@link TestConfiguration} 而不是
 * {@code @MockBean} 加 {@code @BeforeEach}: 回填是 {@code ApplicationRunner}, 它在
 * 上下文刷新时就跑了, 而 {@code @BeforeEach} 在那之后 —— 那时候再 stub 已经晚了,
 * 桩会返回 null, 于是这里量到的是"没跑"而实际是"跑早了一步".
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-backfill-runner;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "anitrack.backfill.enabled=true"
})
@ActiveProfiles("dev")
class AnimeBackfillRunnerTest {

    /** 桩被调用的凭证. 静态是因为它必须在容器刷新前就存在 */
    private static final CountDownLatch CALLED = new CountDownLatch(1);

    @TestConfiguration
    static class StubBackfill {

        @Bean
        @Primary
        AnimeBackfillService stubBackfillService() {
            AnimeBackfillService stub = mock(AnimeBackfillService.class);
            when(stub.backfill()).thenAnswer(inv -> {
                CALLED.countDown();
                return new BackfillResult(1, 2, 2, 2, 470, 472, StopReason.END_OF_DATA);
            });
            return stub;
        }
    }

    @Test
    @DisplayName("开关打开: 启动时真的跑了一轮回填(不是静默什么都不做)")
    void runsOnStartupWhenEnabled() throws Exception {
        assertThat(CALLED.await(10, TimeUnit.SECONDS))
                .as("anitrack.backfill.enabled=true 时, 回填必须在启动阶段被叫起来一次")
                .isTrue();
    }
}

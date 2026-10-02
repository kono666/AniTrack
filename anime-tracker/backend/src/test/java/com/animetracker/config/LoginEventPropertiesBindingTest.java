package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code anitrack.login-event.retention} 真的绑到了 {@link LoginEventProperties} 上。
 *
 * <p>理由与 {@code ImageProxyPropertiesBindingTest} 逐字相同，但这一条比它更要紧：那边
 * 配错了顶多是"上限不是你以为的那个数"，这边配错了是**保留期不是你以为的那个数**，而它
 * 唯一的执行者是每天 03:43 那个 DELETE —— 一个绑不上的配置不会报错，只会让清理按默认值
 * 一直删下去，或者按默认值一直不删。两条路的症状都在几个月后才出现。
 *
 * <p>所以下面那个配置值**故意与代码默认值 180d 不同**。这一条在这个类里尤其不能省：
 * {@code application.yml} 里写的就是 {@code 180d}，与默认值一模一样 —— 前缀写错时
 * 绑定整个失效而行为一字不变，{@code LoginEventIntegrationTest} 里那条真删的用例照样绿。
 * 只有拿一个**不同的数**去绑，才能把"配置生效了"与"配置根本没读到"分开。
 */
class LoginEventPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableLoginEvent.class);

    @Test
    @DisplayName("配了就用配的: anitrack.login-event.retention 绑定生效(含单位换算)")
    void configuredValueWins() {
        runner.withPropertyValues("anitrack.login-event.retention=45d")
                .run(ctx -> {
                    LoginEventProperties props = ctx.getBean(LoginEventProperties.class);
                    assertThat(props.getRetention())
                            .as("与代码默认值 180d 不同, 才能证明是绑定来的而不是默认值")
                            .isEqualTo(Duration.ofDays(45));
                });
    }

    /**
     * 单位是 {@code Duration} 的转换器在管，而**写错单位不会报错**：{@code 30} 会被当成
     * 30 **毫秒**。结局是清理任务每次跑都把整张表删空 —— 那正是 {@code purgeExpired} 里
     * 「保留期为 0 就跳过」那道闸想拦、却拦不住的东西（它不是 0，它是一个正数）。
     *
     * <p>⚠️ 这条注释原写的是"30 纳秒"，是错的：{@code SubjectExtrasPropertiesBindingTest.
     * unitLessValueIsMilliseconds} 实测同一个转换器把裸数字解读成**毫秒**（30 → 30000000ns）。
     * 结论不变（仍然远小于 180 天，仍然拦不住），但数的量级差了一百万倍，所以照着改掉 ——
     * 一条写错的注释比没有注释更贵。
     *
     * <p>所以这里拿 {@code 30s} 去绑，断言的是**秒数**而不是原字符串。
     */
    @Test
    @DisplayName("带单位的写法按秒换算 —— 不带单位会被当成毫秒, 那道「0 就跳过」的闸拦不住")
    void unitLessValueIsNotSilentlyAccepted() {
        runner.withPropertyValues("anitrack.login-event.retention=30s")
                .run(ctx -> {
                    LoginEventProperties props = ctx.getBean(LoginEventProperties.class);
                    assertThat(props.getRetention().getSeconds())
                            .as("30s 是 30 秒, 不是 30 天也不是 30 纳秒")
                            .isEqualTo(30);
                });
    }

    @Test
    @DisplayName("一个都不配时保持默认值, 不会因为缺这段配置起不来")
    void defaultsWhenNothingConfigured() {
        runner.run(ctx -> assertThat(ctx.getBean(LoginEventProperties.class).getRetention())
                .isEqualTo(Duration.ofDays(180)));
    }

    /** 只注册这一个配置类, 不走组件扫描 —— 免得顺带拉起整个应用 */
    @EnableConfigurationProperties(LoginEventProperties.class)
    static class EnableLoginEvent {
    }
}

package com.animetracker.config;

import com.animetracker.config.SubjectExtrasProperties.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code anitrack.subject-extras.*} 真的绑到了 {@link SubjectExtrasProperties} 上。
 *
 * <p>理由与 {@code LoginEventPropertiesBindingTest} 同源, 而且这里有一个更安静的失效方式:
 * <b>ttl 配错了不会报错</b>, 只会让"多久重取一次"变成默认的 24 小时。而这一个参数是
 * 附属数据唯一的刷新机制 —— 没有定时任务, 没有别的入口。绑定失效的症状是"新角色
 * 要等很久才出现", 那既不像 bug 也不容易被报上来。
 *
 * <p>所以下面那个 ttl <b>故意与代码默认值 24h 不同</b>(而且单位也不同), 并且顺带验了
 * 不带单位的写法会被当成<b>纳秒</b> —— 那是 {@code Duration} 转换器的行为, 写 {@code 30}
 * 的人以为自己写的是 30 秒或 30 天, 实际是 30 纳秒, 于是这一块每次请求都回源。
 */
class SubjectExtrasPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableSubjectExtras.class);

    @Test
    @DisplayName("配了就用配的: ttl 与配额都绑定生效(含单位换算)")
    void configuredValuesWin() {
        runner.withPropertyValues(
                        "anitrack.subject-extras.ttl=45m",
                        "anitrack.subject-extras.rate-limit-per-minute=7")
                .run(ctx -> {
                    SubjectExtrasProperties props = ctx.getBean(SubjectExtrasProperties.class);
                    assertThat(props.getTtl())
                            .as("与代码默认值 24h 不同, 才能证明是绑定来的而不是默认值")
                            .isEqualTo(Duration.ofMinutes(45));
                    assertThat(props.getRateLimitPerMinute()).isEqualTo(7);
                });
    }

    /**
     * <b>不带单位时那串数字被解读成什么。</b>
     *
     * <p>结果是<b>毫秒</b>: 写 {@code 30} 得到的是 30 毫秒(<b>不是</b> 30 秒, 也不是 30 天),
     * 于是一个写着"30"的人以为自己配了 30 秒, 实际每 30 毫秒就算旧一次 —— 也就是这一块
     * <b>每次请求都回源</b>。而这一段配置在 {@code application.yml} 里是带着单位的,
     * 所以这个坑只在有人手改部署配置时才会踩到。
     *
     * <p>⚠️ <b>这条用例修正了一处流传的说法。</b> {@code LoginEventPropertiesBindingTest}
     * 的类注释里写着"30 会被当成 30 纳秒" —— 那份注释没有对应的断言, 而这里量出来是
     * <b>毫秒</b>(30 → 30000000ns)。断言 {@code toMillis()} 而不是"不等于 30 秒",
     * 是因为后者在一个更奇怪的解读下也会绿, 而这里要说清的是它到底变成了什么。
     */
    @Test
    @DisplayName("写 30(不带单位)会被当成 30 毫秒 —— 这一块于是每次都回源")
    void unitLessValueIsMilliseconds() {
        runner.withPropertyValues("anitrack.subject-extras.ttl=30")
                .run(ctx -> {
                    Duration ttl = ctx.getBean(SubjectExtrasProperties.class).getTtl();
                    assertThat(ttl.toNanos()).isEqualTo(30_000_000L);
                    assertThat(ttl.toMillis())
                            .as("裸数字按毫秒解读")
                            .isEqualTo(30L);
                    assertThat(ttl.getSeconds())
                            .as("不是 30 秒 —— 写配置的人多半以为它是")
                            .isZero();
                });
    }

    @Test
    @DisplayName("一个都不配时保持默认值: 24 小时, 每分钟 60 次")
    void defaultsWhenNothingConfigured() {
        runner.run(ctx -> {
            SubjectExtrasProperties props = ctx.getBean(SubjectExtrasProperties.class);
            assertThat(props.getTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(props.getRateLimitPerMinute()).isEqualTo(60);
            assertThat(props.getCharactersTtl()).isNull();
            assertThat(props.getStaffTtl()).isNull();
            assertThat(props.getRelationsTtl()).isNull();
        });
    }

    /**
     * <b>覆盖值优先, 没配的落回 {@code ttl}。</b>
     *
     * <p>这是 {@link SubjectExtrasProperties#ttlFor} 的全部行为, 而它决定了"哪一块多久
     * 重取一次"。三个调用点各写一遍三元表达式就会有一处漏掉覆盖值 —— 所以判断只留一处,
     * 这里把三种组合一次量掉。
     */
    @Test
    @DisplayName("ttlFor: 配了覆盖值的用它, 没配的落回 ttl")
    void perSectionTtlFallsBackToTheSharedOne() {
        runner.withPropertyValues(
                        "anitrack.subject-extras.ttl=12h",
                        "anitrack.subject-extras.characters-ttl=5m")
                .run(ctx -> {
                    SubjectExtrasProperties props = ctx.getBean(SubjectExtrasProperties.class);

                    assertThat(props.ttlFor(Section.CHARACTERS)).isEqualTo(Duration.ofMinutes(5));
                    assertThat(props.ttlFor(Section.STAFF))
                            .as("没配的那两块必须落回 ttl, 而不是变成 null 或者 0")
                            .isEqualTo(Duration.ofHours(12));
                    assertThat(props.ttlFor(Section.RELATIONS)).isEqualTo(Duration.ofHours(12));
                });
    }

    /**
     * 只注册这一个配置类, 不走组件扫描 —— 免得顺带拉起整个应用。
     *
     * <p>注意 {@code SubjectExtrasProperties} 自己带着 {@code @Component}, 而这里用的是
     * {@code @EnableConfigurationProperties}: 两个入口都行, 但走这个才与
     * {@code LoginEventPropertiesBindingTest} 一致, 也才不会被组件扫描拖出一整个上下文。
     */
    @EnableConfigurationProperties(SubjectExtrasProperties.class)
    static class EnableSubjectExtras {
    }
}

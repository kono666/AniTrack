package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code anitrack.ranking.*} 真的绑到了 {@link RankingProperties} 上.
 *
 * <p>为什么这件事要单独验: 属性名写错(少个字母、连字符位置不对)时 Spring **不报错**,
 * 字段就静静保持代码里的默认值 —— 于是行为上完全看不出区别, 只有"改了配置却不生效"
 * 的时候才奇怪. 而这一组用的正是"配置值故意取得与默认值不同"的写法: 绑定没生效时
 * 断言会红. 反过来, 如果这里断言的是默认值(200 / 7.0), 那么无论绑定成没成都是绿的,
 * 等于什么都没验 —— 这是配置类测试最容易写成空转的地方.
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}: 这里要验的是
 * 两个字段的绑定, 不该顺带把整个应用、数据库、以及会联网的启动预加载器一起拉起来
 * (理由同 {@code AnimeServicePagingTest} 里那段"不写成 @SpringBootTest 是刻意的").
 */
class RankingPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableRanking.class);

    @Test
    @DisplayName("配了就用配的: anitrack.ranking.* 绑定生效")
    void configuredValuesWin() {
        runner.withPropertyValues(
                        "anitrack.ranking.prior-votes=1234.5",
                        "anitrack.ranking.prior-score=3.25")
                .run(ctx -> {
                    RankingProperties props = ctx.getBean(RankingProperties.class);
                    assertThat(props.getPriorVotes())
                            .as("与代码默认值 200 不同, 才能证明是绑定来的而不是默认值")
                            .isEqualTo(1234.5);
                    assertThat(props.getPriorScore())
                            .as("与代码默认值 7.0 不同")
                            .isEqualTo(3.25);
                });
    }

    @Test
    @DisplayName("一个都不配时保持默认值, 不会因为缺这段配置起不来")
    void defaultsWhenNothingConfigured() {
        runner.run(ctx -> {
            RankingProperties props = ctx.getBean(RankingProperties.class);
            assertThat(props.getPriorVotes()).isEqualTo(200.0);
            assertThat(props.getPriorScore()).isEqualTo(7.0);
        });
    }

    /** 只注册这一个配置类, 不走组件扫描 —— 免得顺带拉起整个应用 */
    @EnableConfigurationProperties(RankingProperties.class)
    static class EnableRanking {
    }
}

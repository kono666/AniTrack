package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次提问的整体超时是怎么算出来的.
 *
 * <p>这个值决定「一次提问最多跑多久」, 而它只有两个取值来源: 显式配置, 或者按
 * 轮数与单次调用超时推导. 推导错了的两个方向都不好受 —— 算短了会在正常但慢的请求上
 * 误杀(前端看到流断了), 算长了会让卡住的请求多占十几分钟线程与额度.
 *
 * <p>所以这里把口径钉住: 是 maxToolRounds 次而不是 maxToolRounds + 1 次. 多算一次
 * 在默认值下就是凭空多出 90 秒, 而它不会有任何别的信号.
 */
class LlmPropertiesTest {

    @Test
    @DisplayName("默认按 maxToolRounds × readTimeout + 余量推导")
    void derivesFromRoundsAndReadTimeout() {
        LlmProperties props = new LlmProperties();
        props.setMaxToolRounds(6);
        props.setReadTimeout(90_000);

        // 6 × 90 秒 + 60 秒余量 = 10 分钟
        assertThat(props.effectiveOverallTimeoutMs()).isEqualTo(600_000L);
        assertThat(props.getOverallTimeoutMs()).as("默认是 0, 表示自动推导").isZero();
    }

    @Test
    @DisplayName("配了具体值就以它为准, 不再推导")
    void explicitValueWins() {
        LlmProperties props = new LlmProperties();
        props.setMaxToolRounds(6);
        props.setReadTimeout(90_000);
        props.setOverallTimeoutMs(1_000);

        assertThat(props.effectiveOverallTimeoutMs()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("轮数配成 0 或负数时, 至少也要留一次调用的时间")
    void neverCollapsesToJustTheSlack() {
        LlmProperties props = new LlmProperties();
        props.setMaxToolRounds(0);
        props.setReadTimeout(90_000);

        assertThat(props.effectiveOverallTimeoutMs())
                .as("轮数是 0 属于配错了, 但不该让超时塌成 60 秒")
                .isEqualTo(150_000L);
    }
}

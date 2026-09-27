package com.animetracker.agent;

import com.animetracker.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提问频率限制测试.
 *
 * 这是唯一挡住「有人写个循环把 API 额度刷爆」的东西, 必须真的生效.
 */
class AgentRateLimiterTest {

    private AgentRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AgentRateLimiter();
    }

    @Test
    @DisplayName("配额内放行, 超出后抛 429")
    void blocksAfterQuotaExceeded() {
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> limiter.check("user:1", 3)).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check("user:1", 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("太频繁")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("不同用户各算各的: 一个人被限流不该影响其他人")
    void isolatesKeys() {
        for (int i = 0; i < 3; i++) {
            limiter.check("user:1", 3);
        }
        assertThatThrownBy(() -> limiter.check("user:1", 3)).isInstanceOf(BusinessException.class);

        // 另一个用户和另一个 IP 都还有完整配额
        assertThatCode(() -> limiter.check("user:2", 3)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("ip:203.0.113.7", 3)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("匿名访客按 IP 限流, 与登录用户互不干扰")
    void anonymousAndLoggedInAreSeparate() {
        limiter.check("ip:198.51.100.9", 2);
        limiter.check("ip:198.51.100.9", 2);

        assertThatThrownBy(() -> limiter.check("ip:198.51.100.9", 2))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.check("user:9", 2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("key 为 null 时也不该 NPE")
    void toleratesNullKey() {
        assertThatCode(() -> limiter.check(null, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(null, 1)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("reset 之后配额恢复, 便于测试与运维介入")
    void resetClearsCounters() {
        limiter.check("user:1", 1);
        assertThatThrownBy(() -> limiter.check("user:1", 1)).isInstanceOf(BusinessException.class);

        limiter.reset();
        assertThatCode(() -> limiter.check("user:1", 1)).doesNotThrowAnyException();
    }
}

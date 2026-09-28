package com.animetracker.config;

import com.animetracker.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 滑动窗口的机制本身.
 *
 * <p>{@link com.animetracker.agent.AgentRateLimiterTest} 盯的是「提问」那条路的对外行为,
 * 这一份盯机制: 键之间互不影响、提示语里带动作名、重置之后配额回来.
 *
 * <p>两者不重复 —— 机制是从 AgentRateLimiter 里抽出来的, 抽完必须证明
 * 「换成新位置之后机制还是原来那套行为」, 而这条只有直接对着机制测才看得见.
 */
class SlidingWindowRateLimiterTest {

    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new SlidingWindowRateLimiter();
    }

    @Test
    @DisplayName("配额内放行, 超出后抛 429")
    void blocksAfterQuotaExceeded() {
        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> limiter.check("ip:1", 3, "提问")).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.check("ip:1", 3, "提问"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("不同键各算各的: 一个人被限流不该影响其他人")
    void isolatesKeys() {
        for (int i = 0; i < 3; i++) {
            limiter.check("user:1", 3, "提问");
        }
        assertThatThrownBy(() -> limiter.check("user:1", 3, "提问"))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> limiter.check("user:2", 3, "提问")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("ip:203.0.113.7", 3, "提问")).doesNotThrowAnyException();
    }

    /**
     * 提示语要能直接展示给用户, 所以动作名与当前上限都得在里面.
     *
     * <p>这条同时钉住「机制里不该出现业务措辞」: 同一个类既能对提问说「提问太频繁了」,
     * 也能对注册说「注册太频繁了」——那些词是调用方传进来的, 不是写死在机制里的.
     */
    @Test
    @DisplayName("提示语由调用方传入的动作名拼出来")
    void messageNamesTheActionAndTheLimit() {
        limiter.check("register:ip:198.51.100.1", 1, "注册");

        assertThatThrownBy(() -> limiter.check("register:ip:198.51.100.1", 1, "注册"))
                .hasMessage("注册太频繁了, 请稍等一分钟再试 (当前上限 1 次/分钟)");

        limiter.check("login:ip:198.51.100.1", 1, "登录");
        assertThatThrownBy(() -> limiter.check("login:ip:198.51.100.1", 1, "登录"))
                .hasMessage("登录太频繁了, 请稍等一分钟再试 (当前上限 1 次/分钟)");
    }

    @Test
    @DisplayName("key 为 null 时也不该 NPE")
    void toleratesNullKey() {
        assertThatCode(() -> limiter.check(null, 1, "提问")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(null, 1, "提问")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("reset 之后配额恢复, 便于测试与运维介入")
    void resetClearsCounters() {
        limiter.check("ip:1", 1, "提问");
        assertThatThrownBy(() -> limiter.check("ip:1", 1, "提问")).isInstanceOf(BusinessException.class);

        limiter.reset();
        assertThatCode(() -> limiter.check("ip:1", 1, "提问")).doesNotThrowAnyException();
    }
}

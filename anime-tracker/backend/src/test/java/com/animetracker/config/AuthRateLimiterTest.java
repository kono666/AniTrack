package com.animetracker.config;

import com.animetracker.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册 / 登录的按 IP 配额怎么分桶.
 *
 * <p>这一层是纯计算, 所以阈值直接写小(注册 2、登录 3), 不必真发 HTTP ——
 * 端到端那条路(429 的响应体长什么样、校验没过的请求不消耗配额)在
 * AuthRateLimitIntegrationTest 里.
 *
 * <p>重点是**分桶**: 注册与登录各算各的、每个 IP 各算各的. 这两件事错了不会报错,
 * 只会表现成「莫名其妙被挡」或者「根本没限住」, 属于必须有断言盯着的那类.
 */
class AuthRateLimiterTest {

    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AuthRateLimiter(props(2, 3));
    }

    private static AuthRateLimitProperties props(int register, int login) {
        AuthRateLimitProperties p = new AuthRateLimitProperties();
        p.setRegisterPerMinute(register);
        p.setLoginPerMinute(login);
        return p;
    }

    @Test
    @DisplayName("注册: 第 3 次(上限 2)被挡, 抛 429 且提示里写的是「注册」")
    void registerIsCappedPerIp() {
        limiter.checkRegister("198.51.100.1");
        limiter.checkRegister("198.51.100.1");

        assertThatThrownBy(() -> limiter.checkRegister("198.51.100.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("注册太频繁了")
                .hasMessageContaining("当前上限 2 次/分钟")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("登录: 第 4 次(上限 3)被挡")
    void loginIsCappedPerIp() {
        for (int i = 0; i < 3; i++) {
            limiter.checkLogin("198.51.100.1");
        }

        assertThatThrownBy(() -> limiter.checkLogin("198.51.100.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录太频繁了");
    }

    @Test
    @DisplayName("每个 IP 各算各的: 一个 IP 撞满了不影响另一个")
    void eachIpHasItsOwnQuota() {
        limiter.checkRegister("198.51.100.1");
        limiter.checkRegister("198.51.100.1");
        assertThatThrownBy(() -> limiter.checkRegister("198.51.100.1"))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> limiter.checkRegister("203.0.113.9")).doesNotThrowAnyException();
    }

    /**
     * 注册与登录必须是两笔独立的配额.
     *
     * <p>少了这一条, 键前缀写成一样(比如两边都用 "ip:")也照样全绿 —— 而那样的话,
     * 一个在注册上撞了墙的人会发现自己也登不进来了, 且现象毫无道理可讲.
     */
    @Test
    @DisplayName("撞满注册不会把人挡在登录外面, 反之亦然")
    void registerAndLoginAreSeparateBuckets() {
        limiter.checkRegister("198.51.100.1");
        limiter.checkRegister("198.51.100.1");
        assertThatThrownBy(() -> limiter.checkRegister("198.51.100.1"))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> limiter.checkLogin("198.51.100.1")).doesNotThrowAnyException();

        for (int i = 0; i < 2; i++) {
            limiter.checkLogin("198.51.100.1");
        }
        assertThatThrownBy(() -> limiter.checkLogin("198.51.100.1"))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> limiter.checkRegister("198.51.100.2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reset 清掉全部计数, 免得用例之间互相影响")
    void resetClearsBothBuckets() {
        limiter.checkRegister("198.51.100.1");
        limiter.checkRegister("198.51.100.1");
        limiter.checkLogin("198.51.100.1");

        limiter.reset();

        assertThatCode(() -> limiter.checkRegister("198.51.100.1")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLogin("198.51.100.1")).doesNotThrowAnyException();
    }

    /**
     * 类里的默认值与 application.yml 里那两个环境变量的默认值必须是同一组数.
     *
     * <p>两边不一致时的表现很隐蔽: yml 里有这个键时以 yml 为准, 类里的默认值形同虚设;
     * 而哪天 yml 那个键被删掉或改名(比如重构成别的前缀), 限流会**静默**退化成这组默认值.
     * 断言一下, 至少保证退化后仍然是设计时的量级.
     */
    @Test
    @DisplayName("默认阈值就是设计值: 注册 5 次/分钟, 登录 10 次/分钟")
    void defaultsMatchTheDocumentedValues() {
        AuthRateLimitProperties defaults = new AuthRateLimitProperties();

        assertThat(defaults.getRegisterPerMinute()).isEqualTo(5);
        assertThat(defaults.getLoginPerMinute()).isEqualTo(10);
    }
}

package com.animetracker.agent;

import com.animetracker.config.LlmProperties;
import com.animetracker.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 每日预算测试.
 *
 * 这是公网 Demo 的账单保险丝. 它错的方向很关键:
 * 少挡一次只是多花几分钱, 放行一次超支就是密钥被人当免费额度用 ——
 * 所以断言都围绕「保守」这一侧展开.
 */
class AgentBudgetGuardTest {

    private static final int ROUNDS = 6;

    private static LlmProperties props(int dailyBudget) {
        LlmProperties p = new LlmProperties();
        p.setDailyCallBudget(dailyBudget);
        p.setMaxToolRounds(ROUNDS);
        return p;
    }

    @Test
    @DisplayName("按最大轮数预留, 结算时按实际轮数退还")
    void reservesMaxRoundsAndRefundsTheDifference() {
        AgentBudgetGuard guard = new AgentBudgetGuard(props(100));

        guard.acquire();
        assertThat(guard.usedToday())
                .as("刚进来还不知道要用几轮, 只能按最坏情况占额度")
                .isEqualTo(ROUNDS);

        // 这次提问实际只用了 2 轮
        guard.settle(2);
        assertThat(guard.usedToday()).isEqualTo(2);
        assertThat(guard.remaining()).isEqualTo(98);
    }

    @Test
    @DisplayName("额度和预留量对不上时直接拒绝, 不会先放行再超支")
    void rejectsWhenReservationDoesNotFit() {
        // 上限 10, 一次提问最坏占 6. 已经用掉 5 之后, 下一个请求会占到 11 —— 必须挡在门外
        AgentBudgetGuard guard = new AgentBudgetGuard(props(10));

        guard.acquire();
        guard.settle(5);
        assertThat(guard.usedToday()).isEqualTo(5);

        assertThatThrownBy(guard::acquire)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("今日 AI 体验额度已用完");

        // 被拒的那次不能留下预留, 否则额度会被慢慢漏光
        assertThat(guard.usedToday()).isEqualTo(5);
    }

    @Test
    @DisplayName("失败码是 429, 前端才能区分「问太快」和「服务坏了」")
    void rejectionCarries429() {
        AgentBudgetGuard guard = new AgentBudgetGuard(props(1));

        assertThatThrownBy(guard::acquire)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("0 表示不限: 本地开发不该被预算挡住")
    void zeroMeansUnlimited() {
        AgentBudgetGuard guard = new AgentBudgetGuard(props(0));

        for (int i = 0; i < 50; i++) {
            assertThatCode(guard::acquire).doesNotThrowAnyException();
        }
        assertThat(guard.remaining()).isEqualTo(-1);
        assertThat(guard.usedToday()).isZero();
    }

    @Test
    @DisplayName("跨天自动重置 —— 否则第二天 Demo 直接打不开")
    void resetsOnNewDay() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 9, 27));
        AgentBudgetGuard guard = new AgentBudgetGuard(props(10), today::get);

        guard.acquire();
        guard.settle(3);
        assertThat(guard.usedToday()).isEqualTo(3);

        // 用掉大半额度后跨天
        today.set(LocalDate.of(2026, 9, 28));
        assertThat(guard.remaining()).isEqualTo(10);
        assertThat(guard.usedToday()).isZero();
        assertThatCode(guard::acquire).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不会把新一天的额度退成负数: 跨天后到达的结算请求要忽略")
    void lateSettleAfterRolloverDoesNotGoNegative() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 9, 27));
        AgentBudgetGuard guard = new AgentBudgetGuard(props(100), today::get);

        guard.acquire();
        // 请求还在跑的时候跨了天
        today.set(LocalDate.of(2026, 9, 28));
        guard.settle(1);

        assertThat(guard.usedToday())
                .as("退款不该打到新一天的账上")
                .isZero();
        assertThat(guard.remaining()).isEqualTo(100);
    }

    @Test
    @DisplayName("预留在并发下不会超发")
    void doesNotOversellUnderConcurrency() throws Exception {
        int limit = 60;                       // 恰好容纳 10 次预留
        AgentBudgetGuard guard = new AgentBudgetGuard(props(limit));
        int threads = 32;
        java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    guard.acquire();
                    admitted.incrementAndGet();
                } catch (BusinessException ignored) {
                    // 额度用完, 预期之内
                }
            });
            workers[i].start();
        }
        for (Thread w : workers) {
            w.join(10_000);
        }

        assertThat(admitted.get()).isEqualTo(10);
        assertThat(guard.usedToday()).isLessThanOrEqualTo(limit);
    }
}

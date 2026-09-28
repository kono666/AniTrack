package com.animetracker.agent;

import com.animetracker.config.LlmProperties;
import com.animetracker.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * 全站每日模型调用预算.
 *
 * 和 {@link AgentRateLimiter} 是两件事, 别混:
 * <ul>
 *   <li>限流管「一个人刷得太快」, 按用户/IP 分摊, 一分钟的窗口;</li>
 *   <li>预算管「所有人加起来今天花了多少」, 全站共享, 一天的窗口.</li>
 * </ul>
 *
 * 公网 Demo 上真正会出事的是后者. 密钥放在服务器上, 谁都能来问, 而单靠
 * 「每人 10 次/分钟」挡不住「一百个人同时各问 10 次」—— 每个人都合规,
 * 账单照样能翻上去. 这一层是给账单兜底的最后一道闸.
 *
 * <b>计数单位是「模型调用次数」而不是「提问次数」.</b> 一次提问最多会触发
 * maxToolRounds 次模型调用, 按提问计数会把真实开销低估好几倍 —— 而预算这种东西,
 * 估错的代价就是它失效.
 *
 * 实现上先按最大轮数预留、结束后按实际轮数退还. 预留方向刻意偏保守:
 * 宁可少放几个请求进来, 也不会出现「以为还有额度、实际早就超了」.
 * 反向设计(先放行、事后补记)在并发下必然超支, 那就失去意义了.
 *
 * 局限: 计数在单机内存里, 重启即清零, 多实例部署时各算各的.
 * 对单实例的作品集 Demo 够用; 真要严格计费得落到 Redis 或数据库.
 */
@Component
public class AgentBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(AgentBudgetGuard.class);

    private final LlmProperties props;
    private final Supplier<LocalDate> clock;

    private final Object lock = new Object();
    private LocalDate day;
    private int used;

    /**
     * 主构造器.
     *
     * 必须显式标 @Autowired: 一旦类里有多个构造器, Spring 就不再自动挑「唯一那个」,
     * 而是转头去找无参构造器, 找不到就在启动时直接报错.
     */
    @Autowired
    public AgentBudgetGuard(LlmProperties props) {
        this(props, LocalDate::now);
    }

    /**
     * 允许注入时钟的构造器, 只为测试跨天重置.
     *
     * 这段逻辑一天才走一次, 靠手工验证等于不验证; 而它一旦失效,
     * 表现是「额度不恢复了, Demo 第二天还是打不开」—— 很难联想到这里.
     */
    AgentBudgetGuard(LlmProperties props, Supplier<LocalDate> clock) {
        this.props = props;
        this.clock = clock;
        this.day = clock.get();
    }

    /**
     * 预扣一次提问的额度. 额度不足时抛出业务异常, 请求根本不会打到模型上.
     *
     * 放在流式接口建流之前调用, 被拒时前端拿到的是普通 JSON 而不是一条 SSE 错误事件.
     */
    public void acquire() {
        int limit = props.getDailyCallBudget();
        if (limit <= 0) {
            return;
        }
        int reserve = reserveSize();
        synchronized (lock) {
            rollover();
            if (used + reserve > limit) {
                throw new BusinessException(429,
                        "今日 AI 体验额度已用完, 明天再来。"
                                + "(全站每日上限 " + limit + " 次模型调用, 已用 " + used + ")");
            }
            used += reserve;
        }
    }

    /**
     * 一次提问结束后按实际轮数结算, 把多预留的退回去.
     *
     * 只在成功跑完后调用. 中途抛异常的请求不退 —— 因为拿不到「实际调用了几次」,
     * 而少退(多算)只是让额度更早用完, 多退(少算)才真的会让账单失控.
     */
    public void settle(int actualCalls) {
        int limit = props.getDailyCallBudget();
        if (limit <= 0) {
            return;
        }
        int refund = reserveSize() - Math.max(1, actualCalls);
        if (refund <= 0) {
            return;
        }
        synchronized (lock) {
            // 跨零点时可能已经翻篇, 不能把新一天的额度退成负的
            if (day.equals(clock.get())) {
                used = Math.max(0, used - refund);
            }
        }
    }

    /**
     * 退还一次「预扣了但一次都没跑」的额度.
     *
     * <p>与 {@link #settle(int)} 的分工: settle 用于「跑完了, 但实际轮数比预留的少」,
     * 它至少算一次调用(模型确实被调过); release 用于「压根没跑起来」—— 目前只有一种
     * 情况: 预扣之后提交给 worker 池时被拒绝(池子满了). 那种情况下模型一次都没调,
     * 整份预留必须原样退回.
     *
     * <p>不退还的后果不是「少了几分钱」, 而是个正反馈: 服务越忙 -> 被拒的请求越多 ->
     * 额度掉得越快, 最后恰好在最需要额度的时候把额度耗光, 而那笔钱一分都没花出去.
     *
     * <p>跨天与 settle 同样处理: 退款不能打到新一天的账上.
     */
    public void release() {
        int limit = props.getDailyCallBudget();
        if (limit <= 0) {
            return;
        }
        synchronized (lock) {
            if (day.equals(clock.get())) {
                used = Math.max(0, used - reserveSize());
            }
        }
    }

    /** 今日剩余可用的模型调用次数; 未设上限时返回 -1 */
    public int remaining() {
        int limit = props.getDailyCallBudget();
        if (limit <= 0) {
            return -1;
        }
        synchronized (lock) {
            rollover();
            return Math.max(0, limit - used);
        }
    }

    /** 今日已用 (含尚未结算的预留); 未设上限时返回 0 */
    public int usedToday() {
        synchronized (lock) {
            rollover();
            return used;
        }
    }

    public int dailyLimit() {
        return props.getDailyCallBudget();
    }

    /** 清空计数, 供测试使用 */
    public void reset() {
        synchronized (lock) {
            day = clock.get();
            used = 0;
        }
    }

    /** 预留粒度: 按最大轮数留, 结束后按实际轮数退 */
    private int reserveSize() {
        return Math.max(1, props.getMaxToolRounds());
    }

    /** 跨天则清零. 调用方必须持有 lock. */
    private void rollover() {
        LocalDate today = clock.get();
        if (!today.equals(day)) {
            log.info("每日预算跨天重置: {} -> {}, 昨日已用 {}", day, today, used);
            day = today;
            used = 0;
        }
    }
}

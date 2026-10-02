package com.animetracker.service;

import com.animetracker.config.LoginEventProperties;
import com.animetracker.entity.LoginEvent;
import com.animetracker.repository.LoginEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录事件这一层的规则测试: <b>写永远不抛</b>, 以及保留期清理里那条"0 不等于清空"。
 *
 * <p>聚合口径(日活/周活/曲线/数据不足)不在这里 —— 那几条要真库才验得动, 在
 * {@code LoginEventDashboardIntegrationTest} 里, 理由与 {@code UserServiceTest} 顶上
 * 写的那条一样: mock 看不见 SQL 干了什么。
 */
class LoginEventServiceTest {

    /** RFC 5737 的文档网段, 不会与真实地址撞上 */
    private static final String CLIENT_IP = "203.0.113.7";

    private LoginEventRepository repository;
    private LoginEventProperties props;
    private LoginEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(LoginEventRepository.class);
        props = new LoginEventProperties();
        // 用真的 IsolatedInsert: 它本身只是一句 insert.get(), 没有容器时就是"直接调用",
        // 于是这里量到的还是"回调有没有被执行、异常会跑到哪".
        service = new LoginEventService(repository, props, new IsolatedInsert());
    }

    // ========== 写: 这辈子不会抛 ==========

    @Test
    @DisplayName("把调用方给的三个字段原样写下去(包括 userId 为 null 的那一类失败)")
    void recordsWhatItWasGiven() {
        service.recordAttempt(null, CLIENT_IP, false);

        ArgumentCaptor<LoginEvent> captor = ArgumentCaptor.forClass(LoginEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getIp()).isEqualTo(CLIENT_IP);
        assertThat(captor.getValue().isSuccess()).isFalse();
    }

    /**
     * <b>这一条是整个改动里最不能少的一条。</b>
     *
     * <p>{@code recordAttempt} 是在登录的判定做完之后被调用的旁路: 它旁边那一行
     * {@code throw} 才是用户要的结果。它要是把异常漏出去, 一次**密码完全正确**的登录
     * 会变成 500 —— 拿用户的登录换我们的一张统计图, 这笔账怎么算都不划算。
     *
     * <p>逐个试几种不同的坏法, 而不是只挑一种: 这条约定是"兜住 Exception", 所以它得
     * 对**任意**异常成立。只测 {@code DataIntegrityViolationException} 的话, 哪天有人把
     * {@code catch} 收窄成它, 一个连接池超时的 {@code IllegalStateException} 照样能把
     * 登录打崩而用例全绿。
     */
    @Test
    @DisplayName("仓储无论怎么坏, recordAttempt 都不抛")
    void recordingNeverPropagates() {
        List<RuntimeException> ways = List.of(
                new DataIntegrityViolationException("字段超长"),
                new IllegalStateException("连接池拿不到连接"),
                new NullPointerException("Hibernate 那边炸了"));

        for (RuntimeException boom : ways) {
            when(repository.save(any(LoginEvent.class))).thenThrow(boom);

            assertThatCode(() -> service.recordAttempt(7L, CLIENT_IP, true))
                    .as("%s 不该漏到调用方 —— 漏出去就是一次成功的登录被判成 500",
                            boom.getClass().getSimpleName())
                    .doesNotThrowAnyException();
        }
    }

    // ========== 保留期清理 ==========

    @Test
    @DisplayName("按保留期算出的时刻去删: 180 天前之前的一条不留")
    void purgesEverythingOlderThanTheRetention() {
        props.setRetention(Duration.ofDays(180));

        service.purgeExpired();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteOlderThan(captor.capture());

        // 断"界落在 180 天前"而不是"差了多少天": 后者是一件看精度的事 —— 两次 now() 落在
        // 同一微秒里时差值恰好是整 180 天, 差一点就变成 179, 于是一条正确的实现能随机变红。
        assertThat(Duration.between(captor.getValue(),
                LocalDateTime.now().minus(Duration.ofDays(180))).abs())
                .as("界就是 现在−保留期")
                .isLessThan(Duration.ofSeconds(5));
    }

    /**
     * <b>保留期配成 0 是"关掉清理", 不是"清空这张表"。</b>
     *
     * <p>照字面算的话, 界会变成"早于现在", 一条 DELETE 把全部历史带走 —— 而写下那个 0
     * 的人多半想的是"先别清"。这是这个类里唯一后果不可逆的分支, 所以它单独有一条用例。
     */
    @Test
    @DisplayName("保留期配成 0 或负数时一条都不删(那是「关掉清理」的意思, 不是「清空」)")
    void disabledRetentionDeletesNothing() {
        for (Duration bad : List.of(Duration.ZERO, Duration.ofDays(-1))) {
            props.setRetention(bad);

            service.purgeExpired();

            verify(repository, never()).deleteOlderThan(any(LocalDateTime.class));
        }
    }

    /** 删不掉不是急事(多留一天而已), 但它绝不能让定时任务抛出去 —— 那会在日志里变成另一件事 */
    @Test
    @DisplayName("删除失败时 purgeExpired 不抛, 留到明天再试")
    void purgeFailureIsSwallowed() {
        when(repository.deleteOlderThan(any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("表被锁住了"));

        assertThatCode(() -> service.purgeExpired()).doesNotThrowAnyException();
    }
}

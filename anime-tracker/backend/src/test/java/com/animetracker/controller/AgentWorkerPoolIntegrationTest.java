package com.animetracker.controller;

import com.animetracker.agent.AgentBudgetGuard;
import com.animetracker.agent.AgentOrchestrator;
import com.animetracker.agent.AgentResult;
import com.animetracker.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * worker 池被占满时的行为: 后来者拿 503, 而且**不会白扣每日额度**.
 *
 * <p>池子大小与排队上限是控制器里的常量(4 + 16), 所以「一次打满」需要 21 个并发请求.
 * 整体超时只有 300 毫秒, 于是: 客户端的每个请求都会很快拿到答复(504), 而 worker 上的
 * 任务还堵在桩里 —— 池子在整个用例期间都是占满的, 后来者必然被拒.
 *
 * <p><b>桩用闩锁, 不用 Thread.sleep.</b> 原来这里睡 10 秒, 隐含假设是「断言一定在这
 * 10 秒之内跑完」. 这个假设靠不住 —— 断言读的是「此刻已用额度」, 而 worker 一跑完就会
 * settle 退掉多预留的部分, 于是用例只要被拖慢到超过 10 秒, 读到的数就偏小.
 *
 * <p>CI 上真的踩到了, 记在这里免得下一个人重新查一遍: 套件跑到本用例时 JVM 已启动
 * 60.28 秒, 恰好越过 {@code DataRefreshService} 的 initialDelay=60s, 那次定时刷新
 * (要发真网络请求) 把用例拖到 23 秒; 期间两个批次的 worker 睡醒跑完, 各 settle 退 5,
 * 已用额度从 120 掉到 80, 断言失败. 当时 8 个绿色 run 的启动时长都在 40.5~53.1 秒之间,
 * 全在 60 秒这条线以下 —— 也就是说, 这条用例一直贴着悬崖边过, 是升级带来的额外启动
 * 开销把它推了过去. 换成闩锁之后, worker 停多久由用例说了算, 与机器快慢、
 * 后台有没有别的事在跑都无关.
 *
 * <p>额度那一条是本用例的重点: 预扣发生在提交之前, 被拒的请求模型一次都没调,
 * 那份预留必须退回去. 不退还的后果是个正反馈 —— 服务越忙, 额度掉得越快.
 * 断言用**实际收到的答复**算出被接受了几次, 所以不依赖「恰好拒绝 5 个」这种时序假设.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-agent-pool;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "llm.overall-timeout-ms=300",
        "llm.rate-limit-per-minute=1000",
        // 额度要够大(25 × 6 = 150), 否则撞的是预算而不是线程池
        "llm.daily-call-budget=400",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class AgentWorkerPoolIntegrationTest {

    private static final int REQUESTS = 25;
    private static final String CHAT = "/api/agent/chat";
    private static final String CHAT_BODY = "{\"message\":\"推荐几部番\"}";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentBudgetGuard budget;

    @Autowired
    private LlmProperties props;

    @MockBean
    private AgentOrchestrator orchestrator;

    @Test
    @DisplayName("池子满了 -> 后来者 503(不是 429, 也不是干等), 且被拒的请求不消耗每日额度")
    void saturatedPoolRejectsWith503WithoutChargingTheBudget() throws Exception {
        // worker 一进桩就停在这儿, 直到断言跑完才放行 —— 这样「还在跑」是确定的,
        // 而不是「大概还没跑完」. 给个超时是兜底: 万一用例提前炸了, 别把 worker 永久挂住.
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        when(orchestrator.run(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            releaseWorkers.await(60, TimeUnit.SECONDS);
            return AgentResult.of("慢回答", List.of(), 1, 1, 1);
        });

        try {
            List<ResponseEntity<Map>> responses = fireConcurrently(REQUESTS);

            List<ResponseEntity<Map>> rejected = responses.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE).toList();
            List<ResponseEntity<Map>> accepted = responses.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.GATEWAY_TIMEOUT).toList();

            assertThat(rejected)
                    .as("25 个请求同时打进来, 池子(4 + 队列 16)必然放不下")
                    .isNotEmpty();
            assertThat(accepted).isNotEmpty();
            assertThat(rejected.size() + accepted.size()).as("不该出现 500 之类的第三种答复").isEqualTo(REQUESTS);

            for (ResponseEntity<Map> r : rejected) {
                assertThat(r.getBody().get("code")).isEqualTo(503);
                assertThat((String) r.getBody().get("message")).contains("稍等");
            }

            // 被接受的那些还堵在桩里(一个都没跑完), 所以此刻「已用」= 净预留量.
            // 若被拒的请求没有退还预留, 这个数会多出 rejected × maxToolRounds.
            assertThat(budget.usedToday())
                    .as("只有真的提交进去的请求才该占额度")
                    .isEqualTo(accepted.size() * props.getMaxToolRounds());
        } finally {
            releaseWorkers.countDown();
        }
    }

    /** 25 个线程同时发, 让它们尽可能在同一瞬间到达服务端 */
    private List<ResponseEntity<Map>> fireConcurrently(int count) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<ResponseEntity<Map>> out = new ArrayList<>();

        try {
            List<java.util.concurrent.Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    return rest.postForEntity(CHAT,
                            new HttpEntity<>(CHAT_BODY, headers), Map.class);
                }));
            }
            start.countDown();
            for (var future : futures) {
                out.add(future.get(30, TimeUnit.SECONDS));
            }
        } catch (Exception e) {
            throw new IllegalStateException("并发发请求失败: " + e, e);
        } finally {
            // 线程本身不睡, 睡的是服务端的任务, 所以这里可以立刻收工
            pool.shutdownNow();
        }
        return out;
    }
}

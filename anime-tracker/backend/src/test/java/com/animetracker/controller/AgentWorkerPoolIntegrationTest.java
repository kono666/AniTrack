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
 * 桩睡 10 秒而整体超时只有 300 毫秒, 于是: 客户端的每个请求都会很快拿到答复(504),
 * 而 worker 上的任务还在睡 —— 池子在整个用例期间都是占满的, 后来者必然被拒.
 * 睡的时间要明显长于「25 个请求全部到达」所需的时间, 否则先跑完的任务会腾出位置,
 * 用例就变成碰运气了.
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
        when(orchestrator.run(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return AgentResult.of("慢回答", List.of(), 1, 1, 1);
        });

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

        // 被接受的那些还在 worker 上睡着(10 秒), 所以此刻「已用」= 净预留量.
        // 若被拒的请求没有退还预留, 这个数会多出 rejected × maxToolRounds.
        assertThat(budget.usedToday())
                .as("只有真的提交进去的请求才该占额度")
                .isEqualTo(accepted.size() * props.getMaxToolRounds());
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

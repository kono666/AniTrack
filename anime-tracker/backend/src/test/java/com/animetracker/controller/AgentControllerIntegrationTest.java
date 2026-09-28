package com.animetracker.controller;

import com.animetracker.agent.AgentOrchestrator;
import com.animetracker.agent.AgentResult;
import com.animetracker.agent.AgentStep;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Agent 两个对话入口在**真容器**上的行为: 正常返回、超时、以及登录用户的会话落库.
 *
 * <p>为什么用 RANDOM_PORT 而不是 MockMvc: 这里要验的东西一半是**容器行为** ——
 * DeferredResult 到点会不会真的结束请求、SseEmitter 到点会不会真的关连接、
 * 请求线程有没有被释放. MockMvc 不跑 Tomcat 的异步支持, 这些一个都验不到.
 *
 * <p>为什么把编排器打成桩: 真实编排器要么打真模型(要密钥、要钱、还慢), 要么走 Mock LLM
 * 去调真工具(会打到 api.bgm.tv, 让用例依赖网络). 这一层用例要验的是**控制器的契约** ——
 * 提交、超时、退款、SSE 事件顺序 —— 编排器自己的行为有 AgentOrchestratorTest 管.
 * 桩里会照真实编排器那样回调 listener, 否则 SSE 那几条事件就没人推, 用例也就验不到.
 *
 * <p>整体超时压到 300 毫秒: 默认推导出来是 10 分钟, 拿它验超时得等到天亮. 这个值只影响
 * 超时那两条用例, 其余用例的桩是立刻返回的, 压根走不到超时.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-agent-endpoints;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "llm.overall-timeout-ms=300",
        // 一个 IP 要发好几个请求, 别让限流半路插一脚
        "llm.rate-limit-per-minute=100",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class AgentControllerIntegrationTest {

    private static final String CHAT = "/api/agent/chat";
    private static final String STREAM = "/api/agent/chat/stream";
    private static final String CHAT_BODY = "{\"message\":\"推荐几部番\"}";

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private AgentOrchestrator orchestrator;

    // ========== 桩 ==========

    /** 编排器要睡多久才回答; 0 表示立刻回答 */
    private void stubAnswer(AgentResult result, long millis) {
        when(orchestrator.run(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (millis > 0) {
                Thread.sleep(millis);
            }
            return result;
        });
    }

    /** 照真实编排器那样先回调 listener 再返回 —— 否则流式那几条事件没人推 */
    private void stubAnswerEmittingToolEvents(AgentResult result) {
        when(orchestrator.run(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            AgentOrchestrator.Listener listener = inv.getArgument(4);
            listener.onToolCall(1, "get_ranking", Map.of());
            listener.onToolResult(1, "get_ranking", "{\"rows\":[]}", false, List.of());
            return result;
        });
    }

    private static AgentResult cannedResult() {
        AgentStep step = AgentStep.ok(1, "get_ranking", Map.of(), "{\"rows\":[]}", 12L);
        step.setCards(List.of());
        return AgentResult.of("给你的推荐在这", List.of(step), 1, 100, 20);
    }

    // ========== 请求 ==========

    private static HttpEntity<String> post(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<String> postSse(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        return new HttpEntity<>(body, headers);
    }

    private String loginAsTestUser() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/user/login",
                post("{\"username\":\"test\",\"password\":\"test123\"}", null), Map.class);
        assertThat(resp.getStatusCode()).as("测试账号由 DataInitializer 在 dev profile 下创建")
                .isEqualTo(HttpStatus.OK);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("token");
    }

    // ========== 正常路径 ==========

    @Test
    @DisplayName("非流式: 照常返回回答/轮数/工具轨迹, 访客不落库")
    void nonStreamingStillReturnsTheUsualPayload() {
        stubAnswer(cannedResult(), 0);

        ResponseEntity<Map> resp = rest.postForEntity(CHAT, post(CHAT_BODY, null), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(200);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(data.get("answer")).isEqualTo("给你的推荐在这");
        assertThat(data.get("rounds")).isEqualTo(1);
        List<?> steps = (List<?>) data.get("steps");
        assertThat(steps).hasSize(1);
        assertThat(((Map<?, ?>) steps.get(0)).get("tool")).isEqualTo("get_ranking");
        assertThat(data.get("conversationId")).as("访客的对话不落库").isNull();
    }

    /**
     * 登录用户的会话是在 worker 线程上写的, 不是请求线程.
     *
     * <p>这条盯的是一个很容易被忽略的回归: 异步线程上没有 EntityManager, 事务得自己开
     * (AgentOrchestrator 里那段注释讲的就是这个坑). 用「返回的 id 非空」判不够 ——
     * 那只证明方法被调到了; 这里回头再查一次会话详情, 证明它真的写进了库.
     */
    @Test
    @DisplayName("非流式: 登录用户的会话在 worker 线程上真的落了库")
    void loggedInConversationIsReallyPersistedFromTheWorkerThread() {
        stubAnswer(cannedResult(), 0);
        String token = loginAsTestUser();

        ResponseEntity<Map> resp = rest.postForEntity(CHAT, post(CHAT_BODY, token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number conversationId = (Number) ((Map<?, ?>) resp.getBody().get("data")).get("conversationId");
        assertThat(conversationId).as("登录用户的会话应该落库").isNotNull();

        ResponseEntity<Map> detail = rest.exchange("/api/agent/conversations/" + conversationId,
                org.springframework.http.HttpMethod.GET, post(null, token), Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> messages = (List<?>) ((Map<?, ?>) detail.getBody().get("data")).get("messages");
        assertThat(messages).as("一问一答两条消息").hasSize(2);
    }

    @Test
    @DisplayName("流式: tool_call -> tool_result -> done 按序推完, done 带完整轨迹")
    void streamPushesToolEventsThenDone() {
        stubAnswerEmittingToolEvents(cannedResult());

        ResponseEntity<String> resp = rest.postForEntity(STREAM, postSse(CHAT_BODY), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).isNotNull();
        int toolCall = body.indexOf("tool_call");
        int toolResult = body.indexOf("tool_result");
        int done = body.indexOf("done");
        assertThat(toolCall).as("事件顺序: tool_call 在最前").isNotNegative();
        assertThat(toolResult).isGreaterThan(toolCall);
        assertThat(done).isGreaterThan(toolResult);
        assertThat(body).contains("给你的推荐在这");
    }

    // ========== 超时 ==========

    /**
     * 非流式到点没跑完 -> 504 与统一 JSON, 而不是让前端一直等.
     *
     * <p>桩睡 3 秒、超时 300 毫秒: 若超时没生效, 这个请求会在 3 秒后拿到 200 —— 两条断言
     * 都会红, 所以它咬得住「超时到底有没有挂上」.
     */
    @Test
    @DisplayName("非流式: 超过整体超时 -> 504 与统一 JSON")
    void nonStreamingRequestTimesOutWith504() {
        stubAnswer(cannedResult(), 3_000);

        ResponseEntity<Map> resp = rest.postForEntity(CHAT, post(CHAT_BODY, null), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resp.getBody().get("code")).isEqualTo(504);
        assertThat((String) resp.getBody().get("message")).contains("超时");
    }

    /**
     * 流式到点没跑完 -> 以 504 结束, 且没有 done 事件.
     *
     * <p>状态码那一条是重点. 超时走的是容器转发, 而这条路上的每一步都容易出错: 给 SSE
     * 请求写 JSON 体会 406, 406 又会被转发到 /error, /error 落在 Security 的
     * anyRequest().authenticated() 上 —— 于是匿名访客拿到的答复是 401「请先登录」,
     * 一次超时被伪装成没登录(见 GlobalExceptionHandler#handleAsyncTimeout).
     * 只断言「没有 done」是抓不住这个的: 401 的响应里当然也没有 done.
     *
     * <p>反过来若这里能等到 done, 说明超时没生效(桩睡 3 秒, 而连接应该在 300 毫秒断开).
     */
    @Test
    @DisplayName("流式: 超过整体超时 -> 504 结束, 且没有 done 事件")
    void streamingConnectionIsClosedAtTheTimeout() {
        stubAnswer(cannedResult(), 3_000);

        long start = System.currentTimeMillis();
        ResponseEntity<String> resp = rest.postForEntity(STREAM, postSse(CHAT_BODY), String.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(resp.getStatusCode())
                .as("SSE 超时应当以 504 收场, 而不是被转成 401(客户端甚至会当成认证失败去重试)")
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resp.getBody() == null ? "" : resp.getBody()).doesNotContain("done");
        assertThat(elapsed)
                .as("应在 300 毫秒的时限上断开, 而不是等桩睡完的 3 秒")
                .isLessThan(1_500L);
    }
}

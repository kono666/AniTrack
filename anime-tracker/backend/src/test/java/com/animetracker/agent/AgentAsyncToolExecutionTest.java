package com.animetracker.agent;

import com.animetracker.agent.llm.LlmClient;
import com.animetracker.agent.llm.LlmMessage;
import com.animetracker.agent.llm.LlmResponse;
import com.animetracker.agent.llm.ToolCall;
import com.animetracker.agent.llm.ToolSpec;
import com.animetracker.agent.tool.ToolRegistry;
import com.animetracker.agent.tool.ToolTransactionRunner;
import com.animetracker.config.LlmProperties;
import com.animetracker.entity.Review;
import com.animetracker.entity.User;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.UserRepository;
import com.animetracker.service.AdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「异步线程 + JPA 懒加载」这条链路的验证.
 *
 * SSE 流式输出要求 Agent 循环跑在请求线程之外的线程上, 而 EntityManager 是绑定线程的 ——
 * 项目没关 open-in-view, 请求线程上一切正常, 所以这个问题在改成流式之前根本不会暴露.
 *
 * 这里把三件事钉死:
 *   1. 普通新线程上直接访问懒加载关联, 确实会抛 LazyInitializationException (问题是真的);
 *   2. 走 ToolTransactionRunner 之后就能正常读 (修复是有效的);
 *   3. 真实的编排器在真实的工作线程上跑一个会读关联的工具, 整条链路通 (不是只有单元级别能过).
 *
 * 第 1 条尤其重要: 它是这个类存在的理由. 没有它, 将来有人把 runner 去掉,
 * 剩下的测试依然全绿, 而线上是在用户点开评论助手时才炸.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-async;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("dev")
class AgentAsyncToolExecutionTest {

    private static final int SUBJECT_ID = 4242;

    @Autowired
    private ToolRegistry registry;
    @Autowired
    private ToolTransactionRunner runner;
    @Autowired
    private AdminService adminService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private LlmProperties props;
    @Autowired
    private ObjectMapper mapper;

    private User author;

    @BeforeEach
    void seedReview() {
        author = userRepository.findByUsername("async-probe").orElseGet(() ->
                userRepository.save(User.builder()
                        .username("async-probe").password("x").role("USER").build()));
        if (reviewRepository.findBySubjectIdOrderByCreatedAtDesc(SUBJECT_ID).isEmpty()) {
            reviewRepository.save(Review.builder()
                    .user(author).subjectId(SUBJECT_ID).rating(8).content("这部真的好看").build());
        }
    }

    @Test
    @DisplayName("前提: 普通工作线程上访问懒加载关联确实会失败")
    void lazyAccessFailsOnPlainWorkerThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                // 换一个线程就等于换了一个没有 EntityManager 的世界
                adminService.getAllReviews().get(0).get("username");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join(30_000);

        assertThat(failure.get())
                .as("如果这里没有抛异常, 说明懒加载在异步线程上是可用的, 那 ToolTransactionRunner 就没有存在意义了")
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    @DisplayName("修复: 经过 ToolTransactionRunner 后, 工作线程上也能读到关联对象")
    void lazyAccessWorksInsideRunner() throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                result.set(runner.run(null, managedUser -> adminService.getAllReviews()));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join(30_000);

        assertThat(failure.get()).isNull();
        assertThat(result.get()).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) result.get();
        // username 来自 Review.user 这个 LAZY 关联 —— 能读到就说明事务确实生效了
        assertThat(reviews).isNotEmpty();
        assertThat(reviews.get(0).get("username")).isEqualTo("async-probe");
    }

    @Test
    @DisplayName("整条链路: 编排器在工作线程上调用会读关联对象的工具")
    void orchestratorRunsLazyTouchingToolOnWorkerThread() throws Exception {
        // 用真实的注册表: 跑的是线上那 23 个工具, 不是测试替身
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new ScriptedLlm()
                        .thenToolCall("c1", "read_reviews", Map.of("subjectId", SUBJECT_ID))
                        .thenText("大家评价不错。"),
                registry, props, new PromptLibrary(), mapper, runner);

        AtomicReference<AgentResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 模拟 SSE 的工作线程: 这里没有 open-in-view 兜底
        Thread worker = new Thread(() -> {
            try {
                result.set(orchestrator.run(null, Persona.USER_ASSISTANT, List.of(),
                        "这部评价怎么样", AgentOrchestrator.SILENT));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join(60_000);

        assertThat(failure.get()).isNull();
        AgentResult agentResult = result.get();
        assertThat(agentResult.getSteps()).hasSize(1);

        AgentStep step = agentResult.getSteps().get(0);
        assertThat(step.isError())
                .as("工具报错说明事务没把 EntityManager 带进工作线程: %s", step.getResult())
                .isFalse();
        // 评论正文和作者名都要真的取到, 才算验证了懒加载
        assertThat(step.getResult()).contains("这部真的好看").contains("async-probe");
        assertThat(agentResult.getAnswer()).isEqualTo("大家评价不错。");
    }

    /** 按脚本返回响应的假模型 */
    private static class ScriptedLlm implements LlmClient {
        private final Deque<LlmResponse> script = new ArrayDeque<>();

        ScriptedLlm thenText(String text) {
            script.addLast(LlmResponse.text(text, "end_turn"));
            return this;
        }

        ScriptedLlm thenToolCall(String id, String name, Map<String, Object> args) {
            LlmResponse r = new LlmResponse();
            r.setToolCalls(List.of(new ToolCall(id, name, args)));
            r.setStopReason("tool_use");
            script.addLast(r);
            return this;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
            return script.isEmpty()
                    ? LlmResponse.text("(脚本已用尽)", "end_turn")
                    : script.pollFirst();
        }

        @Override
        public String providerName() {
            return "Scripted";
        }

        @Override
        public String modelName() {
            return "scripted-1";
        }
    }
}

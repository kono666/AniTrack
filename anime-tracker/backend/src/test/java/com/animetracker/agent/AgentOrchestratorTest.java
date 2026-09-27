package com.animetracker.agent;

import com.animetracker.agent.llm.LlmClient;
import com.animetracker.agent.llm.LlmMessage;
import com.animetracker.agent.llm.LlmResponse;
import com.animetracker.agent.llm.ToolCall;
import com.animetracker.agent.llm.ToolSpec;
import com.animetracker.agent.tool.ToolDefinition;
import com.animetracker.agent.tool.ToolDefinition.Access;
import com.animetracker.agent.tool.ToolProvider;
import com.animetracker.agent.tool.ToolRegistry;
import com.animetracker.agent.tool.ToolTransactionRunner;
import com.animetracker.config.LlmProperties;
import com.animetracker.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 主循环测试.
 *
 * 用脚本化的假模型替代真实 LLM, 这样可以精确构造出「模型要求调工具」「模型编了个不存在的工具名」
 * 「模型一直在两个工具之间打转」这些真实调用里很难复现、却必须处理的分支.
 *
 * 真实模型接进来之前, 这个循环的正确性只能靠这里保证 —— 一旦它写错,
 * 表现是「AI 有时候答非所问」, 极难定位.
 */
class AgentOrchestratorTest {

    private LlmProperties props;
    private ToolTransactionRunner txRunner;

    @BeforeEach
    void setUp() throws Exception {
        props = new LlmProperties();
        props.setMaxToolRounds(6);
        props.setHistoryLimit(20);
        props.setMaxToolResultChars(6000);

        // 事务是为了给异步线程补上 EntityManager, 与主循环逻辑无关.
        // 这里直接透传, 让被测代码跑在普通单测里.
        txRunner = mock(ToolTransactionRunner.class);
        when(txRunner.run(any(), any())).thenAnswer(invocation -> {
            ToolTransactionRunner.ToolWork work = invocation.getArgument(1);
            return work.run(invocation.getArgument(0));
        });
    }

    @Test
    @DisplayName("正常一轮: 模型先要工具, 拿到结果后再给出最终回答")
    void runsToolThenAnswers() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition tool = def("get_ranking", Access.PUBLIC, (call, user) -> {
            calls.incrementAndGet();
            return Map.of("list", List.of("进击的巨人", "钢炼"));
        });

        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("call_1", "get_ranking", Map.of())
                .thenText("推荐《进击的巨人》和《钢之炼金术师》。");

        AgentResult result = run(llm, List.of(provider(tool)), null, "推荐几部高分番");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.getAnswer()).isEqualTo("推荐《进击的巨人》和《钢之炼金术师》。");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getSteps().get(0).getToolName()).isEqualTo("get_ranking");
        assertThat(result.getSteps().get(0).isError()).isFalse();
        assertThat(result.isTruncated()).isFalse();

        // 关键: 第二轮的上下文里必须带上工具结果, 否则模型是在瞎猜
        List<LlmMessage> secondRound = llm.seen.get(1);
        LlmMessage toolMsg = secondRound.get(secondRound.size() - 1);
        assertThat(toolMsg.getRole()).isEqualTo(LlmMessage.Role.TOOL);
        assertThat(toolMsg.getToolResults()).hasSize(1);
        assertThat(toolMsg.getToolResults().get(0).getContent()).contains("进击的巨人");
        assertThat(toolMsg.getToolResults().get(0).isError()).isFalse();
    }

    @Test
    @DisplayName("模型编出不存在的工具名: 记为失败并把错误回灌, 让它自己改正, 不中断整个请求")
    void feedsUnknownToolErrorBackToModel() {
        ToolDefinition tool = def("get_ranking", Access.PUBLIC, (call, user) -> Map.of());
        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("call_1", "get_top_anime", Map.of())   // 这个名字不存在
                .thenText("抱歉, 我换个方式回答。");

        AgentResult result = run(llm, List.of(provider(tool)), null, "推荐几部");

        assertThat(result.getSteps()).hasSize(1);
        assertThat(result.getSteps().get(0).isError()).isTrue();
        assertThat(result.getSteps().get(0).getResult()).contains("不存在名为 get_top_anime 的工具");

        // 错误信息要真的回灌给模型, 而不是只记在日志里
        List<LlmMessage> secondRound = llm.seen.get(1);
        assertThat(secondRound.get(secondRound.size() - 1).getToolResults().get(0).isError()).isTrue();
        assertThat(result.getAnswer()).isEqualTo("抱歉, 我换个方式回答。");
    }

    @Test
    @DisplayName("工具自己抛异常时收拢成一句可读错误, 同样不中断请求")
    void convertsToolExceptionIntoErrorMessage() {
        ToolDefinition tool = def("get_anime_detail", Access.PUBLIC, (call, user) -> {
            throw new IllegalArgumentException("没有找到 id 为 999 的番剧");
        });
        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("call_1", "get_anime_detail", Map.of("subjectId", 999))
                .thenText("这部番我没查到。");

        AgentResult result = run(llm, List.of(provider(tool)), null, "看下 999 的详情");

        assertThat(result.getSteps().get(0).isError()).isTrue();
        assertThat(result.getSteps().get(0).getResult()).contains("执行失败").contains("没有找到 id 为 999");
    }

    @Test
    @DisplayName("模型反复调用不收敛时按轮数上限中断, 并明确说明没能得出结论")
    void stopsAtMaxRoundsAndSaysSo() {
        props.setMaxToolRounds(2);
        ToolDefinition tool = def("get_ranking", Access.PUBLIC, (call, user) -> Map.of("ok", true));

        // 每一轮都要求调工具, 永远不给最终回答
        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("c1", "get_ranking", Map.of())
                .thenToolCall("c2", "get_ranking", Map.of())
                .thenToolCall("c3", "get_ranking", Map.of());

        AgentResult result = run(llm, List.of(provider(tool)), null, "推荐几部");

        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(result.getSteps()).hasSize(2);
        assertThat(llm.seen).hasSize(2);   // 触顶后不再多调一次模型
        assertThat(result.getAnswer()).doesNotContain("推荐《");  // 不能假装给出了结论
    }

    @Test
    @DisplayName("超长工具结果会被截断, 避免一次把上下文撑爆")
    void truncatesOversizedToolResult() {
        props.setMaxToolResultChars(200);
        String huge = "x".repeat(5000);
        ToolDefinition tool = def("get_calendar", Access.PUBLIC, (call, user) -> huge);

        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("c1", "get_calendar", Map.of())
                .thenText("看完了。");

        AgentResult result = run(llm, List.of(provider(tool)), null, "今天更新什么");

        String fed = result.getSteps().get(0).getResult();
        assertThat(fed).hasSizeLessThan(400);
        assertThat(fed).contains("结果过长已截断").contains("原长 5000 字符");
    }

    @Test
    @DisplayName("历史只带最近 N 条, 对话越长越贵这件事必须有硬上限")
    void trimsHistoryToLimit() {
        props.setHistoryLimit(3);
        ToolDefinition tool = def("get_ranking", Access.PUBLIC, (call, user) -> Map.of());

        List<LlmMessage> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(LlmMessage.user("旧问题 " + i));
            history.add(LlmMessage.assistant("旧回答 " + i));
        }

        ScriptedLlm llm = new ScriptedLlm().thenText("好");
        run(llm, List.of(provider(tool)), history, null, "新问题");

        List<LlmMessage> sent = llm.seen.get(0);
        // 1 条 system + 3 条历史 + 1 条本次提问
        assertThat(sent).hasSize(5);
        assertThat(sent.get(0).getRole()).isEqualTo(LlmMessage.Role.SYSTEM);
        // 保留的是最近的, 不是最早的
        assertThat(sent.get(1).getText()).isEqualTo("旧回答 9");
        assertThat(sent.get(4).getText()).isEqualTo("新问题");
    }

    @Test
    @DisplayName("暴露给模型的工具按身份过滤: 未登录取不到需要登录的工具")
    void onlyExposesToolsAllowedForCurrentUser() {
        ToolDefinition pub = def("get_ranking", Access.PUBLIC, (call, user) -> Map.of());
        ToolDefinition needsLogin = def("list_my_tracking", Access.USER, (call, user) -> Map.of());
        ToolDefinition adminOnly = def("platform_dashboard", Access.ADMIN, (call, user) -> Map.of());
        List<ToolProvider> providers = List.of(provider(pub, needsLogin, adminOnly));

        ScriptedLlm anon = new ScriptedLlm().thenText("好");
        run(anon, providers, null, "hi");
        assertThat(names(anon.seenTools.get(0))).containsExactly("get_ranking");

        ScriptedLlm normal = new ScriptedLlm().thenText("好");
        run(normal, providers, user("USER"), "hi");
        assertThat(names(normal.seenTools.get(0)))
                .containsExactlyInAnyOrder("get_ranking", "list_my_tracking");

        ScriptedLlm admin = new ScriptedLlm().thenText("好");
        run(admin, providers, user("ADMIN"), "hi");
        assertThat(names(admin.seenTools.get(0))).hasSize(3);
    }

    @Test
    @DisplayName("即使模型硬造出越权调用, 服务端也会拒绝执行")
    void refusesPrivilegedToolCallFromWrongIdentity() {
        AtomicInteger executed = new AtomicInteger();
        ToolDefinition adminOnly = def("platform_dashboard", Access.ADMIN, (call, user) -> {
            executed.incrementAndGet();
            return Map.of("totalUsers", 1);
        });

        // 未登录身份, 但模型直接点名要调管理端工具
        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("c1", "platform_dashboard", Map.of())
                .thenText("我没有权限。");

        AgentResult result = run(llm, List.of(provider(adminOnly)), null, "给我看后台数据");

        assertThat(executed.get()).isZero();
        assertThat(result.getSteps().get(0).isError()).isTrue();
        assertThat(result.getSteps().get(0).getResult()).contains("无权使用");
    }

    @Test
    @DisplayName("模型既不给文字也不调工具时, 兜底成一句可读的话而不是空字符串")
    void fallsBackToApologyOnEmptyAnswer() {
        ScriptedLlm llm = new ScriptedLlm().thenText("   ");
        ToolDefinition tool = def("get_ranking", Access.PUBLIC, (call, user) -> Map.of());

        AgentResult result = run(llm, List.of(provider(tool)), null, "在吗");

        assertThat(result.getAnswer()).isNotBlank().contains("换个说法");
    }

    @Test
    @DisplayName("token 用量跨轮累加, 便于监控成本")
    void accumulatesTokenUsage() {
        ToolDefinition tool = def("get_ranking", Access.PUBLIC, (call, user) -> Map.of());
        ScriptedLlm llm = new ScriptedLlm()
                .thenToolCall("c1", "get_ranking", Map.of(), 100, 20)
                .thenText("好了", 50, 10);

        AgentResult result = run(llm, List.of(provider(tool)), null, "推荐几部");

        assertThat(result.getInputTokens()).isEqualTo(150);
        assertThat(result.getOutputTokens()).isEqualTo(30);
    }

    // ── 测试脚手架 ──────────────────────────────────────────

    private AgentResult run(ScriptedLlm llm, List<ToolProvider> providers,
                            User user, String input) {
        return run(llm, providers, List.of(), user, input);
    }

    private AgentResult run(ScriptedLlm llm, List<ToolProvider> providers,
                            List<LlmMessage> history, User user, String input) {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llm, new ToolRegistry(providers), props, new PromptLibrary(),
                new ObjectMapper(), txRunner);
        return orchestrator.run(user, Persona.USER_ASSISTANT, history, input, AgentOrchestrator.SILENT);
    }

    private static ToolProvider provider(ToolDefinition... defs) {
        return () -> List.of(defs);
    }

    private static ToolDefinition def(String name, Access access, com.animetracker.agent.tool.AgentToolExecutor exec) {
        return ToolDefinition.builder()
                .name(name)
                .description("测试工具 " + name)
                .access(access)
                .executor(exec)
                .build();
    }

    private static User user(String role) {
        return User.builder().id(1L).username("tester").role(role).build();
    }

    private static List<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::getName).toList();
    }

    /** 按脚本依次返回响应的假模型, 同时记录每次实际发出去的上下文 */
    private static class ScriptedLlm implements LlmClient {
        final Deque<LlmResponse> script = new ArrayDeque<>();
        final List<List<LlmMessage>> seen = new ArrayList<>();
        final List<List<ToolSpec>> seenTools = new ArrayList<>();

        ScriptedLlm thenText(String text) {
            return thenText(text, 0, 0);
        }

        ScriptedLlm thenText(String text, int in, int out) {
            LlmResponse r = LlmResponse.text(text, "end_turn");
            r.setInputTokens(in);
            r.setOutputTokens(out);
            script.addLast(r);
            return this;
        }

        ScriptedLlm thenToolCall(String id, String name, Map<String, Object> args) {
            return thenToolCall(id, name, args, 0, 0);
        }

        ScriptedLlm thenToolCall(String id, String name, Map<String, Object> args, int in, int out) {
            LlmResponse r = new LlmResponse();
            r.setToolCalls(List.of(new ToolCall(id, name, args)));
            r.setStopReason("tool_use");
            r.setInputTokens(in);
            r.setOutputTokens(out);
            script.addLast(r);
            return this;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
            seen.add(new ArrayList<>(messages));
            seenTools.add(new ArrayList<>(tools));
            if (script.isEmpty()) {
                return LlmResponse.text("(脚本已用尽)", "end_turn");
            }
            return script.pollFirst();
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

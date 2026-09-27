package com.animetracker.agent;

import com.animetracker.agent.llm.LlmClient;
import com.animetracker.agent.llm.LlmMessage;
import com.animetracker.agent.llm.LlmResponse;
import com.animetracker.agent.llm.ToolCall;
import com.animetracker.agent.llm.ToolResult;
import com.animetracker.agent.llm.ToolSpec;
import com.animetracker.agent.tool.ToolCards;
import com.animetracker.agent.tool.ToolDefinition;
import com.animetracker.agent.tool.ToolRegistry;
import com.animetracker.agent.tool.ToolTransactionRunner;
import com.animetracker.config.LlmProperties;
import com.animetracker.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 主循环 —— 整个 AI 能力的核心.
 *
 * 流程:
 * <pre>
 *   messages = [系统提示词] + 历史 + [本次提问]
 *   循环 (最多 maxToolRounds 轮):
 *       让模型决定下一步
 *       如果它不再要求调用工具 -&gt; 这就是最终回答, 结束
 *       否则逐个执行它要求的工具, 把结果追加进上下文, 进入下一轮
 * </pre>
 *
 * 两个关键设计:
 *
 * 1. <b>工具失败不中断请求</b> —— 把错误信息原样回灌给模型. 模型看到「缺少必填参数 subjectId」
 *    后通常会自己改正参数重试, 这比直接给用户报错有用得多.
 *
 * 2. <b>轮数上限兜底</b> —— 防止模型在两个工具之间反复横跳把额度烧光. 触顶时明确告知用户
 *    问题需要拆细, 而不是假装给出了结论.
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final LlmClient llmClient;
    private final ToolRegistry registry;
    private final LlmProperties props;
    private final PromptLibrary prompts;
    private final ObjectMapper mapper;
    private final ToolTransactionRunner txRunner;

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry registry, LlmProperties props,
                             PromptLibrary prompts, ObjectMapper mapper,
                             ToolTransactionRunner txRunner) {
        this.llmClient = llmClient;
        this.registry = registry;
        this.props = props;
        this.prompts = prompts;
        this.mapper = mapper;
        this.txRunner = txRunner;
    }

    /** 执行过程回调, 用于 SSE 实时推送与前端展示 */
    public interface Listener {
        default void onToolCall(int round, String toolName, Map<String, Object> args) {
        }

        /**
         * @param cards 结果里可画成卡片的番剧条目, 见 {@link ToolCards#extract}
         */
        default void onToolResult(int round, String toolName, String result, boolean error,
                                  List<Map<String, Object>> cards) {
        }
    }

    /** 不做任何推送的空实现 */
    public static final Listener SILENT = new Listener() {
    };

    /**
     * 跑一轮完整对话.
     *
     * @param user    当前登录用户, 未登录为 null
     * @param persona 人格 (决定系统提示词与可见工具)
     * @param history 历史消息 (已按时间正序)
     * @param input   本次用户提问
     * @param listener 工具调用过程回调
     */
    public AgentResult run(User user, Persona persona, List<LlmMessage> history,
                           String input, Listener listener) {
        List<ToolSpec> tools = registry.specsFor(user);

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(prompts.systemPrompt(persona)));
        messages.addAll(trimHistory(history));
        messages.add(LlmMessage.user(input));

        log.info("Agent 开始: persona={}, 可用工具 {} 个, 历史 {} 条",
                persona.displayName(), tools.size(), messages.size() - 2);

        List<AgentStep> steps = new ArrayList<>();
        int totalIn = 0;
        int totalOut = 0;

        for (int round = 1; round <= props.getMaxToolRounds(); round++) {
            LlmResponse resp = llmClient.chat(messages, tools);
            totalIn += resp.getInputTokens();
            totalOut += resp.getOutputTokens();

            // 模型不再要求调用工具, 说明它准备给出最终回答
            if (!resp.hasToolCalls()) {
                String answer = (resp.getText() == null || resp.getText().isBlank())
                        ? "抱歉, 我没能组织出有效回复, 请换个说法再问一次。"
                        : resp.getText();
                log.info("Agent 结束: 轮数={}, 工具调用={} 次, token(入/出)={}/{}",
                        round, steps.size(), totalIn, totalOut);
                return AgentResult.of(answer, steps, round, totalIn, totalOut);
            }

            messages.add(LlmMessage.assistantToolCalls(resp.getText(), resp.getToolCalls()));

            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : resp.getToolCalls()) {
                AgentStep step = execute(call, user, round, listener);
                steps.add(step);
                results.add(step.isError()
                        ? ToolResult.error(call.getId(), step.getToolName(), step.getResult())
                        : ToolResult.ok(call.getId(), step.getToolName(), step.getResult()));
            }
            messages.add(LlmMessage.toolResults(results));
        }

        log.warn("Agent 达到最大工具轮数 {} 仍未收敛, 中断", props.getMaxToolRounds());
        AgentResult result = AgentResult.of(
                "这个问题需要查的资料比较多, 我连续调用了 " + props.getMaxToolRounds()
                        + " 轮工具还是没能得出确定结论。可以试着把问题问得更具体一些, "
                        + "比如直接说出番剧名称, 或者指明想看的类型和时间范围。",
                steps, props.getMaxToolRounds(), totalIn, totalOut);
        result.setTruncated(true);
        return result;
    }

    /** 执行单个工具, 把所有异常收拢成一条可回灌给模型的错误信息 */
    private AgentStep execute(ToolCall call, User user, int round, Listener listener) {
        long start = System.currentTimeMillis();
        String name = call.getName();
        listener.onToolCall(round, name, call.getArguments());
        log.info("  [第{}轮] 调用 {} 参数={}", round, name, call.getArguments());

        try {
            ToolDefinition def = registry.find(name);
            if (def == null) {
                // 模型偶尔会凭印象编一个工具名, 明确告诉它不存在, 它下一轮就会改用真实工具
                throw new IllegalArgumentException("不存在名为 " + name + " 的工具, 请只使用已提供的工具");
            }
            // 双保险: 即便模型设法构造出越权的调用, 身份不符也一律不执行
            if (!ToolRegistry.allowed(def, user)) {
                throw new IllegalStateException("当前身份无权使用工具 " + name);
            }

            // 每次工具调用单独开事务: 异步线程上原本没有 EntityManager, 不开事务懒加载会直接抛异常
            Object raw = txRunner.run(user, managedUser -> def.getExecutor().execute(call, managedUser));
            String json = truncate(toJson(raw));
            // 从原始对象里挑卡片, 而不是回头去解析 json 字符串:
            // json 可能已被截断, 而且那是给模型看的形状, 不该让前端依赖它
            List<Map<String, Object>> cards = ToolCards.extract(raw);
            long cost = System.currentTimeMillis() - start;
            log.info("  [第{}轮] {} 成功, {} 字符, {} ms", round, name, json.length(), cost);
            listener.onToolResult(round, name, json, false, cards);
            AgentStep step = AgentStep.ok(round, name, call.getArguments(), json, cost);
            step.setCards(cards);
            return step;

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            String message = "执行失败: " + rootMessage(e);
            log.warn("  [第{}轮] {} 失败: {}", round, name, message);
            listener.onToolResult(round, name, message, true, List.of());
            return AgentStep.fail(round, name, call.getArguments(), message, cost);
        }
    }

    /** 只带最近 N 条历史进上下文, 防止对话越长越贵 */
    private List<LlmMessage> trimHistory(List<LlmMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int limit = props.getHistoryLimit();
        return history.size() <= limit
                ? history
                : new ArrayList<>(history.subList(history.size() - limit, history.size()));
    }

    private String toJson(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(raw);
        } catch (Exception e) {
            return "\"" + String.valueOf(raw).replace("\"", "'") + "\"";
        }
    }

    /**
     * 安全网: 兜底截断超长结果.
     *
     * 各工具自身已经限制了返回条数, 正常不会触发这里;
     * 保留它是为了防止将来新增工具时忘记限流, 一次性把几万字符灌回模型.
     */
    private String truncate(String json) {
        int max = props.getMaxToolResultChars();
        if (json.length() <= max) {
            return json;
        }
        return json.substring(0, max) + "\n...(结果过长已截断, 原长 " + json.length() + " 字符)";
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}

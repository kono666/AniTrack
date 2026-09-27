package com.animetracker.controller;

import com.animetracker.agent.AgentOrchestrator;
import com.animetracker.agent.AgentBudgetGuard;
import com.animetracker.agent.AgentRateLimiter;
import com.animetracker.agent.AgentResult;
import com.animetracker.agent.AgentStep;
import com.animetracker.agent.HistorySanitizer;
import com.animetracker.agent.Persona;
import com.animetracker.agent.llm.LlmClient;
import com.animetracker.agent.llm.LlmException;
import com.animetracker.agent.llm.LlmMessage;
import com.animetracker.agent.tool.ToolDefinition;
import com.animetracker.agent.tool.ToolRegistry;
import com.animetracker.config.CurrentUser;
import com.animetracker.config.LlmProperties;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.AgentChatRequest;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.service.AgentConversationService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 助手接口.
 *
 * 两个入口:
 * <ul>
 *   <li>{@code /api/agent/chat} —— 一次性返回完整结果, 便于调试和 curl 验证;</li>
 *   <li>{@code /api/agent/chat/stream} —— SSE 流式, 把「调用了哪个工具、拿到了什么」
 *       实时推给前端. Agent 的价值有一半在过程里, 只在最后吐一段文字会让人以为
 *       这只是一个套壳的聊天框.</li>
 * </ul>
 *
 * 未登录访客可以直接对话, 但注册表只会把 PUBLIC 工具暴露给模型 —— 拿不到写操作,
 * 也就没有越权的可能. 需要登录的工具对访客而言根本不存在, 模型无从调用.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** SSE 连接最长存活时间, 要比模型最慢的一次响应宽松 */
    private static final long EMITTER_TIMEOUT_MS = 180_000L;
    /** 推给前端的工具结果预览长度 */
    private static final int PREVIEW_CHARS = 300;

    private final AgentOrchestrator orchestrator;
    private final AgentConversationService conversationService;
    private final AgentRateLimiter rateLimiter;
    private final AgentBudgetGuard budget;
    private final ToolRegistry registry;
    private final LlmClient llmClient;
    private final LlmProperties props;

    /**
     * 跑 Agent 循环的线程池.
     *
     * 用固定大小而不是缓存池: 每个任务都在等大模型返回, 池子无限大只会把上游配额打爆,
     * 不如让超出的请求排队. 队列不设界是因为入口有全局限流兜着, 堆不起来.
     */
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "agent-worker");
        t.setDaemon(true);
        return t;
    });

    public AgentController(AgentOrchestrator orchestrator,
                           AgentConversationService conversationService,
                           AgentRateLimiter rateLimiter,
                           AgentBudgetGuard budget,
                           ToolRegistry registry,
                           LlmClient llmClient,
                           LlmProperties props) {
        this.orchestrator = orchestrator;
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.registry = registry;
        this.llmClient = llmClient;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }

    // ========== 对话 ==========

    /** 当前 Agent 的配置概览: 用的哪个模型、当前身份能用哪些工具 */
    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info(@CurrentUser User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", llmClient.providerName());
        out.put("model", llmClient.modelName());
        out.put("loggedIn", user != null);

        List<Map<String, Object>> personas = new ArrayList<>();
        for (Persona p : Persona.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.promptFile());
            item.put("name", p.displayName());
            item.put("available", p != Persona.ADMIN_ANALYST || isAdmin(user));
            personas.add(item);
        }
        out.put("personas", personas);
        out.put("tools", registry.availableFor(user).stream().map(ToolDefinition::name).toList());
        // 剩余额度给前端展示: 公网 Demo 上额度用完是很正常的状态, 与其让用户
        // 撞上一个 429 才知道, 不如提前把「今天还能问几次」摆在界面上
        out.put("dailyLimit", budget.dailyLimit());
        out.put("dailyRemaining", budget.remaining());
        return ApiResponse.success(out);
    }

    /** 非流式对话: 一次返回完整回答与工具调用轨迹 */
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@CurrentUser User user,
                                                 @Valid @RequestBody AgentChatRequest req,
                                                 HttpServletRequest http) {
        Persona persona = resolvePersona(user, req.getPersona());
        checkRateLimit(user, http);
        budget.acquire();
        String input = normalizeInput(req.getMessage());

        AgentResult result = orchestrator.run(user, persona, loadHistory(user, req), input,
                AgentOrchestrator.SILENT);
        budget.settle(result.getRounds());

        Map<String, Object> out = answerPayload(result);
        out.put("conversationId", persist(user, req, persona, input, result));
        out.put("dailyRemaining", budget.remaining());
        return ApiResponse.success(out);
    }

    /** 流式对话: 工具调用与最终回答通过 SSE 实时推送 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@CurrentUser User user,
                             @Valid @RequestBody AgentChatRequest req,
                             HttpServletRequest http) {
        // 身份校验与限流放在建流之前: 被拒时走统一异常处理返回正常 JSON,
        // 而不是先开一条 SSE 再往里推一条 error —— 前端处理起来简单得多
        Persona persona = resolvePersona(user, req.getPersona());
        checkRateLimit(user, http);
        budget.acquire();
        String input = normalizeInput(req.getMessage());
        List<LlmMessage> history = loadHistory(user, req);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        workers.submit(() -> {
            try {
                AgentResult result = orchestrator.run(user, persona, history, input,
                        new AgentOrchestrator.Listener() {
                            @Override
                            public void onToolCall(int round, String toolName, Map<String, Object> args) {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("round", round);
                                data.put("tool", toolName);
                                data.put("args", args == null ? Map.of() : args);
                                send(emitter, "tool_call", data);
                            }

                            @Override
                            public void onToolResult(int round, String toolName, String result,
                                                     boolean error, List<Map<String, Object>> cards) {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("round", round);
                                data.put("tool", toolName);
                                data.put("error", error);
                                data.put("preview", preview(result));
                                // 卡片随结果一起推: 前端不用等整轮结束就能把番剧画出来,
                                // 演示时「工具一跑完, 卡片就浮出来」的观感就来自这里
                                if (!cards.isEmpty()) {
                                    data.put("cards", cards);
                                }
                                send(emitter, "tool_result", data);
                            }
                        });

                Map<String, Object> done = answerPayload(result);
                done.put("conversationId", persist(user, req, persona, input, result));
                budget.settle(result.getRounds());
                done.put("dailyRemaining", budget.remaining());
                send(emitter, "done", done);
                emitter.complete();

            } catch (Exception e) {
                log.warn("Agent 流式执行失败: {}", e.toString());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("message", friendly(e));
                send(emitter, "error", data);
                emitter.complete();
            }
        });

        return emitter;
    }

    // ========== 会话管理 ==========

    @GetMapping("/conversations")
    public ApiResponse<List<Map<String, Object>>> conversations(@CurrentUser User user) {
        requireLogin(user);
        return ApiResponse.success(conversationService.list(user));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<Map<String, Object>> conversation(@CurrentUser User user,
                                                         @PathVariable Long id) {
        requireLogin(user);
        return ApiResponse.success(conversationService.detail(user, id));
    }

    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@CurrentUser User user, @PathVariable Long id) {
        requireLogin(user);
        conversationService.delete(user, id);
        return ApiResponse.success("会话已删除", null);
    }

    // ========== 内部辅助 ==========

    private Map<String, Object> answerPayload(AgentResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", result.getAnswer());
        out.put("rounds", result.getRounds());
        out.put("truncated", result.isTruncated());
        out.put("inputTokens", result.getInputTokens());
        out.put("outputTokens", result.getOutputTokens());
        out.put("steps", stepsOf(result));
        return out;
    }

    private List<Map<String, Object>> stepsOf(AgentResult result) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AgentStep s : result.getSteps()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("round", s.getRound());
            item.put("tool", s.getToolName());
            item.put("args", s.getArguments());
            item.put("error", s.isError());
            item.put("millis", s.getMillis());
            // 空列表也照发: 前端按「这个字段存在但为空」和「压根没这个字段」区分的成本
            // 比省下的这几个字节高得多
            item.put("cards", s.getCards());
            steps.add(item);
        }
        return steps;
    }

    /**
     * 解析人格.
     *
     * 前端传什么不算数 —— 运营分析人格会拿到管理端工具, 这里必须按服务端登录态复核一次.
     */
    private Persona resolvePersona(User user, String raw) {
        Persona persona = Persona.from(raw);
        if (persona == Persona.ADMIN_ANALYST && !isAdmin(user)) {
            throw BusinessException.forbidden("运营分析仅对管理员开放");
        }
        return persona;
    }

    /**
     * 装载历史上下文.
     *
     * 登录用户且指定了会话 id 时, 以服务端记录为准, 完全忽略浏览器带来的历史;
     * 只有访客(或没指定会话)才回退到清洗过的前端历史 —— 前端数据永远只是「线索」,
     * 不能作为上下文的事实来源.
     */
    private List<LlmMessage> loadHistory(User user, AgentChatRequest req) {
        if (user != null && req.getConversationId() != null) {
            return conversationService.history(user, req.getConversationId());
        }
        return HistorySanitizer.sanitize(req.getHistory(), props.getHistoryLimit());
    }

    private Long persist(User user, AgentChatRequest req, Persona persona,
                         String input, AgentResult result) {
        try {
            return conversationService.append(user, req.getConversationId(), persona,
                    input, result.getAnswer());
        } catch (Exception e) {
            // 存不下来是持久化的问题, 不该让用户连这次回答一起丢掉
            log.warn("会话持久化失败(本次回答照常返回): {}", e.getMessage());
            return req.getConversationId();
        }
    }

    private void checkRateLimit(User user, HttpServletRequest http) {
        rateLimiter.check(rateLimitKey(user, http), props.getRateLimitPerMinute());
    }

    /**
     * 限流维度: 登录用户按用户 id, 访客按来源 IP.
     *
     * 注意 getRemoteAddr 在反向代理后面拿到的是代理 IP, 那样所有访客会共用一份配额.
     * 部署到网关后面时需要改成读代理链上的 X-Forwarded-For —— 但也不能无条件相信它,
     * 否则伪造一个请求头就能绕过限流. 这里的取法是「拿不到真实来源就先不信任代理头」.
     */
    private String rateLimitKey(User user, HttpServletRequest http) {
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }
        return "ip:" + http.getRemoteAddr();
    }

    private String normalizeInput(String raw) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty()) {
            throw BusinessException.badRequest("提问内容不能为空");
        }
        int max = props.getMaxInputLength();
        if (input.length() > max) {
            throw BusinessException.badRequest("提问太长了, 请控制在 " + max + " 字以内");
        }
        return input;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 用户刷新或关掉页面会走到这里, 属于正常情况, 不记为错误
            log.debug("SSE 推送失败, 客户端可能已断开: {}", e.getMessage());
        }
    }

    private String preview(String result) {
        if (result == null) {
            return "";
        }
        return result.length() <= PREVIEW_CHARS ? result : result.substring(0, PREVIEW_CHARS) + "…";
    }

    /** 只把明确面向用户的错误原样透出, 其余一律给通用提示 —— 免得把堆栈或上游信息漏给前端 */
    private String friendly(Exception e) {
        if (e instanceof LlmException || e instanceof BusinessException) {
            return e.getMessage();
        }
        return "AI 服务执行出错, 请稍后重试";
    }

    private boolean isAdmin(User user) {
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private void requireLogin(User user) {
        if (user == null) {
            throw BusinessException.unauthorized("请先登录");
        }
    }
}

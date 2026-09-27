package com.animetracker.agent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 占位客户端, 供「还没拿到 API Key」时开发前端使用.
 *
 * 它不调用任何真实模型, 所有输出都带【Mock 模式】前缀, 不会伪装成真回复.
 * 行为: 首轮挑一个「不需要必填参数」的工具调用一次, 让前端能调试工具调用渲染;
 *       拿到工具结果后返回一段固定文案.
 *
 * 用途仅限本地联调与自动化测试. 生产环境把 llm.provider 改掉即可.
 */
public class MockLlmClient implements LlmClient {

    public static final String PREFIX = "【Mock 模式】";

    private int round = 0;

    @Override
    public String providerName() {
        return "Mock";
    }

    @Override
    public String modelName() {
        return "mock-model";
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        round++;

        boolean alreadyCalledTool = messages.stream()
                .anyMatch(m -> m.getRole() == LlmMessage.Role.TOOL);

        if (!alreadyCalledTool && tools != null && !tools.isEmpty()) {
            ToolSpec zeroArg = firstZeroArgTool(tools);
            if (zeroArg != null) {
                ToolCall call = new ToolCall("mock-call-" + round, zeroArg.getName(), new LinkedHashMap<>());
                LlmResponse r = new LlmResponse();
                r.setToolCalls(new ArrayList<>(List.of(call)));
                r.setStopReason("tool_use");
                r.setInputTokens(0);
                r.setOutputTokens(0);
                return r;
            }
        }

        String answer = PREFIX + "当前未接入真实大模型, 这是一段占位回复。\n\n"
                + "要启用真实对话, 请配置环境变量 LLM_API_KEY 后重启后端:\n"
                + "- DeepSeek: 设置 LLM_PROVIDER=openai-compat, LLM_BASE_URL=https://api.deepseek.com, LLM_MODEL=deepseek-chat\n"
                + "- Claude:   设置 LLM_PROVIDER=anthropic, LLM_BASE_URL=https://api.anthropic.com, LLM_MODEL=claude-sonnet-5\n\n"
                + "在此之前, 工具调用链路、会话记录与前端界面都可正常工作, 方便先联调。";

        LlmResponse r = LlmResponse.text(answer, "end_turn");
        r.setOutputTokens(0);
        return r;
    }

    /** 找一个不需要必填参数的工具, 这样可以空参安全调用 */
    private ToolSpec firstZeroArgTool(List<ToolSpec> tools) {
        for (ToolSpec t : tools) {
            Map<String, Object> schema = t.getInputSchema();
            Object required = schema == null ? null : schema.get("required");
            boolean noRequired = required == null
                    || (required instanceof List<?> list && list.isEmpty());
            if (noRequired) {
                return t;
            }
        }
        return null;
    }
}

package com.animetracker.agent.llm;

import com.animetracker.config.LlmProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude 协议实现 (/v1/messages).
 *
 * 与 OpenAI 格式的三处主要差异:
 *   1. system 提示词是顶层字段, 不能放进 messages 数组
 *   2. messages 的 content 是「内容块数组」, 工具调用与结果都是块
 *   3. 工具定义用 input_schema, 不是 function.parameters
 */
public class AnthropicClient implements LlmClient {

    private static final String API_VERSION = "2023-06-01";

    private final LlmProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public AnthropicClient(LlmProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String providerName() {
        return "Anthropic";
    }

    @Override
    public String modelName() {
        return props.getModel();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", props.getTemperature());

        String system = joinSystem(messages);
        if (!system.isBlank()) {
            body.put("system", system);
        }
        body.put("messages", toWireMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> wireTools = new ArrayList<>();
            for (ToolSpec t : tools) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("name", t.getName());
                w.put("description", t.getDescription());
                w.put("input_schema", t.getInputSchema());
                wireTools.add(w);
            }
            body.put("tools", wireTools);
        }

        JsonNode resp = LlmHttp.post(restTemplate, mapper, endpoint(), headers(), body, providerName());
        return parse(resp);
    }

    /** 把所有 system 消息合并成顶层 system 字段 */
    private String joinSystem(List<LlmMessage> messages) {
        List<String> parts = new ArrayList<>();
        for (LlmMessage m : messages) {
            if (m.getRole() == LlmMessage.Role.SYSTEM && m.getText() != null && !m.getText().isBlank()) {
                parts.add(m.getText());
            }
        }
        return String.join("\n\n", parts);
    }

    private List<Map<String, Object>> toWireMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (LlmMessage m : messages) {
            switch (m.getRole()) {
                case SYSTEM -> {
                    // 已提到顶层, 这里跳过
                }
                case USER -> wire.add(textMessage("user", m.getText()));
                case ASSISTANT -> {
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    if (m.getText() != null && !m.getText().isBlank()) {
                        blocks.add(Map.of("type", "text", "text", m.getText()));
                    }
                    for (ToolCall c : m.getToolCalls()) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "tool_use");
                        block.put("id", c.getId());
                        block.put("name", c.getName());
                        block.put("input", c.getArguments());
                        blocks.add(block);
                    }
                    // 空 content 会被 Anthropic 拒绝, 这种消息直接丢弃
                    if (!blocks.isEmpty()) {
                        wire.add(Map.of("role", "assistant", "content", blocks));
                    }
                }
                case TOOL -> {
                    // Anthropic 没有独立的 tool 角色, 工具结果以 tool_result 块放在 user 消息里
                    List<Map<String, Object>> blocks = new ArrayList<>();
                    for (ToolResult r : m.getToolResults()) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", r.getToolCallId());
                        block.put("content", r.getContent());
                        if (r.isError()) {
                            block.put("is_error", true);
                        }
                        blocks.add(block);
                    }
                    if (!blocks.isEmpty()) {
                        wire.add(Map.of("role", "user", "content", blocks));
                    }
                }
            }
        }
        return wire;
    }

    private Map<String, Object> textMessage(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text == null ? "" : text);
        return m;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("x-api-key", props.getApiKey());
        h.set("anthropic-version", API_VERSION);
        return h;
    }

    private String endpoint() {
        return Endpoints.resolve(props.getBaseUrl(), "/v1/messages", "/v1", "/messages");
    }

    private LlmResponse parse(JsonNode resp) {
        LlmResponse out = new LlmResponse();
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();

        for (JsonNode block : resp.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                Map<String, Object> args = new LinkedHashMap<>();
                JsonNode input = block.path("input");
                if (input.isObject()) {
                    args = mapper.convertValue(input, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
                }
                calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(), args));
            }
        }

        out.setText(text.isEmpty() ? null : text.toString());
        out.setToolCalls(calls);
        out.setStopReason(resp.path("stop_reason").asText(null));
        out.setInputTokens(resp.path("usage").path("input_tokens").asInt(0));
        out.setOutputTokens(resp.path("usage").path("output_tokens").asInt(0));
        return out;
    }
}

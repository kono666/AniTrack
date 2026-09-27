package com.animetracker.agent.llm;

import com.animetracker.config.LlmProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议实现 (/v1/chat/completions).
 *
 * 适用于 DeepSeek / 通义千问 / 智谱 / Kimi / 硅基流动 等绝大多数国内厂商.
 *
 * 与 Anthropic 格式的三处主要差异:
 *   1. system 就是 messages 里的一条普通消息
 *   2. 工具调用参数 arguments 是「JSON 字符串」, 需要二次解析
 *   3. 工具结果用独立的 role:"tool" 消息承载
 */
public class OpenAiCompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);

    private final LlmProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public OpenAiCompatClient(LlmProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String providerName() {
        return "OpenAI 兼容接口";
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
        body.put("messages", toWireMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> wireTools = new ArrayList<>();
            for (ToolSpec t : tools) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.getName());
                fn.put("description", t.getDescription());
                fn.put("parameters", t.getInputSchema());
                wireTools.add(Map.of("type", "function", "function", fn));
            }
            body.put("tools", wireTools);
        }

        JsonNode resp = LlmHttp.post(restTemplate, mapper, endpoint(), headers(), body, providerName());
        return parse(resp);
    }

    private List<Map<String, Object>> toWireMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (LlmMessage m : messages) {
            switch (m.getRole()) {
                case SYSTEM -> wire.add(simple("system", m.getText()));
                case USER -> wire.add(simple("user", m.getText()));
                case ASSISTANT -> {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("role", "assistant");
                    // 部分厂商不接受 content 为 null, 这里统一给空串
                    w.put("content", m.getText() == null ? "" : m.getText());
                    if (!m.getToolCalls().isEmpty()) {
                        List<Map<String, Object>> calls = new ArrayList<>();
                        for (ToolCall c : m.getToolCalls()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", c.getName());
                            fn.put("arguments", writeArgs(c.getArguments()));
                            calls.add(Map.of("id", c.getId(), "type", "function", "function", fn));
                        }
                        w.put("tool_calls", calls);
                    }
                    wire.add(w);
                }
                case TOOL -> {
                    for (ToolResult r : m.getToolResults()) {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("role", "tool");
                        w.put("tool_call_id", r.getToolCallId());
                        w.put("content", r.getContent());
                        wire.add(w);
                    }
                }
            }
        }
        return wire;
    }

    private Map<String, Object> simple(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text == null ? "" : text);
        return m;
    }

    /** 工具参数在这里必须序列化成 JSON 字符串, 这是 OpenAI 协议的规定 */
    private String writeArgs(Map<String, Object> args) {
        try {
            return mapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 解析 arguments 字符串; 模型偶尔会给出不合法 JSON, 这里降级成空参数而不是整个请求失败 */
    private Map<String, Object> parseArgs(String raw, String toolName) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("工具 {} 的参数不是合法 JSON, 已降级为空参数: {}", toolName, raw);
            return new LinkedHashMap<>();
        }
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(props.getApiKey());
        return h;
    }

    private String endpoint() {
        return Endpoints.resolve(props.getBaseUrl(), "/v1/chat/completions", "/v1", "/chat/completions");
    }

    private LlmResponse parse(JsonNode resp) {
        LlmResponse out = new LlmResponse();
        JsonNode choice = resp.path("choices").path(0);
        JsonNode msg = choice.path("message");

        JsonNode content = msg.path("content");
        out.setText(content.isMissingNode() || content.isNull() ? null : content.asText());

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode fn = tc.path("function");
            String name = fn.path("name").asText();
            calls.add(new ToolCall(
                    tc.path("id").asText(),
                    name,
                    parseArgs(fn.path("arguments").asText(null), name)));
        }
        out.setToolCalls(calls);
        out.setStopReason(choice.path("finish_reason").asText(null));
        out.setInputTokens(resp.path("usage").path("prompt_tokens").asInt(0));
        out.setOutputTokens(resp.path("usage").path("completion_tokens").asInt(0));
        return out;
    }
}

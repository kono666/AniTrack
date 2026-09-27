package com.animetracker.agent.llm;

import com.animetracker.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** OpenAI 兼容协议映射测试 (DeepSeek / 通义 / 智谱 / Kimi 等都走这套) */
class OpenAiCompatClientTest {

    private MockRestServiceServer server;
    private OpenAiCompatClient client;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai-compat");
        props.setBaseUrl("https://api.deepseek.com");
        props.setApiKey("sk-test-123");
        props.setModel("deepseek-chat");
        props.setMaxTokens(2048);
        props.setTemperature(0.7);

        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new OpenAiCompatClient(props, restTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("system 是普通消息, 工具包成 function 对象, 鉴权用 Bearer")
    void sendsOpenAiShapedRequest() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test-123"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                // system 就是 messages 里的一条, 不像 Anthropic 那样单独提到顶层
                .andExpect(jsonPath("$.system").doesNotExist())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("你是找番助手"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].function.name").value("search_anime"))
                .andExpect(jsonPath("$.tools[0].function.parameters.type").value("object"))
                .andExpect(jsonPath("$.tools[0].function.parameters.properties.keyword.type").value("string"))
                .andExpect(jsonPath("$.tools[0].function.parameters.required[0]").value("keyword"))
                // Anthropic 风格的字段不该出现
                .andExpect(jsonPath("$.tools[0].input_schema").doesNotExist())
                .andExpect(jsonPath("$.tools[0].name").doesNotExist())
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"好的"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":50,"completion_tokens":10}}
                        """, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(
                List.of(LlmMessage.system("你是找番助手"), LlmMessage.user("推荐几部高分番")),
                List.of(searchTool()));

        server.verify();
        assertThat(resp.getText()).isEqualTo("好的");
        assertThat(resp.getStopReason()).isEqualTo("stop");
        assertThat(resp.getInputTokens()).isEqualTo(50);
        assertThat(resp.getOutputTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("工具参数必须序列化成 JSON 字符串 —— 这是最容易写错的地方")
    void serializesToolArgumentsAsJsonString() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                // content 为空时给空串, 有些厂商不接受 null
                .andExpect(jsonPath("$.messages[1].content").value(""))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].type").value("function"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].function.name").value("search_anime"))
                // arguments 是字符串而不是对象: 断言它本身是个 String
                .andExpect(jsonPath("$.messages[1].tool_calls[0].function.arguments").isString())
                // 工具结果用独立的 role=tool 消息承载, 并带上 tool_call_id
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_1"))
                .andExpect(jsonPath("$.messages[2].content").value("{\"total\":1}"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"查到了"},"finish_reason":"stop"}],"usage":{}}
                        """, MediaType.APPLICATION_JSON));

        LlmMessage assistant = LlmMessage.assistantToolCalls(null,
                List.of(new ToolCall("call_1", "search_anime", Map.of("keyword", "巨人", "limit", 5))));
        LlmMessage tools = LlmMessage.toolResults(
                List.of(ToolResult.ok("call_1", "search_anime", "{\"total\":1}")));

        client.chat(List.of(LlmMessage.user("找一下"), assistant, tools), List.of(searchTool()));
        server.verify();
    }

    @Test
    @DisplayName("解析响应里的 tool_calls: arguments 字符串要二次解析成对象")
    void parsesToolCallsFromResponse() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":null,
                          "tool_calls":[{"id":"call_9","type":"function",
                            "function":{"name":"search_anime","arguments":"{\\"keyword\\":\\"巨人\\",\\"limit\\":3}"}}]},
                          "finish_reason":"tool_calls"}],
                         "usage":{"prompt_tokens":77,"completion_tokens":12}}
                        """, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(List.of(LlmMessage.user("找巨人")), List.of(searchTool()));

        assertThat(resp.hasToolCalls()).isTrue();
        // content 为 null 时不该变成字符串 "null"
        assertThat(resp.getText()).isNull();
        assertThat(resp.getStopReason()).isEqualTo("tool_calls");
        ToolCall call = resp.getToolCalls().get(0);
        assertThat(call.getName()).isEqualTo("search_anime");
        assertThat(call.getArguments()).containsEntry("keyword", "巨人");
        assertThat(call.requireInteger("limit")).isEqualTo(3);
        assertThat(resp.getInputTokens()).isEqualTo(77);
    }

    @Test
    @DisplayName("模型给出非法 JSON 参数时降级为空参数, 而不是让整个请求失败")
    void toleratesMalformedArgumentsJson() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"tool_calls":[{"id":"call_1","type":"function",
                          "function":{"name":"search_anime","arguments":"{keyword: 巨人"}}]},
                          "finish_reason":"tool_calls"}],"usage":{}}
                        """, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(List.of(LlmMessage.user("找巨人")), List.of(searchTool()));

        assertThat(resp.hasToolCalls()).isTrue();
        assertThat(resp.getToolCalls().get(0).getArguments()).isEmpty();
        // 参数为空会让必填校验失败, 错误信息回灌给模型后它通常会自己改正
        assertThat(resp.getToolCalls().get(0).str("keyword", "")).isEmpty();
    }

    @Test
    @DisplayName("缺 choices 的空响应不抛异常")
    void toleratesEmptyResponse() {
        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(List.of(LlmMessage.user("喂")), List.of());
        assertThat(resp.getText()).isNull();
        assertThat(resp.hasToolCalls()).isFalse();
    }

    private static ToolSpec searchTool() {
        return new ToolSpec("search_anime", "按关键词搜索番剧",
                ToolSpec.schema(Map.of("keyword", ToolSpec.prop("string", "搜索关键词")), "keyword"));
    }
}

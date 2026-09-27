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

/**
 * Anthropic 协议映射测试.
 *
 * 这里不碰真实网络, 而是断言「发出去的报文长得对不对」和「收回来的报文解析得对不对」.
 * 这类地方一旦写错(比如把 system 塞进 messages), 单元测试之外很难发现 ——
 * 只有真实调用时才会收到一个 400, 而那时你甚至不知道是格式问题.
 */
class AnthropicClientTest {

    private MockRestServiceServer server;
    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setProvider("anthropic");
        props.setBaseUrl("https://api.anthropic.com");
        props.setApiKey("test-key-123");
        props.setModel("claude-sonnet-5");
        props.setMaxTokens(1024);
        props.setTemperature(0.5);

        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new AnthropicClient(props, restTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("system 走顶层字段, 工具定义用 input_schema, 请求头带 x-api-key 与版本号")
    void sendsAnthropicShapedRequest() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key-123"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.max_tokens").value(1024))
                .andExpect(jsonPath("$.system").value("你是找番助手"))
                // messages 里绝不能出现 system 角色
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("推荐几部高分番"))
                .andExpect(jsonPath("$.tools[0].name").value("search_anime"))
                .andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
                .andExpect(jsonPath("$.tools[0].input_schema.properties.keyword.type").value("string"))
                .andExpect(jsonPath("$.tools[0].input_schema.required[0]").value("keyword"))
                // OpenAI 风格的包装不该出现
                .andExpect(jsonPath("$.tools[0].function").doesNotExist())
                .andExpect(jsonPath("$.tools[0].parameters").doesNotExist())
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"好的"}],
                         "stop_reason":"end_turn",
                         "usage":{"input_tokens":100,"output_tokens":20}}
                        """, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(
                List.of(LlmMessage.system("你是找番助手"), LlmMessage.user("推荐几部高分番")),
                List.of(searchTool()));

        server.verify();
        assertThat(resp.getText()).isEqualTo("好的");
        assertThat(resp.hasToolCalls()).isFalse();
        assertThat(resp.getStopReason()).isEqualTo("end_turn");
        assertThat(resp.getInputTokens()).isEqualTo(100);
        assertThat(resp.getOutputTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("工具结果的回灌: assistant 用 tool_use 块, 结果放进 user 消息的 tool_result 块")
    void mapsToolRoundTripToContentBlocks() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(jsonPath("$.messages.length()").value(3))
                // [0] 用户提问
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                // [1] 上一轮助手的工具调用: content 是块数组
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].content[0].type").value("text"))
                .andExpect(jsonPath("$.messages[1].content[1].type").value("tool_use"))
                .andExpect(jsonPath("$.messages[1].content[1].id").value("toolu_1"))
                .andExpect(jsonPath("$.messages[1].content[1].name").value("search_anime"))
                .andExpect(jsonPath("$.messages[1].content[1].input.keyword").value("巨人"))
                // [2] 工具结果: Anthropic 没有 tool 角色, 必须包成 user 消息里的 tool_result 块
                .andExpect(jsonPath("$.messages[2].role").value("user"))
                .andExpect(jsonPath("$.messages[2].content[0].type").value("tool_result"))
                .andExpect(jsonPath("$.messages[2].content[0].tool_use_id").value("toolu_1"))
                .andExpect(jsonPath("$.messages[2].content[0].content").value("{\"total\":1}"))
                .andExpect(jsonPath("$.messages[2].content[0].is_error").doesNotExist())
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"查到了"}],"stop_reason":"end_turn","usage":{}}
                        """, MediaType.APPLICATION_JSON));

        LlmMessage assistant = LlmMessage.assistantToolCalls("我来查一下",
                List.of(new ToolCall("toolu_1", "search_anime", Map.of("keyword", "巨人"))));
        LlmMessage tools = LlmMessage.toolResults(
                List.of(ToolResult.ok("toolu_1", "search_anime", "{\"total\":1}")));

        LlmResponse resp = client.chat(List.of(
                LlmMessage.user("找一下进击的巨人"),
                assistant,
                tools), List.of(searchTool()));

        server.verify();
        assertThat(resp.getText()).isEqualTo("查到了");
    }

    @Test
    @DisplayName("工具执行失败时带上 is_error, 让模型知道这次调用没成功")
    void marksFailedToolResultAsError() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                // 只有两条消息: [0] 用户提问, [1] 工具结果(user 角色 + tool_result 块)
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content[0].type").value("tool_result"))
                .andExpect(jsonPath("$.messages[1].content[0].is_error").value(true))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"我重试"}],"usage":{}}
                        """, MediaType.APPLICATION_JSON));

        LlmMessage failing = LlmMessage.toolResults(
                List.of(ToolResult.error("toolu_9", "get_anime_detail", "执行失败: 番剧不存在")));

        client.chat(List.of(LlmMessage.user("看下详情"), failing), List.of(searchTool()));
        server.verify();
    }

    @Test
    @DisplayName("解析 tool_use 响应: 参数对象直接可用, 且能同时带文字说明")
    void parsesToolUseResponse() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("""
                        {"content":[
                           {"type":"text","text":"我先搜一下"},
                           {"type":"tool_use","id":"toolu_7","name":"search_anime","input":{"keyword":"巨人","limit":5}}
                         ],
                         "stop_reason":"tool_use",
                         "usage":{"input_tokens":42,"output_tokens":8}}
                        """, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(List.of(LlmMessage.user("找巨人")), List.of(searchTool()));

        assertThat(resp.hasToolCalls()).isTrue();
        assertThat(resp.getText()).isEqualTo("我先搜一下");
        assertThat(resp.getStopReason()).isEqualTo("tool_use");
        ToolCall call = resp.getToolCalls().get(0);
        assertThat(call.getId()).isEqualTo("toolu_7");
        assertThat(call.getName()).isEqualTo("search_anime");
        assertThat(call.getArguments()).containsEntry("keyword", "巨人");
        assertThat(call.requireInteger("limit")).isEqualTo(5);
        assertThat(resp.getInputTokens()).isEqualTo(42);
    }

    @Test
    @DisplayName("全空响应也不该抛异常, 交给上层兜底成一句道歉")
    void toleratesEmptyResponse() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        LlmResponse resp = client.chat(List.of(LlmMessage.user("喂")), List.of());

        assertThat(resp.getText()).isNull();
        assertThat(resp.hasToolCalls()).isFalse();
        assertThat(resp.getInputTokens()).isZero();
    }

    @Test
    @DisplayName("没有 system 消息时不发 system 字段, 也不发空 tools")
    void omitsEmptySystemAndTools() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(jsonPath("$.system").doesNotExist())
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        client.chat(List.of(LlmMessage.user("你好")), List.of());
        server.verify();
    }

    private static ToolSpec searchTool() {
        return new ToolSpec("search_anime", "按关键词搜索番剧",
                ToolSpec.schema(Map.of("keyword", ToolSpec.prop("string", "搜索关键词")), "keyword"));
    }
}

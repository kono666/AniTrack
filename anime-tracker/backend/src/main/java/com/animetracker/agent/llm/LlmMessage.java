package com.animetracker.agent.llm;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部统一消息模型.
 *
 * 各家协议的消息结构差异很大 —— Anthropic 用 content 数组承载 tool_use / tool_result,
 * OpenAI 用扁平的 tool_calls 字段加独立的 role:"tool" 消息.
 * 这层差异由各 LlmClient 实现负责翻译, Agent 主循环只跟这个统一模型打交道.
 */
@Data
public class LlmMessage {

    public enum Role {
        SYSTEM,
        USER,
        /** 模型的回复, 可能带工具调用请求 */
        ASSISTANT,
        /** 工具执行结果, 回灌给模型 */
        TOOL
    }

    private Role role;

    /** 文本内容 */
    private String text;

    /** 仅 ASSISTANT: 本轮请求调用的工具 */
    private List<ToolCall> toolCalls = new ArrayList<>();

    /** 仅 TOOL: 工具执行结果 */
    private List<ToolResult> toolResults = new ArrayList<>();

    public static LlmMessage system(String text) {
        return of(Role.SYSTEM, text);
    }

    public static LlmMessage user(String text) {
        return of(Role.USER, text);
    }

    public static LlmMessage assistant(String text) {
        return of(Role.ASSISTANT, text);
    }

    /** 模型带工具调用的回复 */
    public static LlmMessage assistantToolCalls(String text, List<ToolCall> calls) {
        LlmMessage m = of(Role.ASSISTANT, text);
        m.setToolCalls(calls != null ? calls : new ArrayList<>());
        return m;
    }

    /** 一批工具的执行结果 */
    public static LlmMessage toolResults(List<ToolResult> results) {
        LlmMessage m = new LlmMessage();
        m.setRole(Role.TOOL);
        m.setToolResults(results != null ? results : new ArrayList<>());
        return m;
    }

    private static LlmMessage of(Role role, String text) {
        LlmMessage m = new LlmMessage();
        m.setRole(role);
        m.setText(text);
        return m;
    }

    /** 供日志使用的内容摘要 */
    public String preview(int max) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (!toolCalls.isEmpty()) {
            s = "[调用工具 " + toolCalls.stream().map(ToolCall::getName).toList() + "] " + s;
        }
        if (!toolResults.isEmpty()) {
            s = "[工具结果 x" + toolResults.size() + "]";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

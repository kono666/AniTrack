package com.animetracker.agent.llm;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次 LLM 调用的结果. */
@Data
public class LlmResponse {

    /** 模型输出的文本, 可能为空 (此时通常是在请求调用工具) */
    private String text;

    /** 模型请求调用的工具, 为空表示本轮就是最终回答 */
    private List<ToolCall> toolCalls = new ArrayList<>();

    /** 结束原因, 如 end_turn / tool_use / stop */
    private String stopReason;

    /** 消耗的输入 token */
    private int inputTokens;

    /** 消耗的输出 token */
    private int outputTokens;

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static LlmResponse text(String text, String stopReason) {
        LlmResponse r = new LlmResponse();
        r.setText(text);
        r.setStopReason(stopReason);
        return r;
    }
}

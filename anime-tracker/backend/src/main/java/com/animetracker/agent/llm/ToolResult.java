package com.animetracker.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工具执行结果, 会被回灌给模型. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    /** 对应 ToolCall.id */
    private String toolCallId;

    /** 工具名, 仅用于日志与前端展示 */
    private String toolName;

    /** 结果内容, 统一序列化为 JSON 字符串 */
    private String content;

    /** 是否执行失败. 失败也要回灌, 模型看到错误往往能自己换个参数重试 */
    private boolean error;

    public static ToolResult ok(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, content, false);
    }

    public static ToolResult error(String callId, String toolName, String message) {
        return new ToolResult(callId, toolName, message, true);
    }
}

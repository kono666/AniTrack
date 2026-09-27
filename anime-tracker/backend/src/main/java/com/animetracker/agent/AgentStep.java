package com.animetracker.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次工具调用的完整记录.
 *
 * 这是整个 Agent 的可观测性基础 —— 每一轮「模型想调什么、传了什么参数、
 * 拿到什么结果、花了多久」都被完整记下来. 出了问题能复盘, 也方便在前端展示调用过程.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentStep {

    /** 第几轮 */
    private int round;

    /** 工具名 */
    private String toolName;

    /** 模型给出的参数 */
    private Map<String, Object> arguments = new LinkedHashMap<>();

    /** 执行结果 (JSON) 或错误信息 */
    private String result;

    /** 是否执行失败 */
    private boolean error;

    /** 耗时(毫秒) */
    private long millis;

    /**
     * 这次结果里能画成卡片的对象, 由 {@link com.animetracker.agent.tool.ToolCards} 从原始结果里挑出.
     *
     * 单独存一份而不是让前端去解析 {@link #result}:
     * result 是截断过的 JSON 字符串 (可能被砍掉尾巴), 前端解析它有失败的风险;
     * 而且那是「给模型看的」形状, 不该让界面依赖它.
     */
    private List<Map<String, Object>> cards = List.of();

    public static AgentStep ok(int round, String toolName, Map<String, Object> args,
                               String result, long millis) {
        return new AgentStep(round, toolName, args, result, false, millis, List.of());
    }

    public static AgentStep fail(int round, String toolName, Map<String, Object> args,
                                 String message, long millis) {
        return new AgentStep(round, toolName, args, message, true, millis, List.of());
    }
}

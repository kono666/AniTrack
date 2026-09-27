package com.animetracker.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 模型请求调用某个工具. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** 模型给出的调用 id, 回灌执行结果时必须原样带回 */
    private String id;

    /** 工具名 */
    private String name;

    /** 调用参数, 由模型生成, 需要做类型容错 */
    private Map<String, Object> arguments = new LinkedHashMap<>();

    /** 取字符串参数, 缺失或空值返回默认值 */
    public String str(String key, String defaultValue) {
        Object v = arguments.get(key);
        if (v == null) return defaultValue;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    /** 取整数参数, 解析失败返回默认值 */
    public int integer(String key, int defaultValue) {
        Object v = arguments.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 取可空整数, 用于「没传就是没传」的参数 */
    public Integer integerOrNull(String key) {
        Object v = arguments.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取必填整数, 缺失时抛出让模型自行纠正的参数错误 */
    public Integer requireInteger(String key) {
        Integer v = integerOrNull(key);
        if (v == null) {
            throw new IllegalArgumentException("缺少必填参数 " + key + " (需要整数)");
        }
        return v;
    }
}

package com.animetracker.agent.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具对外的协议描述.
 *
 * 这是协议层唯一认识的东西 —— 只有名字, 说明和参数结构,
 * 不含任何业务逻辑与执行能力. 让 LLM 客户端可以脱离业务独立测试.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpec {

    /** 工具名, 模型回传时必须精确匹配 */
    private String name;

    /** 给模型看的说明, 决定模型什么时候会调用它 */
    private String description;

    /** JSON Schema 形式的参数定义 */
    private Map<String, Object> inputSchema = new LinkedHashMap<>();

    /**
     * 构造一个 object 类型的 JSON Schema.
     *
     * @param properties 各参数的定义
     * @param required   必填参数名
     */
    public static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && required.length > 0) {
            schema.put("required", java.util.List.of(required));
        }
        return schema;
    }

    /** 构造单个参数定义 */
    public static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    /** 构造带枚举取值的参数定义, 限定模型只能从给定值里选 */
    public static Map<String, Object> enumProp(String description, String... values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", java.util.List.of(values));
        return p;
    }
}

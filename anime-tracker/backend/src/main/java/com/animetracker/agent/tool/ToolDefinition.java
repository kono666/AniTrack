package com.animetracker.agent.tool;

import com.animetracker.agent.llm.ToolSpec;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个 Agent 工具的完整定义: 协议描述 + 可见范围 + 执行逻辑.
 *
 * 用建造者模式声明, 让工具定义读起来接近自然语言:
 * <pre>
 * ToolDefinition.builder()
 *     .name("search_anime")
 *     .description("按关键词搜索番剧...")
 *     .access(Access.PUBLIC)
 *     .stringParam("keyword", "搜索关键词", true)
 *     .intParam("limit", "返回条数, 默认 10", false)
 *     .executor((call, user) -&gt; ...)
 *     .build();
 * </pre>
 */
@Getter
public class ToolDefinition {

    /** 工具可见范围 */
    public enum Access {
        /** 无需登录 */
        PUBLIC,
        /** 需要登录 */
        USER,
        /** 需要管理员身份 */
        ADMIN
    }

    private final ToolSpec spec;
    private final Access access;
    private final AgentToolExecutor executor;

    private ToolDefinition(ToolSpec spec, Access access, AgentToolExecutor executor) {
        this.spec = spec;
        this.access = access;
        this.executor = executor;
    }

    public String name() {
        return spec.getName();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 工具定义建造者, 顺带把 JSON Schema 生成掉 */
    public static class Builder {

        private String name;
        private String description;
        private Access access = Access.PUBLIC;
        private AgentToolExecutor executor;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 工具说明.
         *
         * 这段文字直接决定模型会不会在正确的时机调用它, 所以要写清楚
         * 「什么时候用」而不只是「它是什么」.
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder access(Access access) {
            this.access = access;
            return this;
        }

        public Builder stringParam(String key, String desc, boolean isRequired) {
            properties.put(key, ToolSpec.prop("string", desc));
            if (isRequired) {
                required.add(key);
            }
            return this;
        }

        public Builder intParam(String key, String desc, boolean isRequired) {
            properties.put(key, ToolSpec.prop("integer", desc));
            if (isRequired) {
                required.add(key);
            }
            return this;
        }

        /** 枚举参数, 限定模型只能从给定取值里挑, 能显著降低乱传参的概率 */
        public Builder enumParam(String key, String desc, boolean isRequired, String... values) {
            properties.put(key, ToolSpec.enumProp(desc, values));
            if (isRequired) {
                required.add(key);
            }
            return this;
        }

        public Builder executor(AgentToolExecutor executor) {
            this.executor = executor;
            return this;
        }

        public ToolDefinition build() {
            Objects.requireNonNull(name, "工具名不能为空");
            Objects.requireNonNull(description, "工具说明不能为空");
            Objects.requireNonNull(executor, "工具执行器不能为空");
            if (name.isBlank() || !name.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("工具名只能是下划线小写字母与数字: " + name);
            }
            // 说明是模型判断「什么时候该用这个工具」的唯一依据.
            // 留空不会报错, 但模型只能在瞎猜和不用之间二选一 —— 这类问题在联调时极难定位,
            // 所以宁可在启动阶段就拦住.
            if (description.isBlank()) {
                throw new IllegalArgumentException("工具说明不能为空: " + name);
            }
            ToolSpec spec = new ToolSpec(name, description,
                    ToolSpec.schema(properties, required.toArray(new String[0])));
            return new ToolDefinition(spec, access, executor);
        }
    }
}

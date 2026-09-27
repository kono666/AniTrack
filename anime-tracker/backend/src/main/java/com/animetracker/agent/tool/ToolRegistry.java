package com.animetracker.agent.tool;

import com.animetracker.agent.llm.ToolSpec;
import com.animetracker.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表.
 *
 * 启动时把所有 ToolProvider 的工具汇总起来, 建立「名字 -&gt; 定义」的精确映射.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> byName = new LinkedHashMap<>();

    public ToolRegistry(List<ToolProvider> providers) {
        for (ToolProvider provider : providers) {
            for (ToolDefinition def : provider.tools()) {
                String name = def.name();
                if (byName.containsKey(name)) {
                    // 重名会让模型的行为不可预测, 宁可启动失败也不要带病运行
                    throw new IllegalStateException("Agent 工具名重复: " + name);
                }
                byName.put(name, def);
            }
        }
        log.info("已注册 {} 个 Agent 工具: {}", byName.size(), byName.keySet());
    }

    /**
     * 精确查找工具.
     *
     * 刻意不做模糊匹配或前缀匹配 —— 否则模型拼错名字时可能误命中另一个工具,
     * 造成意料之外的调用.
     */
    public ToolDefinition find(String name) {
        return name == null ? null : byName.get(name);
    }

    /** 按调用者身份给出可见的工具集 */
    public List<ToolDefinition> availableFor(User user) {
        return byName.values().stream().filter(d -> allowed(d, user)).toList();
    }

    /** 送给模型的工具描述列表 (已按身份过滤) */
    public List<ToolSpec> specsFor(User user) {
        return availableFor(user).stream().map(ToolDefinition::getSpec).toList();
    }

    /** 判断某个身份能否使用某个工具 */
    public static boolean allowed(ToolDefinition def, User user) {
        return switch (def.getAccess()) {
            case PUBLIC -> true;
            case USER -> user != null;
            case ADMIN -> user != null && "ADMIN".equalsIgnoreCase(user.getRole());
        };
    }

    public int size() {
        return byName.size();
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
    }
}

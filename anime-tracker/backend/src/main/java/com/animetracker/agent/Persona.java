package com.animetracker.agent;

/**
 * Agent 的人格设定.
 *
 * 不同人格共用同一套工具框架与主循环, 只是系统提示词和可见工具不同 ——
 * 这是把「找番助手」和「运营分析」放在一个 Agent 层里实现的原因.
 */
public enum Persona {

    /** 用户端: 帮普通用户找番、管理追番 */
    USER_ASSISTANT("user-assistant", "找番助手"),

    /** 管理端: 帮管理员看平台数据、生成运营分析 */
    ADMIN_ANALYST("admin-analyst", "运营分析");

    private final String promptFile;
    private final String displayName;

    Persona(String promptFile, String displayName) {
        this.promptFile = promptFile;
        this.displayName = displayName;
    }

    /** 提示词文件名 (位于 resources/prompts/ 下) */
    public String promptFile() {
        return promptFile;
    }

    public String displayName() {
        return displayName;
    }

    /** 解析前端传来的字符串, 无法识别时回退为用户端 */
    public static Persona from(String value) {
        if (value == null || value.isBlank()) {
            return USER_ASSISTANT;
        }
        for (Persona p : values()) {
            if (p.name().equalsIgnoreCase(value) || p.promptFile.equalsIgnoreCase(value)) {
                return p;
            }
        }
        return USER_ASSISTANT;
    }
}

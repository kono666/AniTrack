package com.animetracker.agent.llm;

/**
 * 接口地址拼装.
 *
 * 用户配置的 base-url 写法五花八门 —— 有的带 /v1, 有的不带, 有的干脆写了完整路径.
 * 这里统一容错, 避免因为少写一个 /v1 就报 404 让人排查半天.
 */
final class Endpoints {

    private Endpoints() {
    }

    /**
     * @param baseUrl       用户配置的基础地址
     * @param fullPath      完整路径, 例如 /v1/chat/completions
     * @param versionPrefix 版本前缀, 例如 /v1
     * @param tail          去掉版本前缀后的剩余路径, 例如 /chat/completions
     */
    static String resolve(String baseUrl, String fullPath, String versionPrefix, String tail) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            throw new LlmException("尚未配置 llm.base-url");
        }
        // 已经写全了路径
        if (base.endsWith(fullPath)) {
            return base;
        }
        // 带版本前缀, 只补尾部
        if (base.endsWith(versionPrefix)) {
            return base + tail;
        }
        return base + fullPath;
    }
}

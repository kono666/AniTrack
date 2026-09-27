package com.animetracker.agent.llm;

import java.util.List;

/**
 * 未配置密钥时的兜底客户端.
 *
 * 选择「能启动但调用时报错」而不是「启动直接失败」:
 * 后者会让整个应用起不来, 连番剧浏览等无关功能都不可用, 排查体验很差.
 */
public class UnconfiguredLlmClient implements LlmClient {

    private static final String HINT =
            "尚未配置大模型密钥, AI 助手暂不可用。"
                    + "请设置环境变量 LLM_API_KEY (以及可选的 LLM_PROVIDER / LLM_BASE_URL / LLM_MODEL) 后重启后端。";

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools) {
        throw new LlmException(HINT);
    }

    @Override
    public String providerName() {
        return "未配置";
    }

    @Override
    public String modelName() {
        return "-";
    }
}

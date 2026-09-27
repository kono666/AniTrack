package com.animetracker.agent.llm;

import java.util.List;

/**
 * 大模型客户端抽象.
 *
 * 一次性拿到完整回复 (非流式). 流式由上层另做包装, 这样协议层保持简单,
 * 出问题也容易定位是「模型调用」还是「流式传输」的锅.
 */
public interface LlmClient {

    /**
     * 发起一轮对话.
     *
     * @param messages 完整上下文 (含 system)
     * @param tools    本次可用的工具, 为空表示不允许调用工具
     * @return 模型回复, 可能包含若干工具调用请求
     * @throws LlmException 网络失败 / 鉴权失败 / 限流 / 响应格式异常
     */
    LlmResponse chat(List<LlmMessage> messages, List<ToolSpec> tools);

    /** 协议名, 用于日志与前端展示 */
    String providerName();

    /** 模型名, 用于日志与前端展示 */
    String modelName();
}

package com.animetracker.agent.tool;

import com.animetracker.agent.llm.ToolCall;
import com.animetracker.entity.User;

/**
 * 工具的执行逻辑.
 *
 * 注意签名里没有 userId 参数 —— 当前用户由服务端注入.
 * 这是刻意的安全设计: 模型在结构上就没有「指定操作哪个用户」的能力,
 * 因此无法通过提示词注入越权读写他人数据.
 */
@FunctionalInterface
public interface AgentToolExecutor {

    /**
     * @param call 模型给出的调用 (含参数与类型容错读取方法)
     * @param user 当前登录用户, 未登录时为 null
     * @return 任意可序列化对象, 会被转成 JSON 回灌给模型
     */
    Object execute(ToolCall call, User user) throws Exception;
}

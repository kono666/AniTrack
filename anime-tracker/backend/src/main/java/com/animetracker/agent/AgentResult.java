package com.animetracker.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次 Agent 对话的产出. */
@Data
public class AgentResult {

    /** 最终回答 */
    private String answer;

    /** 调用链: 每一轮的工具调用记录 */
    private List<AgentStep> steps = new ArrayList<>();

    /** 实际消耗的轮数 */
    private int rounds;

    /** 累计输入 token */
    private int inputTokens;

    /** 累计输出 token */
    private int outputTokens;

    /** 是否因为达到轮数上限而中断 (此时 answer 是兜底文案, 不是模型的结论) */
    private boolean truncated;

    public static AgentResult of(String answer, List<AgentStep> steps, int rounds,
                                 int inputTokens, int outputTokens) {
        AgentResult r = new AgentResult();
        r.setAnswer(answer);
        r.setSteps(steps);
        r.setRounds(rounds);
        r.setInputTokens(inputTokens);
        r.setOutputTokens(outputTokens);
        return r;
    }
}

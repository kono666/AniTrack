package com.animetracker.agent.service;

import com.animetracker.agent.AgentResult;
import com.animetracker.agent.AgentStep;
import com.animetracker.agent.Persona;
import com.animetracker.agent.domain.AgentRun;
import com.animetracker.agent.domain.AgentRunStep;
import com.animetracker.agent.repository.AgentRunRepository;
import com.animetracker.agent.repository.AgentRunStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一次 Agent 运行的轮数、token、工具链与耗时落库.
 *
 * <p>这是「先量再拓展」里「量」的那一半：没有这张表，所有实验数字只能靠日志
 * 或手工抄，既不可复现也无法按时间/用户/模型维度比较。
 *
 * <p>写入放在响应返回之前（同步）而不是后台队列：一次运行只有一次 insert +
 * 几步工具调用，代价是毫秒级；而异步会引入「进程退出时最后几条没落库」的
 * 新问题。失败由调用方兜住，不影响用户拿到回答。
 */
@Service
public class AgentRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentRunRecorder.class);

    private static final int QUESTION_MAX = 4000;
    private static final int ANSWER_MAX = 8000;
    private static final int ERROR_MAX = 500;
    private static final int ARGUMENTS_MAX = 2000;
    private static final int RESULT_PREVIEW_MAX = 2000;

    private final AgentRunRepository runRepository;
    private final AgentRunStepRepository stepRepository;
    private final ObjectMapper mapper;

    public AgentRunRecorder(AgentRunRepository runRepository,
                            AgentRunStepRepository stepRepository,
                            ObjectMapper mapper) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.mapper = mapper;
    }

    /**
     * @param result    运行结果；执行抛异常时为 null
     * @param error     失败摘要；成功为 null
     * @param traceId   请求链路 id，见 common/web/TraceId
     * @return 新写入的 run id
     */
    @Transactional
    public Long record(Long userId, Long conversationId, Persona persona, String question,
                       AgentResult result, long totalMillis, String error, String traceId) {
        AgentRun run = AgentRun.builder()
                .userId(userId)
                .conversationId(conversationId)
                .persona(persona == null ? Persona.USER_ASSISTANT.promptFile() : persona.promptFile())
                .question(truncate(question, QUESTION_MAX))
                .answer(result == null ? null : truncate(result.getAnswer(), ANSWER_MAX))
                .rounds(result == null ? 0 : result.getRounds())
                .inputTokens(result == null ? 0 : result.getInputTokens())
                .outputTokens(result == null ? 0 : result.getOutputTokens())
                .totalMillis(totalMillis)
                .truncated(result != null && result.isTruncated())
                .error(truncate(error, ERROR_MAX))
                .traceId(traceId)
                .build();
        runRepository.save(run);

        if (result != null && result.getSteps() != null) {
            List<AgentRunStep> steps = new ArrayList<>();
            for (AgentStep step : result.getSteps()) {
                steps.add(AgentRunStep.builder()
                        .run(run)
                        .round(step.getRound())
                        .toolName(step.getToolName())
                        .arguments(truncate(toJson(step.getArguments()), ARGUMENTS_MAX))
                        .resultPreview(truncate(step.getResult(), RESULT_PREVIEW_MAX))
                        .error(step.isError())
                        .millis(step.getMillis())
                        .build());
            }
            stepRepository.saveAll(steps);
        }
        return run.getId();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("工具参数序列化失败，降级为字符串: {}", e.getMessage());
            return String.valueOf(value);
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}

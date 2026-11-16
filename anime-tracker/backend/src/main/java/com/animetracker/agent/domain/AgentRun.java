package com.animetracker.agent.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 一次 Agent 运行的汇总记录 —— 评测与排障的源头。
 *
 * <p>与 {@link AgentConversation} 的分工：会话表存的是聊天文本（且访客不落库），
 * 这张表每次运行都写一行，记的是轮数、token、耗时、是否截断、traceId。
 * 两者通过 conversationId 关联，但不挂外键：删会话不该带走测量数据。
 */
@Entity
@Table(name = "agent_run")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 访客为 null */
    @Column(name = "user_id")
    private Long userId;

    /** 访客或首次对话为 null */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 人格标识，见 Persona.promptFile() */
    @Column(nullable = false, length = 32)
    private String persona;

    /** 本次提问原文（已按 llm.max-input-length 截断） */
    @Column(nullable = false, length = 4000)
    private String question;

    /** 最终回答；失败时为 null */
    @Column(length = 8000)
    private String answer;

    /** 实际消耗的轮数 */
    @Column(nullable = false)
    private int rounds;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "total_millis", nullable = false)
    private long totalMillis;

    /** 是否因达到轮数上限而中断（此时 answer 是兜底文案） */
    @Column(nullable = false)
    private boolean truncated;

    /** 执行失败时的异常摘要；成功为 null */
    @Column(length = 500)
    private String error;

    /** 请求链路 id，见 common/web/TraceId */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

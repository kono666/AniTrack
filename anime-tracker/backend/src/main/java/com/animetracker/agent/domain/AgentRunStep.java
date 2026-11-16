package com.animetracker.agent.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 一次 Agent 运行中的单步工具调用。
 *
 * <p>为什么单独一张表而不是塞进 run 的 JSON 列：评测要按工具名、轮数过滤，
 * 也要统计失败率与耗时；展开 JSON 既不能建索引，也让 SQL 统计变得难写。
 */
@Entity
@Table(name = "agent_run_step")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRunStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    /** 第几轮 */
    @Column(nullable = false)
    private int round;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    /** 模型给出的参数（JSON 文本） */
    @Column(length = 2000)
    private String arguments;

    /** 工具结果预览，截断到 2000 字符 */
    @Column(name = "result_preview", length = 2000)
    private String resultPreview;

    @Column(name = "is_error", nullable = false)
    private boolean error;

    @Column(nullable = false)
    private long millis;
}

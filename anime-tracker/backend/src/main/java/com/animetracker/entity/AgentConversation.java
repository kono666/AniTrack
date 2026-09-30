package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 一次 Agent 会话 —— 一个话题下的多轮对话.
 *
 * 只有登录用户才有会话记录. 未登录访客可以正常对话(只能用公开工具), 但不落库:
 * 没有归属主体的数据存下来只会变成垃圾, 也没法做权限校验.
 */
@Entity
@Table(name = "agent_conversation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 人格标识, 见 Persona 枚举 */
    @Column(nullable = false, length = 32)
    private String persona;

    /** 会话标题, 取首条提问的前若干字, 方便在列表里辨认 */
    @Column(length = 60)
    private String title;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 两条时间戳取自同一次 {@code now()} —— 分两次调在纳秒级时钟上会落进不同的微秒,
     *  插入的行看着就像"被编辑过"。完整理由见 {@link ReviewReply} 的同名方法。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (messageCount == null) {
            messageCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

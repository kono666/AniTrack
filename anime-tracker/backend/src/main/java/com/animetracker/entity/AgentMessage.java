package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 会话里的一条消息.
 *
 * 只存 user / assistant 两种角色 —— 工具调用明细属于「过程」而不是「对话」,
 * 回灌给模型的历史里也不需要它们(模型自己会重新决定要不要调工具),
 * 存下来只会让表迅速膨胀.
 */
@Entity
@Table(name = "agent_message")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AgentConversation conversation;

    /** user 或 assistant */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

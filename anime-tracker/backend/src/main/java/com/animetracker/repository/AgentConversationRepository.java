package com.animetracker.repository;

import com.animetracker.entity.AgentConversation;
import com.animetracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {

    List<AgentConversation> findByUserOrderByUpdatedAtDesc(User user);

    /**
     * 按 id + 归属人查询.
     *
     * 刻意不写成 findById 之后再用 getUserId() 比对: 把归属条件直接放进 SQL,
     * 既少一次对象图访问, 也杜绝了「忘记比对」这类疏漏 —— 越权读取会话就是从这里漏出去的.
     */
    Optional<AgentConversation> findByIdAndUser(Long id, User user);

    long countByUser(User user);
}

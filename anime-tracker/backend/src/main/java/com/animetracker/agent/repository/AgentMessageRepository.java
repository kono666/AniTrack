package com.animetracker.agent.repository;

import com.animetracker.agent.domain.AgentConversation;
import com.animetracker.agent.domain.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    /** 按写入顺序取回, 这个顺序就是对话顺序 */
    List<AgentMessage> findByConversationOrderByIdAsc(AgentConversation conversation);

    void deleteByConversation(AgentConversation conversation);

    long countByConversation(AgentConversation conversation);
}

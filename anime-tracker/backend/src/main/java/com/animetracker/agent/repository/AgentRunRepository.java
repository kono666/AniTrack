package com.animetracker.agent.repository;

import com.animetracker.agent.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    /** 最近若干次运行，前端/评测脚本先按时间倒序看 */
    List<AgentRun> findTop20ByOrderByCreatedAtDesc();
}

package com.animetracker.agent.repository;

import com.animetracker.agent.domain.AgentRun;
import com.animetracker.agent.domain.AgentRunStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunStepRepository extends JpaRepository<AgentRunStep, Long> {

    List<AgentRunStep> findByRunOrderByIdAsc(AgentRun run);
}

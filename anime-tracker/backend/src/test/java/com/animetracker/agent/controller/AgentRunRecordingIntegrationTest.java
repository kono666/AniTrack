package com.animetracker.agent.controller;

import com.animetracker.agent.AgentOrchestrator;
import com.animetracker.agent.AgentResult;
import com.animetracker.agent.AgentStep;
import com.animetracker.agent.domain.AgentRun;
import com.animetracker.agent.domain.AgentRunStep;
import com.animetracker.agent.repository.AgentRunRepository;
import com.animetracker.agent.repository.AgentRunStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 一次对话跑完之后，agent_run / agent_run_step 里真的有一行可查的记录.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-agent-run;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false",
        "llm.rate-limit-per-minute=100"
})
@ActiveProfiles("dev")
class AgentRunRecordingIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AgentRunRepository runRepository;
    @Autowired
    private AgentRunStepRepository stepRepository;
    @MockBean
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void clean() {
        stepRepository.deleteAll();
        runRepository.deleteAll();
    }

    @Test
    @DisplayName("一次对话会落一条 run 和它对应的工具步")
    void recordsRunAndSteps() {
        AgentStep ok = AgentStep.ok(1, "get_ranking", Map.of("limit", 20), "{\"rows\":3}", 12);
        AgentStep failed = AgentStep.fail(2, "search_anime", Map.of("keyword", "x"), "boom", 7);
        AgentResult result = AgentResult.of("推荐这几部", List.of(ok, failed), 2, 120, 30);
        when(orchestrator.run(any(), any(), any(), any(), any())).thenReturn(result);

        ResponseEntity<Map> resp = rest.postForEntity("/api/agent/chat",
                Map.of("message", "找几部番"), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        List<AgentRun> runs = runRepository.findAll();
        assertThat(runs).hasSize(1);
        AgentRun run = runs.get(0);
        assertThat(run.getQuestion()).isEqualTo("找几部番");
        assertThat(run.getAnswer()).isEqualTo("推荐这几部");
        assertThat(run.getRounds()).isEqualTo(2);
        assertThat(run.getInputTokens()).isEqualTo(120);
        assertThat(run.getOutputTokens()).isEqualTo(30);
        assertThat(run.isTruncated()).isFalse();
        assertThat(run.getError()).isNull();
        assertThat(run.getTraceId()).isEqualTo(resp.getHeaders().getFirst("X-Trace-Id"));

        List<AgentRunStep> steps = stepRepository.findByRunOrderByIdAsc(run);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getToolName()).isEqualTo("get_ranking");
        assertThat(steps.get(0).getRound()).isEqualTo(1);
        assertThat(steps.get(0).isError()).isFalse();
        assertThat(steps.get(0).getArguments()).contains("limit");
        assertThat(steps.get(1).getToolName()).isEqualTo("search_anime");
        assertThat(steps.get(1).isError()).isTrue();
    }
}

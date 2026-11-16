package com.animetracker.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 在真容器里的端到端行为：响应头、响应体、以及调用方自带的值.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-traceid;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@ActiveProfiles("dev")
class TraceIdIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("响应头与响应体里的 traceId 是同一个")
    void headerAndBodyShareTraceId() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/agent/info", Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String header = resp.getHeaders().getFirst(TraceId.HEADER);
        assertThat(header).isNotBlank();
        assertThat(resp.getBody()).containsEntry("traceId", header);
    }

    @Test
    @DisplayName("调用方带来的合法 traceId 会原样回传")
    void incomingTraceIdIsKept() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceId.HEADER, "client-trace-1");

        ResponseEntity<Map> resp = rest.exchange("/api/agent/info", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(resp.getHeaders().getFirst(TraceId.HEADER)).isEqualTo("client-trace-1");
        assertThat(resp.getBody()).containsEntry("traceId", "client-trace-1");
    }
}

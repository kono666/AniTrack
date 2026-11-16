package com.animetracker.common.web;

import com.animetracker.dto.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.remove(TraceId.MDC_KEY);
    }

    @Test
    @DisplayName("没有请求头时生成 traceId，写进响应头与 MDC，结束后清理 MDC")
    void generatesAndClears() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> duringChain.set(TraceId.current()));

        String traceId = response.getHeader(TraceId.HEADER);
        assertNotNull(traceId);
        assertEquals(16, traceId.length());
        assertEquals(traceId, duringChain.get());
        assertNull(MDC.get(TraceId.MDC_KEY), "请求结束后必须清理 MDC");
    }

    @Test
    @DisplayName("调用方带来的合法 traceId 原样保留")
    void keepsValidIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader(TraceId.HEADER, "abc-123_XYZ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("abc-123_XYZ", response.getHeader(TraceId.HEADER));
    }

    @Test
    @DisplayName("非法 traceId（空白/换行/超长）不采信，重新生成")
    void rejectsUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader(TraceId.HEADER, "bad value\n");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String traceId = response.getHeader(TraceId.HEADER);
        assertNotNull(traceId);
        assertNotEquals("bad value\n", traceId);
        assertTrue(traceId.matches("[0-9a-f]{16}"));
    }

    @Test
    @DisplayName("ApiResponse 自动带上当前 MDC 里的 traceId")
    void apiResponseCarriesTraceId() {
        MDC.put(TraceId.MDC_KEY, "trace-42");
        try {
            assertEquals("trace-42", ApiResponse.success("ok").getTraceId());
        } finally {
            MDC.remove(TraceId.MDC_KEY);
        }
    }

    @Test
    @DisplayName("runWith 把 traceId 带到 worker 线程，结束后恢复线程原值")
    void runWithPropagatesAndRestores() throws Exception {
        MDC.put(TraceId.MDC_KEY, "parent");
        AtomicReference<String> seen = new AtomicReference<>();
        Thread worker = new Thread(() -> TraceId.runWith("child", () -> seen.set(TraceId.current())));
        worker.start();
        worker.join();

        assertEquals("child", seen.get(), "worker 线程上应看到传入的 traceId");
        assertEquals("parent", TraceId.current(), "父线程的 MDC 不应被 worker 改掉");
    }
}

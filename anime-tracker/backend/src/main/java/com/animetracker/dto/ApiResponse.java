package com.animetracker.dto;

import com.animetracker.common.web.TraceId;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应封装.
 *
 * @param <T> data 字段的类型
 */
@NoArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer page;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer pageSize;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer total;

    /** 本次请求的链路 id, 与响应头 X-Trace-Id 同值; 非 HTTP 线程上为 null */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceId;

    // ── 构造器 ──────────────────────────────────────────

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceId.current();
    }

    // ── 工厂方法 ────────────────────────────────────────

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    /** 带分页信息的成功响应 */
    public static <T> ApiResponse<T> paged(T data, int page, int pageSize, int total) {
        ApiResponse<T> resp = new ApiResponse<>(200, "success", data);
        resp.page = page;
        resp.pageSize = pageSize;
        resp.total = total;
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(400, message, null);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, message, null);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(401, message, null);
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return new ApiResponse<>(403, message, null);
    }

    // ── getters (Lombok 不生成含 JsonInclude 字段的 getter) ──

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public Integer getPage() { return page; }
    public Integer getPageSize() { return pageSize; }
    public Integer getTotal() { return total; }
    public String getTraceId() { return traceId; }

    public void setCode(int code) { this.code = code; }
    public void setMessage(String message) { this.message = message; }
    public void setData(T data) { this.data = data; }
    public void setPage(Integer page) { this.page = page; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public void setTotal(Integer total) { this.total = total; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}

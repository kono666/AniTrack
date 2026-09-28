package com.animetracker.config;

import com.animetracker.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「调用方把请求写错了」这一类异常该回什么.
 *
 * <p>改动前的实际表现（2026-09-28 在真实运行的 jar 上复现过）：少传一个查询参数、
 * 参数类型写错、方法用错、Content-Type 用错、请求体不是合法 JSON —— 五种情况全部返回
 * <b>500「服务器内部错误」</b>，并在日志里各记一条 ERROR 级的 "Unexpected error"。
 * 原因是这些异常没有单独的处理器，掉进了最后的兜底分支。
 *
 * <p>后果是双向的：调用方以为服务端挂了（于是重试、或者去看服务端日志），
 * 而服务端日志被这类「其实怪调用方」的 ERROR 泡着，真异常反而不好找。
 *
 * <p>这里逐个钉住状态码与文案。文案之所以要求带上参数名，是因为那是调用方自己发来的
 * 请求，说出来能让写调用方的人一眼定位；而原始异常消息（可能含类名、目标类型）
 * 一律不许外传 —— 所以最后两例专门断言「内部细节没有出现在响应里」。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("缺少必填查询参数 -> 400, 并指出是哪个参数")
    void missingParameterIsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMissingParameter(new MissingServletRequestParameterException("subjectId", "Integer"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).isEqualTo("缺少必填参数: subjectId");
    }

    @Test
    @DisplayName("参数类型不对 -> 400, 并指出是哪个参数")
    void typeMismatchIsBadRequest() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "animeId", null, new IllegalArgumentException("转换失败"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(e);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).isEqualTo("参数 animeId 格式不正确");
    }

    @Test
    @DisplayName("请求体不是合法 JSON -> 400, 且不把原始异常消息带出去")
    void unreadableBodyIsBadRequestWithoutLeakingDetails() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("JSON parse error: 内部类名不该外传", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(e);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage())
                .isEqualTo("请求体格式不正确")
                .doesNotContain("parse", "类名");
    }

    @Test
    @DisplayName("请求方法不对 -> 405 (不是 500)")
    void wrongMethodIsMethodNotAllowed() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(e);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().getCode()).isEqualTo(405);
        assertThat(response.getBody().getMessage()).isEqualTo("该接口不支持 POST 请求");
    }

    @Test
    @DisplayName("Content-Type 不对 -> 415 (不是 500)")
    void wrongContentTypeIsUnsupportedMediaType() {
        HttpMediaTypeNotSupportedException e = new HttpMediaTypeNotSupportedException("text/plain");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMediaTypeNotSupported(e);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody().getCode()).isEqualTo(415);
    }

    /**
     * 新增的四个处理器不能把兜底吃掉：真正没预料到的异常仍然必须是 500，
     * 而且响应里只有一句笼统的提示 —— 堆栈留在服务端日志里，不上公网。
     */
    @Test
    @DisplayName("真正的未知异常仍然是 500, 且不外泄内部细节")
    void unexpectedExceptionStillReturnsInternalError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleOther(new IllegalStateException("数据库连接串 jdbc:xxx 不该出现在响应里"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getMessage())
                .isEqualTo("服务器内部错误")
                .doesNotContain("jdbc");
    }
}

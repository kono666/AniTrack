package com.animetracker.config;

import com.animetracker.dto.ApiResponse;
import com.animetracker.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验失败.
     *
     * 只取注解上写的 message, 不再拼接字段名 —— 这些提示语要原样展示给用户,
     * 「password: 密码必须同时包含字母和数字」多出来的英文前缀对用户没有意义.
     * 之所以能这么做, 是因为所有校验注解都集中在 RequestDTO 里且都写了中文
     * message; 若将来新增注解忘了写 message, 这里就会漏出英文默认文案.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(msg.isEmpty() ? "参数校验失败" : msg));
    }

    /** 权限不足 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.forbidden("无权限访问"));
    }

    /**
     * 路径不存在.
     *
     * 不加这一条, 请求一个不存在的地址会掉进下面的兜底处理器, 变成 500
     * 「服务器内部错误」并被记成 ERROR 级日志. 后果不只是响应码难看:
     * 公网上扫描器天天在试 /wp-login.php 这类路径, 每一下都会往日志里写一条
     * 假的「Unexpected error」, 真的异常很快就被淹掉.
     *
     * 所以这里要明确返回 404, 而且用 debug 级记 —— 路径没找到是预期内的事.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        log.debug("No handler for {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "接口不存在"));
    }

    // ── 「调用方把请求写错了」这一类 ────────────────────────────
    //
    // 这四个异常都在参数绑定/路由阶段抛出, 表示请求本身不合法, 服务端没出任何问题.
    // 不单独登记的话它们会掉进最下面的兜底处理器, 于是同时发生两件不该发生的事:
    //
    //   1) 调用方收到 500「服务器内部错误」. 前端只能提示「服务异常, 请稍后再试」,
    //      而真实原因是少传了一个参数 —— 该改的是调用方, 不是服务端;
    //   2) 日志里多一条 ERROR 级的 "Unexpected error". 公网上的扫描器每天都在乱试
    //      路径与参数, 这些噪音会把真正的异常淹掉 (与上面 404 那条同一个道理).
    //
    // 消息里带上出错的参数名: 那是调用方自己发来的请求, 说出来不泄露任何服务端信息,
    // 但能让写调用方的人一眼定位. 反之, 原始异常消息一律不外传
    // (里面可能含类名、目标类型这类内部结构).
    //
    // 记录级别用 debug: 请求写错是预期内会发生的事, 不该占用 ERROR 级日志.

    /** 少了必填的查询参数, 例如 /api/review/list 没带 subjectId */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return badRequest("缺少必填参数: " + e.getParameterName());
    }

    /** 参数值类型不对, 例如 ?animeId=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数 " + e.getName() + " 格式不正确");
    }

    /** 请求体不是合法 JSON, 或字段类型对不上 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return badRequest("请求体格式不正确");
    }

    /** 请求方法不对, 例如对只读接口发了 POST */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("请求方法不支持: {}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(405, "该接口不支持 " + e.getMethod() + " 请求"));
    }

    /** Content-Type 不对, 例如把 JSON 接口用 text/plain 调 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.debug("不支持的 Content-Type: {}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(415, "请求内容类型不受支持, 本服务只接受 JSON"));
    }

    /** 上面几个 400 共用: 统一的记录方式 + 统一的响应结构 */
    private static ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        log.debug("客户端请求不合法: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    /** 其他未捕获异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }
}

package com.animetracker.config;

import com.animetracker.dto.ApiResponse;
import com.animetracker.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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

    /** 其他未捕获异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }
}

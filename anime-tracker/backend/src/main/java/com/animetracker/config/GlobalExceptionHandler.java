package com.animetracker.config;

import com.animetracker.dto.ApiResponse;
import com.animetracker.exception.BusinessException;
import com.animetracker.service.AvatarService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
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
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
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

    /**
     * 查询参数上的校验没通过, 例如 /api/bangumi/search?page=0.
     *
     * <p>和上面那条 MethodArgumentNotValidException 是两回事: 那条管的是
     * @RequestBody 里的字段, 这条管的是 @RequestParam / @PathVariable 上的注解.
     * 两者抛的异常类型不同, 少登记一条, 校验失败就会被兜底处理器接走变成 500 ——
     * 那正是这次给 BangumiController 加 @Validated 之前先补上这条的原因.
     *
     * <p>取值方式和上面保持一致: 只取注解里写的中文 message, 不拼字段名.
     * 如果哪天新加的注解忘了写 message, 这里会退化成「参数校验失败」而不是漏英文.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .collect(Collectors.joining("；"));
        return badRequest(msg.isEmpty() ? "参数校验失败" : msg);
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

    /**
     * 上传的文件超过了 {@code spring.servlet.multipart.max-file-size}.
     *
     * <p><b>不登记这一条的话, 最常见的那个错误会变成 500。</b> 它在 multipart 解析阶段
     * 抛出, 兜底处理器接走之后用户看到的是「服务器内部错误」——而真实原因是"图传大了
     * 一点", 该改的是用户手上的文件, 不是服务端。同时每次都会往日志里写一条 ERROR,
     * 把真正的异常淹掉(与上面 404、参数错那几条同一个道理)。
     *
     * <p>消息里带上限值: 用户唯一能采取的行动就是"换张小的", 不告诉他界限在哪,
     * 他只能反复试。这个数字在 {@code application.yml} 与
     * {@link com.animetracker.service.AvatarService#MAX_BYTES} 里各写了一份(必须同值,
     * 理由见那个常量), 所以这里不写死 —— 从异常里读到的客户端声明大小反而是不可信的,
     * 干脆只说规定。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.debug("上传内容超过限制: {}", e.getMessage());
        return badRequest("图片不能超过 " + (AvatarService.MAX_BYTES / 1024) + "KB");
    }

    /**
     * multipart 请求本身是坏的 —— 少了那一部分({@code MissingServletRequestPartException})
     * 或者报文格式不对({@code MultipartException} 的其余子类)。
     *
     * <p>与上面那条同一个理由: 不登记就掉进兜底变 500, 而它同样是调用方的问题。
     * 典型触发是"POST 了 JSON 却没有带 file 那一部分", 那在小程序/Postman 里试接口时
     * 非常容易发生。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e) {
        log.debug("multipart 请求不合法: {}", e.getMessage());
        return badRequest("请选择一张图片后再上传");
    }

    /** 上面几个 400 共用: 统一的记录方式 + 统一的响应结构 */
    private static ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        log.debug("客户端请求不合法: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    /**
     * 异步请求超时 —— DeferredResult/SseEmitter 到了自己声明的时限还没有结果.
     *
     * 不登记这一条的话它会掉进下面的兜底处理器, 变成 500「服务器内部错误」外加一条
     * ERROR 日志. 两处都不对: 服务端并没有出错, 只是这次调用太久了, 该让前端提示
     * 「稍后重试」而不是「服务异常」; 而这类超时是**配置预期内**会发生的事
     * (阈值就在 llm.overall-timeout-ms 里写着), 不该占用 ERROR 级日志.
     *
     * 504 而不是 408: 408 是「你发得太慢」, 这里慢的是我们自己的上游.
     *
     * <p><b>SSE 请求只能回空体</b>, 理由见 {@link #clientAcceptsJson}: 这不是为了省字节,
     * 而是带体会坏. /api/agent/chat/stream 上声明了 produces=text/event-stream, 于是
     * 这一次请求能产出的类型就只剩 event-stream, 而 SSE 客户端的 Accept 里没有 JSON ——
     * 硬把 ApiResponse 序列化出去会以 HttpMediaTypeNotAcceptableException(406) 收场,
     * 容器接着把这次失败转发给 /error, 而 /error 落在 Spring Security 的
     * anyRequest().authenticated() 上, 匿名访客最终拿到的是 401「请先登录」:
     * 一次超时被报成了没登录, 客户端还会把它当成认证失败处理 —— 实测 JDK 的
     * HttpURLConnection 直接抛 HttpRetryException("cannot retry ... in streaming mode").
     * 空体没有可协商的类型, 这条链就断在第一步.
     *
     * <p>也不往流里补一条 error 事件: 超时回调触发时 emitter 已经被标记成 completed
     * (实测发什么都返回 "ResponseBodyEmitter has already completed"), 想推成事件就得
     * 自己再挂一个看门狗定时器; 而前端对「流断了却没有 done」本来就有兜底提示
     * (frontend/src/api/agentStream.js), 不值当.
     *
     * <p>顺带说明这里和「流已经开始推之后才超时」的关系: 那种情况下响应早已提交, 504
     * 只会被容器忽略, 客户端看到的就是一条推了一半、没有 done 的流 —— 前端同样按上面
     * 那条兜底处理.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ApiResponse<Void>> handleAsyncTimeout(AsyncRequestTimeoutException e,
                                                                HttpServletRequest request) {
        log.warn("异步请求超时, 已按 504 结束: {} {}", request.getMethod(), request.getRequestURI());
        if (!clientAcceptsJson(request)) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ApiResponse.error(504, "AI 响应超时, 请稍后重试; 把问题问得短一些会快很多"));
    }

    /**
     * 这次请求能不能带一个 JSON 响应体出去.
     *
     * 没写 Accept、写了 {@code *&#47;*} 或写了非法值都算能 —— 那是最常见的情况,
     * 也最宽容(与改动前的行为一致); 只有明确只要别的类型(SSE 的 text/event-stream)
     * 才算不能. 于是这条判断的适用面刚好压在真正会出事的那一类请求上.
     */
    private static boolean clientAcceptsJson(HttpServletRequest request) {
        try {
            List<MediaType> accepted = MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT));
            return accepted.isEmpty()
                    || accepted.stream().anyMatch(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON));
        } catch (InvalidMediaTypeException ex) {
            // 头写坏了不该让响应也跟着坏掉, 按最宽容处理
            return true;
        }
    }

    /** 其他未捕获异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }
}

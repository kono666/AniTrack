package com.animetracker.controller;

import com.animetracker.config.AuthRateLimiter;
import com.animetracker.config.CurrentUser;
import com.animetracker.dto.ApiResponse;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.service.AvatarService;
import com.animetracker.service.NotificationService;
import com.animetracker.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.*;

/**
 * 用户自己的东西: 注册 / 登录取 token、自己的资料、自己的密码、自己的通知、自己的头像。
 *
 * <p><b>类上的 {@code @Validated} 是通知列表那两个分页参数生效的前提</b> —— 参数级的
 * {@code @Min}/{@code @Max} 只在被它标注过的 bean 上装配, 少了它 {@code ?limit=100000}
 * 会一路走到 service, 接口照样 200, 看起来像"约束写了但没起作用"。它与
 * {@code AdminController} / {@code ReviewController} 是同一个写法, 加在这里是因为
 * V11 这一批才第一次有带分页参数的端点落在本类上。
 */
@Validated
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final AuthRateLimiter authRateLimiter;
    private final NotificationService notificationService;
    private final AvatarService avatarService;

    public UserController(UserService userService, AuthRateLimiter authRateLimiter,
                          NotificationService notificationService, AvatarService avatarService) {
        this.userService = userService;
        this.authRateLimiter = authRateLimiter;
        this.notificationService = notificationService;
        this.avatarService = avatarService;
    }

    /**
     * 注册.
     *
     * <p>限流检查刻意放在方法体第一行 —— @Valid 在进方法体之前就跑完了, 所以
     * 「请求体不合法」的请求不会被计入配额. 只有真的会走到 BCrypt 的请求才消耗它,
     * 理由详见 {@link AuthRateLimiter} 的类注释.
     *
     * <p>这里用的是 getRemoteAddr(): 反向代理后面要拿到真实访客 IP, 靠的是
     * server.forward-headers-strategy: native(见 application.yml 的 server 块),
     * 由 Tomcat 在进应用之前按 X-Forwarded-For 改写这个返回值. 也就是说
     * 「代理的头可不可信」由容器判断, 这一层只管取用 —— 自己读 X-Forwarded-For
     * 是错的, 那个头客户端随便就能写.
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req,
                                                    HttpServletRequest http) {
        authRateLimiter.checkRegister(http.getRemoteAddr());
        return ApiResponse.success("注册成功", userService.register(req));
    }

    /**
     * 登录. 限流位置与理由同 register.
     *
     * <p>{@code getRemoteAddr()} 取两次(限流一次, 落事件一次)而不是取出来存一个局部变量:
     * 它是一次没有副作用的取值, 而存变量就要给它起个名字、读的人还得回去看那个名字
     * 是不是同一个意思. 两次调用之间也不会有东西改变它 —— 这个请求的地址是固定的.
     *
     * <p>它现在多了一个下游: {@code login_event.ip}. 那一列会长期留在库里, 所以顺带
     * 说明**存的是地址而不是身份** —— 与 {@code X-Forwarded-For} 无关, 用的是容器
     * 改写过的 {@code getRemoteAddr()}; 而用户名**刻意不存**(理由见 V16 的头部注释).
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                                 HttpServletRequest http) {
        authRateLimiter.checkLogin(http.getRemoteAddr());
        return ApiResponse.success("登录成功", userService.login(req, http.getRemoteAddr()));
    }

    /**
     * 获取用户信息 (管理可查任意, 普通用户只能查自己).
     *
     * <p>两个分支都用 {@code throw BusinessException} 而不是直接 return 一个
     * code 是 401/403 的 ApiResponse —— 区别在**HTTP 状态码**上. 直接返回的那个写法
     * 只把数字写在响应体里, HTTP 状态仍是 200: 前端拦截器看的是状态码, 于是这种
     * 「接口层拒绝」在客户端眼里是一次**成功**(拿到的 data 是 null), curl、
     * 监控、以及任何按状态码判断的调用方也都会当成成功. 抛异常则交给
     * {@link com.animetracker.config.GlobalExceptionHandler}, 状态码与响应体里的 code
     * 一致, 响应体的形状一个字都没变.
     *
     * <p>这也是全项目唯一一处这样写的地方(其余 22 处拒绝都走 BusinessException,
     * 过滤器链上的拒绝本来就是真的 401/403). 改成一致之后, 「没登录」与「没权限」
     * 在 HTTP 这一层与 SecurityConfig 给出的语义就完全对齐了.
     *
     * <p>顺带把两个条件拆开: 未登录与越权是两件事, 混在一个 if 里会让「回 401 还是
     * 403」取决于条件的写法, 而不是取决于发生了什么.
     */
    @GetMapping("/info/{userId}")
    public ApiResponse<Map<String, Object>> getUserInfo(
            @PathVariable Long userId,
            @CurrentUser User currentUser) {
        // 走不到这里: 这个路径不在 SecurityConfig 的免登录名单里, 匿名请求在过滤器链
        // 上就被拦成 401 了. 留着是兜底 —— 万一哪天它被放行, 这里也必须拒绝而不是
        // 因为 currentUser 为 null 而抛 NPE.
        if (currentUser == null) {
            throw BusinessException.unauthorized("未登录");
        }
        // 非管理员只能查自己
        if (!"ADMIN".equals(currentUser.getRole()) && !currentUser.getId().equals(userId)) {
            throw BusinessException.forbidden("无权查看该用户信息");
        }
        User user = userService.getUserById(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("role", user.getRole());
        data.put("status", user.getStatus());
        data.put("createdAt", user.getCreatedAt());
        return ApiResponse.success(data);
    }

    /**
     * 我的通知: 有人回复了我的评论 / 赞了我的评论 / 赞了我的回复, 最新的在最上面.
     *
     * <p><b>它取代了 {@code GET /api/user/received-replies}</b>(V8 的那个端点已删)。
     * 通知表建起来之后, "我收到的回复"就是这里 {@code type='REPLY'} 的一个子集, 留着旧
     * 端点就有两个真源, 而个人页会并排出现两块内容高度相似的区块。删除是有意的破坏性
     * 契约变更, 没有留兼容窗口: 前后端同轮发布。
     *
     * <p><b>为什么落在 UserController 而不是别处</b>: 分法按的是**路径前缀的所有权** ——
     * 全仓每个控制器各占一个前缀({@code /api/review}、{@code /api/track}、…), 这个端点
     * 是 {@code /api/user/...}, 归这里。数据本身由 {@link NotificationService} 出,
     * 这个类只负责"它挂在哪个地址上"。
     *
     * <p>三个通知端点**都不在** SecurityConfig 的公开清单里, 这是有意的: 它们答的都是
     * "我的", 必须知道"我"是谁. 匿名访问在过滤器链上就是 401, 走不到这些方法 —— 方法体
     * 里那个判空是兜底(与 {@link #getUserInfo} 同一个理由), 不是主要防线。
     *
     * <p>{@code page}/{@code limit} 的默认值写在 {@code defaultValue} 上而不是靠
     * {@code int} 的零值: 少了它, 不带分页参数的请求会拿到 {@code page=0}, 而 0 会被
     * {@code @Min(1)} 拦成 400 —— 「不带参数」变成错误是说不通的。上限 50 与评论列表
     * 同一个量级(两者都是用户自己看的一屏), 而管理端那张全站表用的是 100。
     */
    @GetMapping("/notifications")
    public ApiResponse<Map<String, Object>> getNotifications(
            @CurrentUser User user,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 50, message = "每页最多 50 条") int limit) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return ApiResponse.success(notificationService.getNotificationPage(user, page, limit));
    }

    /**
     * 未读条数, 给导航栏的红点用.
     *
     * <p>单独一个端点而不是让前端数列表里的未读: 红点是**每个页面**都要显示的东西,
     * 而列表只在个人页拉。让它走列表就意味着每进一个页面都拉一页通知回来只为了数几个
     * 布尔值。这里只回 {@code {count: N}}。
     */
    @GetMapping("/notifications/unread-count")
    public ApiResponse<Map<String, Object>> getUnreadCount(@CurrentUser User user) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return ApiResponse.success(Map.of("count", notificationService.getUnreadCount(user)));
    }

    /**
     * 把当前用户的未读全部标为已读.
     *
     * <p>用 PUT 而不是 POST: 它把「已读」这个状态设成一个确定值、重复调用结果相同
     * (幂等), 与 {@code AdminController} 的解锁端点选 PUT 是同一条理由 —— 而这条的
     * 幂等性还有一层具体的必要: 用户每进一次个人页就会调一次它, 万一重复发送,
     * 第二次不能把第一次的"什么时候读的"覆盖掉。
     *
     * <p><b>刻意不做单条已读</b>: 用户的心智是"打开看一眼就都算看过了", 逐条已读要配
     * 一套逐条交互(每条一个按钮, 或者"滚到哪算哪"), 而那是红点该干的事 —— 没有红点的
     * 时候没人会去点。
     */
    @PutMapping("/notifications/read")
    public ApiResponse<Void> markNotificationsRead(@CurrentUser User user) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        notificationService.markAllRead(user);
        return ApiResponse.success("已全部标为已读", null);
    }

    /**
     * 改自己的密码.
     *
     * <p><b>响应里回的是一张新 token, 不是一句「修改成功」。</b> 改密会让改密之前签发的
     * token 全部作废({@code JwtAuthFilter.isStaleAfterPasswordChange}), 而当前这台设备
     * 手上那张正是其中之一 —— 只回一句成功, 用户改完密码立刻被登出, 那看起来就是个
     * bug。回一张新的, 当前会话无缝续上, 别处的旧 token 照常失效。前端要把 data.token
     * 存回去, 不存就等于自己把自己登出了。
     *
     * <p>它**不在** SecurityConfig 的公开清单里(与 {@link #getNotifications} 同一条
     * 理由: 要改的是「我」的密码, 必须知道我是谁), 下面判空是兜底不是主防线。
     */
    @PutMapping("/password")
    public ApiResponse<Map<String, Object>> changePassword(@CurrentUser User user,
                                                          @Valid @RequestBody ChangePasswordRequest req) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return ApiResponse.success("密码修改成功", userService.changePassword(user, req));
    }

    /** 获取当前登录用户信息 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> getCurrentUser(@CurrentUser User user) {
        if (user == null) {
            return ApiResponse.error("未登录");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("role", user.getRole());
        data.put("status", user.getStatus());
        data.put("createdAt", user.getCreatedAt());
        return ApiResponse.success(data);
    }

    /**
     * 上传/更换自己的头像。
     *
     * <p><b>路径里没有用户 id, 也不该有</b> —— 换的是"我的"头像, 那个"我"只能从登录态
     * 推出来。带 id 的版本会立刻多出一个要单独审的越权面(把别人的 id 填进来会怎样),
     * 而它换不来任何东西。
     *
     * <p>校验与落库都在 {@link AvatarService#upload} 里, 四道关卡的顺序与理由写在那里。
     * 这个类只负责"它挂在哪个地址上"与把失败映射成状态码。
     *
     * <p>响应里回的是**新的头像地址**(带 {@code ?v=} 版本号), 前端直接把它填进
     * {@code <img src>} 就能立刻看到效果, 不必等下一次 {@code /api/user/me}。
     */
    @PostMapping("/avatar")
    public ApiResponse<Map<String, Object>> uploadAvatar(@CurrentUser User user,
                                                         @RequestParam("file") MultipartFile file) {
        // 走不到这里: 本路径不在 SecurityConfig 的免登录名单里, 匿名请求在过滤器链上
        // 就被拦成 401。下面判空是兜底(与 getUserInfo 同一个理由)。
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        return ApiResponse.success("头像已更新", avatarService.upload(user, file));
    }

    /**
     * 删除自己的头像, 退回默认(首字母/图标)。
     *
     * <p>幂等: 本来就没有头像时调它, 结果与调用前一致, 不是错误 —— 这个端点的心智是
     * "把我变回默认", 而不是"删掉一条存在的记录"。
     */
    @DeleteMapping("/avatar")
    public ApiResponse<Void> deleteAvatar(@CurrentUser User user) {
        if (user == null) {
            throw BusinessException.unauthorized("未登录");
        }
        avatarService.delete(user);
        return ApiResponse.success("头像已删除", null);
    }

    /**
     * 取某个用户的头像图片。**免登录**, 见 {@code SecurityConfig} 里带
     * {@code HttpMethod.GET} 的那一组白名单。
     *
     * <p>为什么要免登录: 评论区、回复列表、通知列表都要显示头像, 而这些地方未登录访客
     * 本来就看得见。漏配的症状与那组白名单注释里写的一模一样 ——
     * <b>未登录访客看得见评论、却看不见评论者的头像</b>, 那不是权限设计, 是漏配。
     *
     * <p>回的是**字节**而不是一个重定向或一段 base64: 浏览器直接把它当图片渲染,
     * 不经过 JS, 也就没有"一屏 20 个头像要解 20 段 base64"这种开销。
     *
     * <p><b>ETag 与 Cache-Control 是这一条的性能所在。</b> 头像在每一页里可能出现几十次,
     * 而它几乎从不变 —— 没有缓存头, 每次翻页都要重下一遍。ETag 由上传时刻算出来
     * (见 {@link AvatarService#etagOf}), 于是"变了没变"由它回答, {@code max-age}
     * 只是把"没变"这段时间的往返也省掉。用户换头像时 URL 上的 {@code ?v=} 会变,
     * 所以长 {@code max-age} 不会让人看到旧图。
     *
     * <p>没有头像就 404: 前端在 {@code avatar} 为 null 时本来就显示兜底图标、根本不发
     * 这个请求, 所以 404 只会在手敲地址时出现, 它是最诚实的回答。
     */
    @GetMapping("/{userId}/avatar")
    public ResponseEntity<byte[]> getAvatar(@PathVariable Long userId) {
        return avatarService.find(userId)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                        .eTag(AvatarService.etagOf(userId, image.updatedAt()))
                        .body(image.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

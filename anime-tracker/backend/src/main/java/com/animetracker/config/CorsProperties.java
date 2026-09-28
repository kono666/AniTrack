package com.animetracker.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域访问白名单.
 *
 * 这个项目默认不需要 CORS, 所以白名单默认是空的:
 *   - 开发环境: Vite 把 /api 代理给后端, 浏览器只跟 5173 说话
 *   - 生产环境: nginx 把 /api 反代给后端, 前后端同域
 * 两种情况浏览器都不跨域, 也就永远不会走到这里.
 *
 * 保留这个配置项是为了应对「前端确实部署在另一个域名下」的情况.
 * 注意那时光配后端还不够: 前端目前的 API 地址是写死的相对路径 /api,
 * 必须先把它改成可配置的绝对地址, 跨域才可能真正跑通.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private static final Logger log = LoggerFactory.getLogger(CorsProperties.class);

    /**
     * 允许跨域的前端来源, 逗号分隔 (Spring 会自动切分并去掉空白).
     * 留空表示不允许任何跨域请求.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 是否配置了白名单 */
    public boolean isEnabled() {
        return !originList().isEmpty();
    }

    /**
     * 白名单列表, 已过滤空项.
     *
     * 会顺手把结尾的 "/" 去掉并去重: 浏览器 Origin 头是 "http://host:port" 的形式,
     * 从不带尾斜杠, 而 originList() 除了喂给 Spring, 也会打进启动日志、参与比对.
     * 统一形态是为了避免「日志里看到的值和实际生效的值不完全一样」这种
     * 需要在两个地方来回核对才能发现的小坑.
     */
    public List<String> originList() {
        if (allowedOrigins == null) {
            return List.of();
        }
        return allowedOrigins.stream()
                .filter(o -> o != null && !o.isBlank())
                .map(String::trim)
                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o)
                .distinct()
                .toList();
    }

    /**
     * 是否配置了通配来源.
     *
     * 通配等于把这道防线整个撤掉 —— 任何网站都能跨域调用本 API.
     * 不直接拒绝启动, 是因为它可能是某次紧急排查时的临时手段;
     * 但会打一条警告, 免得它被当成一次普通配置悄悄留下.
     */
    public boolean allowsAnyOrigin() {
        return originList().stream().anyMatch("*"::equals);
    }

    /**
     * 启动时检查白名单写法.
     *
     * 这里的错误全都不会抛异常, 只会让跨域静默失效, 所以必须在启动日志里说清楚.
     */
    @PostConstruct
    void validate() {
        for (String origin : originList()) {
            if ("*".equals(origin)) {
                continue;
            }
            if (!origin.startsWith("http://") && !origin.startsWith("https://")) {
                log.warn("CORS 白名单项 '{}' 缺少协议头 (http:// 或 https://), 它不会匹配任何请求, 请检查配置", origin);
            } else if (origin.indexOf('/', "https://".length()) >= 0) {
                // 形如 http://host/path —— Origin 头只到端口, 不含路径, 带路径同样匹配不上
                log.warn("CORS 白名单项 '{}' 带有路径, 但浏览器的 Origin 头只包含 协议+域名+端口, 该项不会匹配任何请求", origin);
            }
        }
    }
}

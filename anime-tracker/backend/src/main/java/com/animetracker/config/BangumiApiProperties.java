package com.animetracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bangumi API 的连接参数.
 *
 * <p>这里原本还有四个 {@code *-cache-hours}(subject / episode / search / calendar),
 * 已经删掉: 它们被绑定进来却<b>没有任何一处读</b>, 而 {@code getCalendar} 上那句
 * "缓存2小时"也从来没实现过 —— 是一段看起来在生效的死配置. 缓存的存活时间现在统一
 * 归 {@link CacheProperties}({@code anitrack.cache.*}), 那是真的接上了 CacheManager 的.
 */
@Component
@ConfigurationProperties(prefix = "bangumi.api")
public class BangumiApiProperties {

    /** Bangumi API 基础地址 */
    private String baseUrl = "https://api.bgm.tv";

    /** User-Agent 标识 */
    private String userAgent = "AniTrack/1.0 (https://github.com)";

    // getters & setters
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}

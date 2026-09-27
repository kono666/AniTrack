package com.animetracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bangumi.api")
public class BangumiApiProperties {

    /** Bangumi API 基础地址 */
    private String baseUrl = "https://api.bgm.tv";

    /** User-Agent 标识 */
    private String userAgent = "AniTrack/1.0 (https://github.com)";

    /** 番剧详情缓存时间(小时) */
    private int subjectCacheHours = 24;

    /** 剧集列表缓存时间(小时) */
    private int episodeCacheHours = 24;

    /** 搜索/排行缓存时间(小时) */
    private int searchCacheHours = 1;

    /** 每日放送缓存时间(小时) */
    private int calendarCacheHours = 1;

    // getters & setters
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getSubjectCacheHours() { return subjectCacheHours; }
    public void setSubjectCacheHours(int subjectCacheHours) { this.subjectCacheHours = subjectCacheHours; }
    public int getEpisodeCacheHours() { return episodeCacheHours; }
    public void setEpisodeCacheHours(int episodeCacheHours) { this.episodeCacheHours = episodeCacheHours; }
    public int getSearchCacheHours() { return searchCacheHours; }
    public void setSearchCacheHours(int searchCacheHours) { this.searchCacheHours = searchCacheHours; }
    public int getCalendarCacheHours() { return calendarCacheHours; }
    public void setCalendarCacheHours(int calendarCacheHours) { this.calendarCacheHours = calendarCacheHours; }
}

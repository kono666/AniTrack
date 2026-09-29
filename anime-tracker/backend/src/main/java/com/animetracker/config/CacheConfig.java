package com.animetracker.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 缓存的实现: Caffeine, 每个缓存各自的 TTL 与容量上限(取值见 {@link CacheProperties}).
 *
 * <p><b>为什么名单是写死的四个, 而不是 {@code CaffeineCacheManager} 的按需创建.</b>
 * 后者在遇到没见过的缓存名时会**默默建一个新的** —— 于是 {@code @Cacheable} 里把
 * {@code "ranking"} 打成 {@code "ranknig"}, 不会报任何错, 只是从此多出一个谁也读不到
 * 的缓存, 而真正该被缓存的那些调用每次都打库. 这种错在监控上看不出来(服务正常、
 * 请求正常, 只是慢一点), 属于最难发现的一类.
 *
 * <p>用 {@link SimpleCacheManager} 配死名单之后, 名字对不上会在<b>第一次调用时</b>抛
 * {@code IllegalArgumentException: Cannot find cache named ...} —— 当场可见, 而不是
 * 几个月后的某次性能排查. {@code CacheConfigTest} 把这个行为钉住了(名单外的名字必须
 * 取不到).
 *
 * <p>反过来, 新增一个缓存需要的动作也就变成两处: 在 {@link CacheProperties} 加一个字段,
 * 在这里加一行 —— 而这两处就在隔壁, 不会漏掉其中之一还不知道.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(CacheProperties properties) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache("ranking", properties.getRanking()),
                cache("latest", properties.getLatest()),
                cache("tags", properties.getTags()),
                cache("calendar", properties.getCalendar())));
        return manager;
    }

    private static CaffeineCache cache(String name, CacheProperties.Spec spec) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(spec.getTtl())
                .maximumSize(spec.getMaximumSize())
                .build());
    }
}

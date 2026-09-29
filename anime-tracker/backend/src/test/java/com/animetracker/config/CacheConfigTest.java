package com.animetracker.config;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code anitrack.cache.*} 真的绑到了 Caffeine 上, 而且名单是写死的四个.
 *
 * <p><b>为什么这两件事要单独验.</b>
 *
 * <p>第一件: 属性名写错时 Spring <b>不报错</b>, 字段就静静保持代码里的默认值 ——
 * 于是"改了配置却不生效"在行为上完全看不出来. 所以下面的用例把值取得与代码默认值
 * <b>不同</b>, 绑定没生效时断言会红; 反过来, 如果断言的是默认值(6h / 200), 那么
 * 无论绑定成没成都是绿的, 等于什么都没验 —— 这是配置类测试最容易写成空转的地方
 * (同 {@code RankingPropertiesBindingTest}).
 *
 * <p>第二件: {@code CaffeineCacheManager} 默认会<b>按需创建</b>没见过的缓存名,
 * 而 {@link CacheConfig} 用的是 {@code SimpleCacheManager} + 写死的名单, 名单外的
 * 名字会返回 null, 于是 {@code @Cacheable} 里把 "ranking" 打成 "ranknig" 会在第一次
 * 调用时当场抛 {@code Cannot find cache named ...}. 这条断言把那个行为钉住 ——
 * 它一旦退化回"默默建一个新的", 症状是"该缓存的调用每次都打库", 而服务看起来一切正常.
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}: 这里要验的是
 * 一个 bean 的构造, 不该顺带把整个应用、数据库、以及会联网的启动预加载器一起拉起来.
 */
class CacheConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableCache.class);

    @Test
    @DisplayName("配了就用配的: anitrack.cache.* 一路走到 Caffeine 的 builder")
    void configuredValuesWin() {
        runner.withPropertyValues(
                        "anitrack.cache.ranking.ttl=90m",
                        "anitrack.cache.ranking.maximum-size=7")
                .run(ctx -> {
                    Cache<Object, Object> ranking = nativeCache(ctx, "ranking");

                    assertThat(ranking.policy().expireAfterWrite().orElseThrow().getExpiresAfter())
                            .as("与代码默认值 6h 不同, 才能证明是绑定来的而不是默认值")
                            .isEqualTo(Duration.ofMinutes(90));
                    assertThat(ranking.policy().eviction().orElseThrow().getMaximum())
                            .as("与代码默认值 200 不同")
                            .isEqualTo(7);
                });
    }

    @Test
    @DisplayName("一个都不配时保持默认值, 不会因为缺这段配置起不来")
    void defaultsWhenNothingConfigured() {
        runner.run(ctx -> {
            Cache<Object, Object> ranking = nativeCache(ctx, "ranking");

            assertThat(ranking.policy().expireAfterWrite().orElseThrow().getExpiresAfter())
                    .isEqualTo(Duration.ofHours(6));
            assertThat(ranking.policy().eviction().orElseThrow().getMaximum()).isEqualTo(200);
        });
    }

    /**
     * 四个缓存各有各的 TTL —— "{@code @Cacheable} 上写了名字"与"这个名字真的有配置"
     * 是两件事, 而它们对不上时没有任何症状(见类注释).
     */
    @Test
    @DisplayName("四个缓存都在, 且各自的 TTL 互不相同(不是一个全局值)")
    void eachCacheHasItsOwnTtl() {
        runner.run(ctx -> {
            CacheManager manager = ctx.getBean(CacheManager.class);

            assertThat(manager.getCacheNames())
                    .containsExactlyInAnyOrder("ranking", "latest", "tags", "calendar");
            assertThat(ttlOf(manager, "ranking")).isEqualTo(Duration.ofHours(6));
            assertThat(ttlOf(manager, "latest")).isEqualTo(Duration.ofHours(1));
            assertThat(ttlOf(manager, "tags")).isEqualTo(Duration.ofHours(24));
            assertThat(ttlOf(manager, "calendar"))
                    .as("它原来那句「缓存2小时」的注释现在是真的了")
                    .isEqualTo(Duration.ofHours(2));
        });
    }

    @Test
    @DisplayName("名单外的缓存名取不到 —— 拼错时当场可见, 而不是默默建一个新的")
    void unknownNamesAreNotCreatedOnDemand() {
        runner.run(ctx -> assertThat(ctx.getBean(CacheManager.class).getCache("ranknig"))
                .as("这一条退化的话, 真正该被缓存的调用会每次都打库, 而且没有任何症状")
                .isNull());
    }

    /** 只注册这两个, 不走组件扫描 —— 免得顺带拉起整个应用 */
    @EnableConfigurationProperties(CacheProperties.class)
    @Import(CacheConfig.class)
    static class EnableCache {
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> nativeCache(ApplicationContext ctx, String name) {
        CaffeineCache cache = (CaffeineCache) ctx.getBean(CacheManager.class).getCache(name);
        assertThat(cache).as("缓存 %s 必须在名单里", name).isNotNull();
        return (Cache<Object, Object>) cache.getNativeCache();
    }

    @SuppressWarnings("unchecked")
    private static Duration ttlOf(CacheManager manager, String name) {
        Cache<Object, Object> cache = (Cache<Object, Object>) ((CaffeineCache) manager.getCache(name))
                .getNativeCache();
        return cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter();
    }
}

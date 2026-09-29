package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 四个缓存的存活时间与容量上限.
 *
 * <p><b>为什么要有这个类.</b> {@code @EnableCaching} 一直开着, 但项目里从来没有过
 * {@code CacheManager} bean, 于是 Spring Boot 退回默认的 {@code ConcurrentMapCacheManager}:
 * <b>无界、永不失效</b>, 只靠 {@code AnimeBackfillService} 与 {@code DataRefreshService}
 * 两处 {@code allEntries=true} 清空. 两个后果:
 *
 * <ul>
 *   <li><b>键会无限长出来.</b> {@code getRanking} 的键是 {@code 'rank_' + #limit} ——
 *       Home.vue 要 200, Agent 工具要 1~20 之间的各种值, 控制器默认 20, 每个不同的
 *       limit 各占一个条目, 而它们全都不会过期.</li>
 *   <li><b>"改了配置不生效"的地方.</b> application.yml 里原有四个
 *       {@code bangumi.api.*-cache-hours}, 它们被绑进 {@code BangumiApiProperties}
 *       却<b>没有任何一处读</b>(四个 getter 零调用), {@code getCalendar} 上那句
 *       "缓存2小时"也从来没有实现过. 那些键已经删掉了 —— 留着一个读不到、却看起来
 *       在生效的配置, 比没有更坏: 下次有人调它、发现没反应, 得先花时间证明它是死的.</li>
 * </ul>
 *
 * <p><b>取值理由</b>(每一档都是产品口味, 不是推导出来的, 所以可配):
 * <ul>
 *   <li>{@code calendar} 2 小时 —— 每日放送排期一天一变, 2 小时足够新, 也与它原来那句
 *       (当时是假的)"缓存2小时"注释对齐.</li>
 *   <li>{@code latest} 1 小时 —— 首页"最近更新". 再长会让首页停在昨天.</li>
 *   <li>{@code ranking} 6 小时 —— 加权榜本来就不该频繁重排; 而且回填结束时有
 *       {@code @CacheEvict} 兜底, 真正的"数据变了"不需要靠 TTL 发现.</li>
 *   <li>{@code tags} 24 小时 —— 标签只在回填时变, TTL 在这里纯粹是最后一道兜底.</li>
 * </ul>
 *
 * <p>{@code maximum-size} 不是"预计有多少个键"的估计, 而是"键写错时最多能占多少内存"
 * 的上界 —— 有了它, 一个拼错的键名最多浪费这么多条, 而不是一直涨到 OOM.
 *
 * <p>与 {@link RankingProperties} 同一个写法与同一个理由: 取值归 application.yml,
 * 规则与理由归代码.
 */
@Data
@Component
@ConfigurationProperties(prefix = "anitrack.cache")
public class CacheProperties {

    private Spec ranking = Spec.of(Duration.ofHours(6), 200);

    private Spec latest = Spec.of(Duration.ofHours(1), 200);

    private Spec tags = Spec.of(Duration.ofHours(24), 50);

    private Spec calendar = Spec.of(Duration.ofHours(2), 50);

    /** 单个缓存的规格. 两个字段都有默认值, 所以 yml 里只写其中一个也成立 */
    @Data
    public static class Spec {

        /** 写入后多久失效. 用 {@link Duration} 而不是 int 小时数, 好让 yml 里写 30m 也成立 */
        private Duration ttl = Duration.ofHours(1);

        /** 最多留多少个键 */
        private long maximumSize = 100;

        static Spec of(Duration ttl, long maximumSize) {
            Spec spec = new Spec();
            spec.ttl = ttl;
            spec.maximumSize = maximumSize;
            return spec;
        }
    }
}

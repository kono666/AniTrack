package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 封面图代理的三个数: 单张上限、缓存存活时间、缓存总字节上限.
 *
 * <p>与 {@link CacheProperties} 同一个理由 —— 取值归 application.yml, 规则与理由归代码 ——
 * 但**刻意不挂在 {@code anitrack.cache} 下面**, 也不住进 {@link CacheConfig}:
 * 那是四个**接口级**缓存的名单({@code ranking}/{@code latest}/{@code tags}/{@code calendar}),
 * 由 {@code SimpleCacheManager} 按名分发, 并且有一条"名单外的名字必须取不到"的断言钉着
 * ({@code CacheConfigTest})。图片缓存是另一回事: 它按**字节**而不是按条目淘汰, 值是
 * {@code byte[]} 而不是可序列化的返回值, 而且只有这一个调用方 —— 塞进那个名单既会让
 * 那条断言失效(等于改既有守卫), 也要为"为什么只有它用 maximumWeight"另写一段解释。
 * 各走各的, 两条规则都更短。
 *
 * <p><b>{@code maxBytes} 与 {@code maximumWeightBytes} 不是一回事, 别合并:</b>
 * 前者是**单张**图片允许多大(超过就拒, 见 {@code CoverImageService}), 后者是**整个缓存**
 * 允许多大。单张 5MB × 最多 64MB, 意味着缓存里最多同时躺着十几张 —— 这个数量对
 * "首页一屏 20 张封面"是够的, 而它同时给了一个确定的**内存上界**: 缓存再怎么涨也不会
 * 超过 64MB(加上少量对象头)。这正是用 {@code maximumWeight} 而不是 {@code maximumSize}
 * 的原因 —— 按条目数封顶时, "200 条"既可能是 2MB 也可能是 1GB, 那不构成上界。
 *
 * <p><b>为什么用 {@link DataSize} 而不是 long:</b> yml 里就能写 {@code 5MB} / {@code 64MB}。
 * 写成裸数字也能绑(单位是字节), 但一个 5242880 摆在配置里没人读得出来是多大 ——
 * 而这个文件存在的意义就是让人读。
 */
@Data
@Component
@ConfigurationProperties(prefix = "anitrack.image-proxy")
public class ImageProxyProperties {

    /**
     * 单张图片的字节上限.
     *
     * <p>取值 5MB 的依据: Bangumi 的封面是 {@code /pic/cover/l/…} 这个"large"档,
     * 实测普通条目在 20~200KB, 大图(bgm 的 cover/l 最大边长约 1000px)也在 1MB 以内。
     * 5MB 是"正常图片的两三个数量级之上, 又远小于一次 OOM"的位置 —— 它的作用不是
     * 拦住正常图片, 而是让一个畸形的(或恶意的)上游响应在读满内存之前就被掐断。
     */
    private DataSize maxBytes = DataSize.ofMegabytes(5);

    /**
     * 缓存条目的存活时间.
     *
     * <p>24 小时: 封面图几乎不变(换封面就是换 URL), 而它的价值全在"别每次都穿到上游"。
     * 比 {@code tags}(24h)对齐、比 {@code calendar}(2h)长, 是因为这里没有"数据变了要
     * 尽快反映"的压力 —— 上游真换了图, 最坏情况是这张封面多显示一天旧图, 而这一点
     * 连人工验收都很难察觉。
     */
    private Duration ttl = Duration.ofHours(24);

    /**
     * 缓存里所有图片加起来的字节上限.
     *
     * <p>64MB 是个**容器内存**上的取舍, 不是测算出来的: 单张上限 5MB 时它最多装十几张,
     * 对一屏 20 张封面而言命中率一般; 调大到 256MB 明显更划算(命中率会好很多),
     * 代价是给 JVM 堆留的余量少一块 —— 而这个项目最重的部分(AI 对话与全量回填)本来
     * 就吃内存。选 64MB 是先给那些让路。
     */
    private DataSize maximumWeightBytes = DataSize.ofMegabytes(64);
}

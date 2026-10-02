package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code anitrack.image-proxy.*} 真的绑到了 {@link ImageProxyProperties} 上.
 *
 * <p>理由与 {@code RankingPropertiesBindingTest} 逐字相同, 不再重复: 属性名写错时 Spring
 * 不报错, 字段静静保持默认值, 于是"改了配置却不生效"在功能上完全看不出来。所以这里的
 * 配置值**故意与代码默认值不同** —— 绑定没生效时断言会红; 若断言的是默认值, 那么绑没绑上
 * 都是绿的, 等于什么都没验。
 *
 * <p>这个类比它多验一件事: 三个字段的类型都不是 {@code long}/{@code String} 而是
 * {@link DataSize} 与 {@link Duration}。这两类的绑定靠 Spring Boot 的转换器, 而**转换失败
 * 与绑定失败长得不一样** —— 写 {@code max-bytes: 5} 会绑成 5 **字节**(不报错, 静默地把
 * 上限压到 5 字节, 于是所有图都"过大"), 写 {@code ttl: 24} 同理会被当成 24 纳秒。所以
 * 下面有一条专门拿带单位的写法去绑, 并且断言的是**字节数与秒数**, 不是原字符串 ——
 * 转成 `DataSize` 之后再比字符串是比不出来的。
 */
class ImageProxyPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableImageProxy.class);

    @Test
    @DisplayName("配了就用配的: anitrack.image-proxy.* 绑定生效(含单位换算)")
    void configuredValuesWin() {
        runner.withPropertyValues(
                        "anitrack.image-proxy.max-bytes=1234KB",
                        "anitrack.image-proxy.ttl=90m",
                        "anitrack.image-proxy.maximum-weight-bytes=7MB")
                .run(ctx -> {
                    ImageProxyProperties props = ctx.getBean(ImageProxyProperties.class);
                    assertThat(props.getMaxBytes().toBytes())
                            .as("与代码默认值 5MB 不同, 才能证明是绑定来的而不是默认值")
                            .isEqualTo(1234L * 1024);
                    assertThat(props.getTtl())
                            .as("与代码默认值 24h 不同; 90m 是 5400 秒")
                            .isEqualTo(Duration.ofSeconds(5400));
                    assertThat(props.getMaximumWeightBytes().toBytes())
                            .as("与代码默认值 64MB 不同")
                            .isEqualTo(7L * 1024 * 1024);
                });
    }

    @Test
    @DisplayName("一个都不配时保持默认值, 不会因为缺这段配置起不来")
    void defaultsWhenNothingConfigured() {
        runner.run(ctx -> {
            ImageProxyProperties props = ctx.getBean(ImageProxyProperties.class);
            assertThat(props.getMaxBytes()).isEqualTo(DataSize.ofMegabytes(5));
            assertThat(props.getTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(props.getMaximumWeightBytes()).isEqualTo(DataSize.ofMegabytes(64));
        });
    }

    /**
     * 单张上限必须小于缓存总上限 —— 否则 {@code maximumWeightBytes} 永远先到, 单张上限
     * 那条检查就成了死代码(它本来也不会报错, 只是永远不触发)。
     *
     * <p>这两个数是分别可配的, 于是"配反了"是这次改动唯一能靠配置造出来的坏状态:
     * 表现是缓存里一张图都存不下(Caffeine 对超过 maximumWeight 的条目直接丢弃), 于是
     * 每张图都穿到上游 —— 功能看着正常, 缓存静默失效, 而这恰好是这次改动最想避免的事。
     * 这条断言不验绑定, 它验的是**默认值这组数本身是自洽的**。
     */
    @Test
    @DisplayName("默认值自洽: 单张上限 < 缓存总上限")
    void defaultSingleImageLimitIsBelowTheCacheLimit() {
        ImageProxyProperties props = new ImageProxyProperties();
        assertThat(props.getMaxBytes().toBytes())
                .as("单张上限不小于缓存总上限时, 后者会先命中, 前者成为死代码")
                .isLessThan(props.getMaximumWeightBytes().toBytes());
    }

    /** 只注册这一个配置类, 不走组件扫描 —— 免得顺带拉起整个应用 */
    @EnableConfigurationProperties(ImageProxyProperties.class)
    static class EnableImageProxy {
    }
}

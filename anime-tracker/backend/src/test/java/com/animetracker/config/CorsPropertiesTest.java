package com.animetracker.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * CORS 白名单配置解析测试.
 *
 * 这里钉住的都是「配错了不报错、只是静默失效」的情况 ——
 * 跨域这种事一旦失效, 现象是浏览器控制台一行红字, 后端日志干干净净,
 * 排查成本极高, 所以值得用测试把解析行为固定下来.
 */
class CorsPropertiesTest {

    private static CorsProperties of(String... origins) {
        CorsProperties props = new CorsProperties();
        props.setAllowedOrigins(new ArrayList<>(Arrays.asList(origins)));
        return props;
    }

    // ========== 默认关闭 ==========

    @Test
    void shouldBeDisabledWhenListIsEmpty() {
        assertThat(of().isEnabled()).isFalse();
        assertThat(of().originList()).isEmpty();
        assertThat(of().allowsAnyOrigin()).isFalse();
    }

    @Test
    void shouldBeDisabledWhenAllowedOriginsIsNull() {
        CorsProperties props = new CorsProperties();
        props.setAllowedOrigins(null);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.originList()).isEmpty();
    }

    /** 只写了逗号、或者复制配置时留了个空行 —— 不该被当成「配置了白名单」 */
    @Test
    void shouldBeDisabledWhenOnlyBlankEntries() {
        CorsProperties props = of("", "   ", "\t");

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.originList()).isEmpty();
    }

    // ========== 正常解析 ==========

    @Test
    void shouldTrimAndKeepOrder() {
        CorsProperties props = of("  http://localhost:5173 ", "https://a.example.com");

        assertThat(props.originList())
                .containsExactly("http://localhost:5173", "https://a.example.com");
    }

    /**
     * 尾斜杠必须被去掉.
     *
     * 浏览器的 Origin 头是 "http://host:port", 从不带尾斜杠. 配置里多敲一个
     * 斜杠是很自然的动作, 而 originList() 的结果除了喂给 Spring, 也会出现在
     * 启动日志里 —— 统一形态, 免得「日志显示的和实际生效的不完全一样」.
     */
    @Test
    void shouldStripTrailingSlash() {
        CorsProperties props = of("http://localhost:5173/", "https://a.example.com/");

        assertThat(props.originList())
                .containsExactly("http://localhost:5173", "https://a.example.com");
    }

    @Test
    void shouldDeduplicateAfterNormalization() {
        CorsProperties props = of("http://a.example.com", "http://a.example.com/", " http://a.example.com ");

        assertThat(props.originList()).containsExactly("http://a.example.com");
    }

    // ========== 通配 ==========

    @Test
    void shouldDetectWildcard() {
        assertThat(of("*").allowsAnyOrigin()).isTrue();
        assertThat(of("http://a.example.com", "*").allowsAnyOrigin()).isTrue();
        assertThat(of("http://a.example.com").allowsAnyOrigin()).isFalse();
    }

    // ========== 启动期校验 ==========

    /** 写法有问题的项只打警告, 不抛异常 —— 启动失败的门槛留给真正的安全问题 */
    @Test
    void validateShouldNeverThrow() {
        assertThatCode(() -> of("localhost:5173").validate()).doesNotThrowAnyException();
        assertThatCode(() -> of("http://a.example.com/path").validate()).doesNotThrowAnyException();
        assertThatCode(() -> of("*").validate()).doesNotThrowAnyException();
    }
}

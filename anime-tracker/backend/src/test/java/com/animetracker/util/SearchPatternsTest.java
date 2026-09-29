package com.animetracker.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIKE 模式串的拼装.
 *
 * <p>这一组盯的是「用户输入的 % 和 _ 被当成通配符」那件事. 它不报错, 只是结果悄悄变多:
 * 搜一个 {@code %} 命中全表, 搜 {@code a_c} 连 {@code abc} 一起给. 因为返回值永远"看起来
 * 有结果", 靠手工点是发现不了的, 只能靠断言把模式串本身钉死.
 *
 * <p>纯函数, 不需要数据库 —— 真正的「转义在 SQL 里生效」由
 * {@code AnimeSearchQueryIntegrationTest} 在真库上验.
 */
class SearchPatternsTest {

    @Test
    @DisplayName("普通关键词: 两边补 %, 中间原样")
    void wrapsWithWildcards() {
        assertThat(SearchPatterns.contains("EVA")).isEqualTo("%EVA%");
        assertThat(SearchPatterns.contains("进击的巨人")).isEqualTo("%进击的巨人%");
    }

    @Test
    @DisplayName("% 与 _ 被转义成字面量 —— 这是这个类的全部意义")
    void escapesLikeWildcards() {
        assertThat(SearchPatterns.contains("100%")).isEqualTo("%100!%%");
        assertThat(SearchPatterns.contains("a_b")).isEqualTo("%a!_b%");
        assertThat(SearchPatterns.contains("%_%")).isEqualTo("%!%!_!%%");
    }

    @Test
    @DisplayName("转义字符自身也要转义: 搜 a!b 匹配的是 a!b, 不是 ab")
    void escapesTheEscapeCharacterItself() {
        assertThat(SearchPatterns.contains("a!b")).isEqualTo("%a!!b%");
    }

    /**
     * 反斜杠**不**转义, 因为它已经不是转义字符了.
     *
     * <p>这条断言是防"顺手把它也转义掉"的: 显式写了 ESCAPE '!' 之后, 反斜杠在 H2 与 PG
     * 上都退回普通字符(见 SearchPatterns 的类注释). 多转义一道反而会让搜 {@code a\b}
     * 匹配不到 {@code a\b} —— 变成另一种静默错.
     */
    @Test
    @DisplayName("反斜杠是普通字符, 不转义")
    void backslashIsOrdinary() {
        assertThat(SearchPatterns.contains("a\\b")).isEqualTo("%a\\b%");
    }

    @Test
    @DisplayName("空串拼成 %%（匹配全部）; null 原样返回, 由调用方保证不会走到这里")
    void handlesEmptyAndNull() {
        assertThat(SearchPatterns.contains("")).isEqualTo("%%");
        assertThat(SearchPatterns.contains(null)).isNull();
    }
}

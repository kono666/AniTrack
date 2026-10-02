package com.animetracker.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeasonRange#parse} 的边界.
 *
 * <p>纯函数, 所以四个季度的首末月、老写法的不变、认不出的值的退化全部能在这里钉死 ——
 * 而真库上那两条集成用例只需要验"这套解析真的接到了 SQL 上".
 *
 * <p><b>为什么 {@code 2024-10} 必须原样保留.</b> 它不只是"兼容旧写法"这么轻:
 * {@code anime.season} 存的就是这个形状({@link AnimeFields#seasonOf}), 而外部调用方
 * (AI 工具的 {@code season} 参数说明、README)一直按 {@code yyyy-MM} 描述这个参数.
 * 解析器一旦把它也"展开", 语义就变了.
 */
class SeasonRangeTest {

    @Test
    @DisplayName("四个季度各自展开成首末月, 且不跨年")
    void expandsEachQuarterToItsMonthBounds() {
        assertThat(SeasonRange.parse("2024-Q1")).isEqualTo(new SeasonRange("2024-01", "2024-03"));
        assertThat(SeasonRange.parse("2024-Q2")).isEqualTo(new SeasonRange("2024-04", "2024-06"));
        assertThat(SeasonRange.parse("2024-Q3")).isEqualTo(new SeasonRange("2024-07", "2024-09"));
        // Q4 的末月是 12 而不是次年的 01: 这一列按字符串比大小, 写成 2025-01 会让
        // ">= 2024-10 且 <= 2025-01" 顺带把 2025 年 1 月也捞进来
        assertThat(SeasonRange.parse("2024-Q4")).isEqualTo(new SeasonRange("2024-10", "2024-12"));
    }

    @Test
    @DisplayName("月份写法退化成 from == to 的精确等值")
    void aMonthDegradesToExactEquality() {
        SeasonRange range = SeasonRange.parse("2024-10");
        assertThat(range.from()).isEqualTo("2024-10");
        assertThat(range.to()).isEqualTo("2024-10");
        assertThat(range.isSingleMonth()).isTrue();
    }

    @Test
    @DisplayName("null / 空串 / 空白都是「不限季度」, 返回 null —— 与 SQL 里那道守卫成对")
    void blankMeansNoCondition() {
        assertThat(SeasonRange.parse(null)).isNull();
        assertThat(SeasonRange.parse("")).isNull();
        assertThat(SeasonRange.parse("   ")).isNull();
    }

    /**
     * 认不出的值原样进区间 —— <b>不报错</b>.
     *
     * <p>大小写与补零刻意都不放宽: 一旦开始放宽, 下一个问题就是 {@code 2024-Q04}
     * 算不算, 而每个放宽点都是一处与前端不一致的机会. 这些值一直是"筛空"的,
     * 保持它意味着这次改动不会把任何调用方从空结果变成 400.
     */
    @Test
    @DisplayName("认不出的值: 原样退化成精确等值(于是筛空), 不抛异常")
    void unrecognizedValuesDegradeInsteadOfThrowing() {
        for (String raw : new String[]{"2024-Q5", "2024-Q0", "2024-q4", "24-Q4", "2024-Q04",
                                       "2024-Q4x", "不是季度", "2024"}) {
            SeasonRange range = SeasonRange.parse(raw);
            assertThat(range).as("%s 应该退化而不是被当成季度", raw).isNotNull();
            assertThat(range.from()).as("%s 的首端应是原样", raw).isEqualTo(raw);
            assertThat(range.to()).as("%s 的末端应是原样", raw).isEqualTo(raw);
        }
    }

    @Test
    @DisplayName("首尾空白会被去掉 —— 它只可能来自手输的 URL, 不该因此筛空")
    void trimsSurroundingWhitespace() {
        assertThat(SeasonRange.parse(" 2024-Q4 ")).isEqualTo(new SeasonRange("2024-10", "2024-12"));
    }
}

package com.animetracker.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页信封 {@code {list, total, page}}.
 *
 * <p>这三行本来是 {@code AnimeService} 里的一个私有方法, 用户管理也要分页之后才有了
 * 第二个调用方. 它值得单独一组断言, 是因为它**错得起**: {@code total} 报小了接口照样
 * 200, 只是用户看不到自己已经看到的那几行 —— 没有异常、没有日志, 靠点页面也很难
 * 分辨"这个站就这么多"和"少报了几条".
 */
class PageResultsTest {

    @Test
    @DisplayName("三个键都在, 且嵌在 data 里(与 AnimeService / ReviewService 同一形状)")
    void hasTheThreeKeys() {
        Map<String, Object> data = PageResults.of(List.of("a", "b"), 2, 1);

        assertThat(data).containsOnlyKeys("list", "total", "page");
        assertThat(data.get("list")).isEqualTo(List.of("a", "b"));
        assertThat(data.get("total")).isEqualTo(2);
        assertThat(data.get("page")).isEqualTo(1);
    }

    /**
     * 这一条是这个类存在的**唯一**理由.
     *
     * <p>count 与取页是两条查询, 中间隔着一个请求的往返. 期间有人注册的话, 这一页可能
     * 比 count 报的还长 —— 那时 {@code total} 若原样下发, 前端拿它算分页控件, 就会
     * 出现"页面上有 21 行, 但系统认为总共只有 20 条".
     */
    @Test
    @DisplayName("total 不得小于手上真实的行数")
    void totalNeverUnderReportsTheRowsInHand() {
        Map<String, Object> data = PageResults.of(List.of("a", "b", "c"), 2, 1);

        assertThat(data.get("total")).isEqualTo(3);
    }

    @Test
    @DisplayName("total 比手上多时原样保留 —— 那正是'后面还有几页'的依据")
    void totalIsKeptWhenItIsLarger() {
        Map<String, Object> data = PageResults.of(List.of("a"), 500, 2);

        assertThat(data.get("total")).isEqualTo(500);
        assertThat(data.get("page")).isEqualTo(2);
    }

    @Test
    @DisplayName("空页也带得动 total: 越界的那一页要靠它算出还有几页")
    void emptyPageStillCarriesTheTotal() {
        Map<String, Object> data = PageResults.of(List.of(), 30, 99);

        assertThat((List<?>) data.get("list")).isEmpty();
        assertThat(data.get("total")).isEqualTo(30);
    }

    /**
     * 溢出上限是 {@code Integer.MAX_VALUE}, 因为切片最终交给
     * {@code Query.setFirstResult(int)}.
     *
     * <p>值的本身没什么可测的, 钉住它是因为 {@code AnimeService} 与
     * {@code AdminService} 两处守卫都在拿它比 —— 哪天有人把它改小(比如改成
     * 100_000) "以保护数据库", 会有用户翻到第 5000 页时看到空列表, 而接口说
     * {@code total} 有十万条.
     */
    @Test
    @DisplayName("溢出上限就是 Integer.MAX_VALUE")
    void maxSqlOffsetIsTheIntLimit() {
        assertThat(PageResults.MAX_SQL_OFFSET).isEqualTo(Integer.MAX_VALUE);
    }
}

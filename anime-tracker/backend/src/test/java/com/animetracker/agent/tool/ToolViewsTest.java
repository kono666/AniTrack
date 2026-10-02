package com.animetracker.agent.tool;

import com.animetracker.entity.Anime;
import com.animetracker.util.CoverImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工具返回的番剧视图 —— 尤其是那一行封面的地址。
 *
 * <p>这个类此前一条用例都没有: {@code ToolCardsTest} 验的是"把已有的一行画成卡片",
 * 它自己往输入里塞 {@code m.put("cover", ...)}, 于是<b>产</b>这一行的代码完全在网外。
 * 这次封面开始走代理, 顺手把这半张网补上 —— {@code ToolViews} 是那 5 个调用点里
 * 唯一一个不走 service、由静态方法直接产出响应行的, 因而也是唯一一个"改错了没有
 * 任何别的东西会红"的地方。
 *
 * <p>对话内联卡片与网页走的是同一个浏览器, 封面必须从同一个入口进来。这一条不是
 * 美观问题: 卡片直连上游时, 用户的 IP 与 {@code Referer} 会一并送给上游, 而
 * 走代理的那一半不会 —— 同一屏里两种行为, 是最难被发现的那种不一致。
 */
class ToolViewsTest {

    private static final String REAL_COVER = "https://lain.bgm.tv/pic/cover/l/aa/01/42.jpg";
    private static final String LEGACY_HTTP_COVER = "http://lain.bgm.tv/pic/cover/l/aa/01/old.jpg";

    @Test
    @DisplayName("白名单内的封面: cover 换成代理地址")
    void whitelistedCoverGoesThroughTheProxy() {
        Map<String, Object> row = ToolViews.animeBrief(animeWithCover(REAL_COVER));

        assertThat(row).containsEntry(ToolViews.TYPE_KEY, ToolViews.TYPE_ANIME);
        assertThat((String) row.get("cover"))
                .startsWith(CoverImages.PROXY_PATH + "?url=");
    }

    @Test
    @DisplayName("白名单外的存量地址: 一个字都不改")
    void nonWhitelistedCoverStaysUntouched() {
        Map<String, Object> row = ToolViews.animeBrief(animeWithCover(LEGACY_HTTP_COVER));

        assertThat(row).containsEntry("cover", LEGACY_HTTP_COVER);
    }

    /**
     * 详情视图是复用列表视图再补几个字段的 —— 于是封面只有一份、不可能飘开。
     *
     * <p>写下来是因为它<b>不必一直白拿</b>: 哪天详情为了省 token 自己拼一次行,
     * 就会出现"搜索卡片走代理、详情卡片直连"的分叉, 而两边各自看都对。
     */
    @Test
    @DisplayName("详情视图的 cover 与列表视图逐字相同")
    void detailInheritsTheSameCover() {
        Anime entity = animeWithCover(REAL_COVER);

        assertThat(ToolViews.animeFull(entity))
                .containsEntry("cover", ToolViews.animeBrief(entity).get("cover"));
    }

    @Test
    @DisplayName("null 番剧: 空 Map, 不抛 NPE")
    void nullAnimeIsAnEmptyMap() {
        assertThat(ToolViews.animeBrief(null)).isEmpty();
        assertThat(ToolViews.animeFull(null)).isEmpty();
    }

    /**
     * 列表里夹着 null 时, 产出的是一行**空 Map**, 而不是把它跳掉。
     *
     * <p>这一条钉住的是现状而不是理想 —— 空行确实会在列表里占一个位置。它之所以不要紧,
     * 是因为下一层 {@link ToolCards} 对"没有 id/name 的行"一律丢弃(那条由
     * {@code ToolCardsTest} 里"标记在但字段缺"那一组守着)。写在这里是为了把这条依赖
     * 摆到明面上: 哪天 {@code ToolCards} 改成"来者不拒", 症状是对话里冒出一串空卡片,
     * 而那时会红的是 {@code ToolCardsTest}, 不是这里。
     */
    @Test
    @DisplayName("列表里的 null 产出一行空 Map(由 ToolCards 那一层丢弃)")
    void nullEntriesBecomeEmptyRows() {
        List<Map<String, Object>> rows = ToolViews.animeBriefList(
                java.util.Arrays.asList(animeWithCover(REAL_COVER), null));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isNotEmpty();
        assertThat(rows.get(1)).isEmpty();
    }

    private static Anime animeWithCover(String coverUrl) {
        return Anime.builder().id(42).title("原名").titleCn("中文名").coverUrl(coverUrl).build();
    }
}

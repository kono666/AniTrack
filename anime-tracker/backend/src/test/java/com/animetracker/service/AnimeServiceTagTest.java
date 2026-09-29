package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.entity.Anime;
import com.animetracker.entity.Tag;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按标签查番剧**是怎么查的** —— 走索引还是把整张表读进内存.
 *
 * <p>为什么返回值断言不出这件事: 两种写法返回的列表一模一样, 差的全在读了哪些行.
 * 同 3.1 那两条"假修复"是同一类问题 —— 注释声称走了索引, 代码在整表 findAll,
 * 而网页上点一遍、接口测试断言一遍, 全都是绿的. 所以这里的重点不是"查到了什么",
 * 而是"没查什么": {@code findAll()} 一次都不该出现, 而
 * {@code findAnimeIdsByTagId} 必须真的被用上(它此前一直没被任何代码调用).
 *
 * <p>为什么还要留住旧路径的用例: {@code anime_tag} 为空(标签迁移还没跑)时的回退
 * 分支仍然存在, 而它现在只有这一条用例走着. 哪天要删回退, 先看到这条用例再说.
 */
class AnimeServiceTagTest {

    private static final int BASE_ID = 97000000;

    private AnimeRepository animeRepository;
    private TagRepository tagRepository;
    private AnimeTagRepository animeTagRepository;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        tagRepository = mock(TagRepository.class);
        animeTagRepository = mock(AnimeTagRepository.class);
        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                tagRepository,
                animeTagRepository,
                mock(BangumiApiClient.class),
                mock(BangumiApiProperties.class),
                new RankingProperties());
    }

    private static Anime anime(int id, String title, String date) {
        return Anime.builder().id(id).title(title).date(date).build();
    }

    private static Tag tag(long id, String name) {
        return Tag.builder().id(id).name(name).build();
    }

    // ========== 走索引, 不读整表 ==========

    /**
     * 关联表有数据时, 两条 {@code findAll()} 都不该出现.
     *
     * <p>这正是改动要修的东西: 改前是 tag 表整表 + anime_tag 表整表各读一遍,
     * 再在内存里 filter. 表里几千行时, 每次按标签查番剧都要搬几千个对象.
     */
    @Test
    @DisplayName("按标签查: 不再对 tag / anime_tag 整表 findAll")
    void neverLoadsWholeTables() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "治愈")));
        when(animeTagRepository.findAnimeIdsByTagId(7L)).thenReturn(List.of(BASE_ID, BASE_ID + 1));
        when(animeRepository.findAllById(any()))
                .thenReturn(List.of(anime(BASE_ID, "A", "2024-01-01"), anime(BASE_ID + 1, "B", "2024-02-01")));

        List<Anime> result = animeService.getByTags(Set.of("治愈"));

        assertThat(result).extracting(Anime::getId).containsExactly(BASE_ID + 1, BASE_ID);
        verify(tagRepository, never()).findAll();
        verify(animeTagRepository, never()).findAll();
        verify(animeTagRepository, times(1)).findAnimeIdsByTagId(7L);
    }

    /**
     * 一个中文标签名会带着它的英文写法一起来("百合" / "Yuri" / "Girls Love"),
     * 多个名字之间取**并集**、并且去重.
     *
     * <p>去重不是可有可无的: 同一部番在迁移和后续同步里可能分别挂了中文名和英文名,
     * 那时它会被两个标签名各命中一次 —— 不去重就会在结果里出现两遍.
     * 这里断言的是真正传给仓储的那个 id 集合, 因为返回的列表会经过 findAllById,
     * 重复与否取决于喂进去的集合.
     */
    @Test
    @DisplayName("多个标签名是并集且去重: 同一部番挂在两个名字下也只出现一次")
    void unionsTagNamesAndDeduplicatesAnimeIds() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "百合"), tag(8L, "Yuri")));
        when(animeTagRepository.findAnimeIdsByTagId(7L)).thenReturn(List.of(BASE_ID, BASE_ID + 1));
        when(animeTagRepository.findAnimeIdsByTagId(8L)).thenReturn(List.of(BASE_ID + 1, BASE_ID + 2));
        when(animeRepository.findAllById(any())).thenReturn(List.of(
                anime(BASE_ID, "A", "2024-01-01"),
                anime(BASE_ID + 1, "B", "2024-01-02"),
                anime(BASE_ID + 2, "C", "2024-01-03")));

        List<Anime> result = animeService.getByTags(new LinkedHashSet<>(List.of("百合", "Yuri")));

        assertThat(result).extracting(Anime::getId)
                .containsExactly(BASE_ID + 2, BASE_ID + 1, BASE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(animeRepository, times(1)).findAllById(captor.capture());
        assertThat(captor.getValue())
                .as("三个 id, 中间那个被两个标签名都命中, 不能变成四个")
                .containsExactly(BASE_ID, BASE_ID + 1, BASE_ID + 2);
    }

    /**
     * 关联表里有指向已删除番剧的行时, 少几条就少几条, 不报错.
     *
     * <p>{@code anime_tag} 与 anime 之间不是外键强约束, 这类悬挂行是会出现的
     * (删除番剧时只删了主表). 改动前的 findAllById + 循环里逐条 findById 同样能
     * 容忍它, 换成批量取之后也要一样.
     */
    @Test
    @DisplayName("悬挂的关联行(番剧已删): 取回来几条就是几条, 不报错")
    void toleratesDanglingAnimeTagRows() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "治愈")));
        when(animeTagRepository.findAnimeIdsByTagId(7L))
                .thenReturn(List.of(BASE_ID, BASE_ID + 99));
        when(animeRepository.findAllById(any())).thenReturn(List.of(anime(BASE_ID, "还在", "2024-01-01")));

        assertThat(animeService.getByTags(Set.of("治愈"))).hasSize(1);
    }

    // ========== 上限 ==========

    /**
     * 封顶之后留下的是**播出日最近的那几部**, 不是"随便几部".
     *
     * <p>"先截断再排序"和"先排序再截断"返回的条数一样多, 只有留下的是哪几部能区分.
     * 这里让 id 顺序与日期顺序一致再反过来排, 于是"按 id 截前 50"会留下最旧的 50.
     */
    @Test
    @DisplayName("封顶: 留下的是播出日最近的 50 部, 而不是靠前的 50 个 id")
    void capKeepsTheNewestOnes() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "日常")));
        when(animeTagRepository.findAnimeIdsByTagId(7L))
                .thenReturn(IntStream.range(0, 60).map(i -> BASE_ID + i).boxed().toList());
        when(animeRepository.findAllById(any())).thenReturn(seed(60));

        List<Anime> capped = animeService.getByTags(Set.of("日常"), AnimeService.BY_TAG_LIMIT);

        assertThat(capped).hasSize(AnimeService.BY_TAG_LIMIT);
        assertThat(capped.get(0).getId()).as("最晚播出的一部").isEqualTo(BASE_ID + 59);
        assertThat(capped).extracting(Anime::getId)
                .doesNotContain(BASE_ID, BASE_ID + 9)
                .contains(BASE_ID + 10);
    }

    @Test
    @DisplayName("不封顶的那个重载: 筛选接口要用完整集合, 它拿到之后还要按年份/季度/状态再筛")
    void uncappedOverloadReturnsEverything() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "日常")));
        when(animeTagRepository.findAnimeIdsByTagId(7L))
                .thenReturn(IntStream.range(0, 60).map(i -> BASE_ID + i).boxed().toList());
        when(animeRepository.findAllById(any())).thenReturn(seed(60));

        assertThat(animeService.getByTags(Set.of("日常"))).hasSize(60);
    }

    /** 60 部番剧, id 越大播出越晚 */
    private static List<Anime> seed(int n) {
        List<Anime> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(anime(BASE_ID + i, "番" + i, String.format("%04d-01-01", 1960 + i)));
        }
        return list;
    }

    /**
     * limit=0 是"要 0 条", 不是"不封顶".
     *
     * <p>这两种解释差得很远: 手滑传进来的 0 如果落到"不封顶"那一档,
     * 这个参数就从上限变成了没有上限.
     */
    @Test
    @DisplayName("limit=0 或负数: 返回空, 且不去查库")
    void nonPositiveLimitMeansNothing() {
        assertThat(animeService.getByTags(Set.of("日常"), 0)).isEmpty();
        assertThat(animeService.getByTags(Set.of("日常"), -1)).isEmpty();

        verifyNoInteractions(animeTagRepository, tagRepository, animeRepository);
    }

    // ========== 空输入 ==========

    @Test
    @DisplayName("标签集合为空: 一次库都不查")
    void emptyTagNamesTouchNothing() {
        assertThat(animeService.getByTags(Set.of())).isEmpty();

        verifyNoInteractions(animeTagRepository, tagRepository, animeRepository);
    }

    /**
     * 库里没有这个标签名: 到此为止, 不该再按空 id 集合去查番剧.
     *
     * <p>空集合的批量查询在 H2 上是语法错误(空 IN 列表), 这条同时挡着那个.
     */
    @Test
    @DisplayName("标签名一个都没匹配上: 不查番剧表")
    void unknownTagNameStopsEarly() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of());

        assertThat(animeService.getByTags(Set.of("没有这个标签"))).isEmpty();

        verify(animeRepository, never()).findAllById(any());
    }

    /** 标签行在、但一个番剧都没挂: 同样不该拿空集合去查番剧表 */
    @Test
    @DisplayName("标签下没有番剧: 不查番剧表")
    void tagWithoutAnimeStopsEarly() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "冷门")));
        when(animeTagRepository.findAnimeIdsByTagId(7L)).thenReturn(List.of());

        assertThat(animeService.getByTags(Set.of("冷门"))).isEmpty();

        verify(animeRepository, never()).findAllById(any());
    }

    // ========== 迁移未完成时的回退 ==========

    /**
     * {@code anime_tag} 为空(标签迁移还没跑)时, 回到读 {@code anime.tags} 那一列.
     *
     * <p>这条路上的番剧是"逐条看自己的 tags 字符串里有没有这个名字", 所以
     * 「逗号分隔 + 前后空格」这些格式细节都在这里: 旧数据里写的是
     * {@code "百合, 日常"}, 带空格的那一段也要能匹配上.
     */
    @Test
    @DisplayName("关联表为空: 回退到旧 tags 列, 按逗号切分且忽略空格")
    void fallsBackToTheLegacyTagsColumn() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(false);
        when(animeRepository.findAll()).thenReturn(List.of(
                Anime.builder().id(BASE_ID).title("两个都中").date("2024-01-01").tags("百合, 日常").build(),
                Anime.builder().id(BASE_ID + 1).title("中一个").date("2024-01-02").tags("日常").build(),
                Anime.builder().id(BASE_ID + 2).title("tags 为空").date("2024-01-03").build(),
                Anime.builder().id(BASE_ID + 3).title("不相干").date("2024-01-04").tags("科幻").build()));

        List<Anime> result = animeService.getByTags(new LinkedHashSet<>(List.of("百合", "日常")));

        assertThat(result).extracting(Anime::getId)
                .containsExactly(BASE_ID + 1, BASE_ID);
        verify(animeTagRepository, never()).findAnimeIdsByTagId(any());
    }

    /** 空 tags 列那一行要能安然跳过, 不是把整条请求带崩 */
    @Test
    @DisplayName("回退路径上 tags 为 null 的行被跳过")
    void fallbackSkipsRowsWithoutTags() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(false);
        when(animeRepository.findAll()).thenReturn(List.of(
                Anime.builder().id(BASE_ID).title("无标签").date("2024-01-01").build()));

        assertThat(animeService.getByTags(Set.of("治愈"))).isEmpty();
    }

    /** 顺带钉一下排序: 缺播出日的排最后, 而不是排在最新番之前 */
    @Test
    @DisplayName("结果按播出日倒序, 没有日期的排最后")
    void sortedByDateWithUnknownLast() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "治愈")));
        when(animeTagRepository.findAnimeIdsByTagId(7L))
                .thenReturn(List.of(BASE_ID, BASE_ID + 1, BASE_ID + 2));
        when(animeRepository.findAllById(any())).thenReturn(List.of(
                anime(BASE_ID, "没日期", null),
                anime(BASE_ID + 1, "较旧", "2020-01-01"),
                anime(BASE_ID + 2, "较新", "2025-01-01")));

        assertThat(animeService.getByTags(Set.of("治愈"))).extracting(Anime::getId)
                .containsExactly(BASE_ID + 2, BASE_ID + 1, BASE_ID);
    }

    /** 编译期的小保险: 用例里用到的集合类型就是调用方传的那种 */
    @Test
    @DisplayName("接受任意 Set 实现(controller 传的是 HashSet)")
    void acceptsAnySetImplementation() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of(tag(7L, "治愈")));
        when(animeTagRepository.findAnimeIdsByTagId(7L)).thenReturn(List.of(BASE_ID));
        when(animeRepository.findAllById(any())).thenReturn(List.of(anime(BASE_ID, "A", "2024-01-01")));

        Set<String> fromController = Arrays.stream(new String[]{"治愈", "Healing"})
                .collect(Collectors.toSet());

        assertThat(animeService.getByTags(fromController)).hasSize(1);
    }
}

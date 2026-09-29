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
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按标签查番剧**是怎么查的** —— 交给仓储的那份参数对不对.
 *
 * <p>为什么返回值断言不出这件事: "把整张表读进内存再 filter"与"让数据库筛完再切一页"
 * 返回的列表可以一模一样, 差的全在读进来的行数. 用 mock 能验的是**服务层有没有把
 * 该传的东西传下去**: 该走的查询走到了没有、标签名解析出的 id 有没有一起交下去、
 * limit 有没有变成页窗口. "到底读进来几行"那一半要打真库, 见
 * {@code QueryCountIntegrationTest} 里那几条 {@code getEntityLoadCount()} 断言.
 *
 * <p><b>这一组守的东西与改动前不一样了, 值得说清楚.</b> 改前是 tag 表 + anime_tag
 * 表各一次整表 {@code findAll()} 再在内存里 filter, 所以那时的重点是两条断言:
 * "那两个 findAll 不该出现"、以及"传进 {@code findAllById} 的 id 集合去重了没有".
 * 现在筛选与切片都在 SQL 里, 去重由 {@code EXISTS} 半连接天然保证(一部番至多出一行),
 * 那两条在这里已经无事可做 —— 它们搬去了真库那边, 变成"生成的 SQL 里有没有
 * {@code anime_tag}"与"读入实体数是不是恒定".
 *
 * <p>为什么还要留住旧路径的用例: {@code anime_tag} 为空(标签迁移还没跑)时的回退
 * 分支仍然存在, 而且它是全项目**唯一**还在用 Java 比较器排序的地方
 * (见 {@code AnimeService.DATE_DESC_UNKNOWN_LAST} 的注释). 哪天要删回退, 先看到这几条.
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
                new RankingProperties(),
                new ConcurrentMapCacheManager());
    }

    private static Anime anime(int id, String title, String date) {
        return Anime.builder().id(id).title(title).date(date).build();
    }

    private static Tag tag(long id, String name) {
        return Tag.builder().id(id).name(name).build();
    }

    /** 备好"关联表有数据 + 标签名解析成这两个 id"这一段, 返回后只需再桩上番剧查询 */
    private void givenTagsResolveTo(long... tagIds) {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        List<Tag> tags = new ArrayList<>();
        String[] names = {"百合", "Yuri", "Girls Love"};
        for (int i = 0; i < tagIds.length; i++) {
            tags.add(tag(tagIds[i], names[i % names.length]));
        }
        when(tagRepository.findByNameIn(any())).thenReturn(tags);
    }

    /** 捕获标签查询收到的那个页窗口 */
    private Pageable capturePageWindow() {
        ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
        verify(animeRepository, times(1))
                .findFilteredByTagDate(isNull(), isNull(), isNull(), any(), window.capture());
        return window.getValue();
    }

    // ========== 走索引, 不读整表 ==========

    /**
     * 关联表有数据时, 两条 {@code findAll()} 都不该出现.
     *
     * <p>这正是当初要修的东西: 改前是 tag 表整表 + anime_tag 表整表各读一遍,
     * 再在内存里 filter. 表里几千行时, 每次按标签查番剧都要搬几千个对象.
     * 现在整条路只剩三条查询: 关联表空不空、标签名 → id、以及那一条带
     * {@code EXISTS} 的取页查询.
     */
    @Test
    @DisplayName("按标签查: 不再对 tag / anime_tag 整表 findAll")
    void neverLoadsWholeTables() {
        givenTagsResolveTo(7L);
        when(animeRepository.findFilteredByTagDate(any(), any(), any(), any(), any()))
                .thenReturn(List.of(anime(BASE_ID + 1, "B", "2024-02-01"),
                        anime(BASE_ID, "A", "2024-01-01")));

        List<Anime> result = animeService.getByTags(Set.of("百合"));

        assertThat(result).extracting(Anime::getId).containsExactly(BASE_ID + 1, BASE_ID);
        verify(tagRepository, never()).findAll();
        verify(animeTagRepository, never()).findAll();
        // 那条"按 id 取番剧"的老路也一并退休了: 现在是 EXISTS 半连接, 不再先取一批 id 再二次查询
        verify(animeRepository, never()).findAllById(any());
    }

    /**
     * 一个中文标签名会带着它的英文写法一起来("百合" / "Yuri" / "Girls Love"),
     * 多个名字之间取**并集** —— 而并集现在是数据库做的.
     *
     * <p>断言的是传下去的那个 id 集合: 服务层的职责到此为止, 把解析出的 id 一起
     * 交给**一条**查询. 以前这里是"逐个标签各查一次 anime_id, 再用 LinkedHashSet
     * 在内存里合并去重"; 现在合并与去重都是 {@code at.tag.id IN :tagIds} 与
     * {@code EXISTS} 的事 —— 同一部番挂两个名字也只会出一行.
     */
    @Test
    @DisplayName("多个标签名: 解析出的 id 一起交给一条查询, 并集在库里做")
    void unionsTagNamesInASingleQuery() {
        givenTagsResolveTo(7L, 8L);
        when(animeRepository.findFilteredByTagDate(any(), any(), any(), any(), any()))
                .thenReturn(List.of(anime(BASE_ID, "A", "2024-01-01")));

        animeService.getByTags(new LinkedHashSet<>(List.of("百合", "Yuri")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(animeRepository, times(1))
                .findFilteredByTagDate(isNull(), isNull(), isNull(), captor.capture(), any());
        assertThat(captor.getValue())
                .as("两个名字解析出的 id 要么都进去, 要么并集就少一半")
                .containsExactly(7L, 8L);
    }

    // ========== 上限 ==========

    /**
     * 封顶现在是**页窗口**的一部分, 不再是"读全部再在内存里截".
     *
     * <p>这与改动前是本批次的重点差别: 以前 {@code BY_TAG_LIMIT} 只省下响应体和映射
     * 开销, 该标签下的番剧行仍然全部要读出来才能排序; 现在读的行数也封在这一页上.
     * 页窗口传错(比如忘了传、恒传 {@code unpaged})时返回的条数会变成"全部",
     * 而排序仍然是对的 —— 所以只能靠这个参数捕获来守.
     */
    @Test
    @DisplayName("封顶: limit 变成从第 0 行开始的 limit 行窗口")
    void capBecomesAPageWindow() {
        givenTagsResolveTo(7L);
        when(animeRepository.findFilteredByTagDate(any(), any(), any(), any(), any()))
                .thenReturn(seed(AnimeService.BY_TAG_LIMIT));

        List<Anime> capped = animeService.getByTags(Set.of("日常"), AnimeService.BY_TAG_LIMIT);

        assertThat(capped).hasSize(AnimeService.BY_TAG_LIMIT);
        Pageable window = capturePageWindow();
        assertThat(window.getOffset()).as("按标签浏览没有页码, 永远第一页").isZero();
        assertThat(window.getPageSize()).isEqualTo(AnimeService.BY_TAG_LIMIT);
        assertThat(window.isUnpaged()).as("说了封顶就不该是「不分页」").isFalse();
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
     * 不封顶的那个重载必须**真的不封顶**.
     *
     * <p>{@code getFiltered} 的标签那一半要用它: 拿到之后还要按年份/季度/状态再筛,
     * 在这里截断会让"符合条件"的行凭空消失 —— 那种错不会有任何报错, 只是结果少几条.
     */
    @Test
    @DisplayName("不封顶的那个重载: 明确地说「不分页」, 而不是传一个大数")
    void uncappedOverloadAsksForEverything() {
        givenTagsResolveTo(7L);
        when(animeRepository.findFilteredByTagDate(any(), any(), any(), any(), any()))
                .thenReturn(seed(60));

        assertThat(animeService.getByTags(Set.of("日常"))).hasSize(60);
        assertThat(capturePageWindow().isUnpaged())
                .as("传个大数当「全部」的话, 数据涨过那个数就会静默少行")
                .isTrue();
    }

    /**
     * limit=0 是"要 0 条", 不是"不封顶".
     *
     * <p>这两种解释差得很远: 手滑传进来的 0 如果落到"不封顶"那一档,
     * 这个参数就从上限变成了没有上限.
     */
    @Test
    @DisplayName("limit=0 或负数: 返回空, 且一次库都不查")
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
     * 库里没有这个标签名: 到此为止, 不该再拿一个空的 id 集合去查番剧.
     *
     * <p>空 IN 列表在 H2 上是语法错误, 这条同时挡着那个 —— 空 {@code tagIds} 直接
     * 返回空结果, 查询根本不发出去.
     */
    @Test
    @DisplayName("标签名一个都没匹配上: 空结果, 且不去查番剧表")
    void unknownTagNameStopsEarly() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(true);
        when(tagRepository.findByNameIn(any())).thenReturn(List.of());

        assertThat(animeService.getByTags(Set.of("没有这个标签"))).isEmpty();

        verifyNoInteractions(animeRepository);
    }

    /**
     * 标签行在、但一个番剧都没挂: 查询照发, 库回空.
     *
     * <p>这里与改动前不同, 说清楚: 改前要先 {@code findAnimeIdsByTagId} 拿到一批
     * anime_id, 空了就能提前返回; 现在"这个标签下有没有番剧"只有发出去才知道,
     * 所以空结果由库回答. 少一次短路, 换来的是查询数不随行数增长 —— 值的.
     */
    @Test
    @DisplayName("标签下没有番剧: 查询照发, 空结果")
    void tagWithoutAnimeReturnsEmpty() {
        givenTagsResolveTo(7L);
        when(animeRepository.findFilteredByTagDate(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(animeService.getByTags(Set.of("冷门"))).isEmpty();
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
        // 回退路径上解析标签 id 的那一步不该被走到: 这条路上的标签信息只在 anime.tags 里
        verify(tagRepository, never()).findByNameIn(any());
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

    /**
     * 回退路径的排序仍然是 Java 比较器, 口径必须与 SQL 那条一致.
     *
     * <p>这是全项目唯一一处还留着 Java 版"按播出日倒序、缺日期排最后"的地方
     * (理由见 {@code AnimeService.DATE_DESC_UNKNOWN_LAST} 的注释). 真库上那条 SQL
     * 的同口径断言在 {@code QueryCountIntegrationTest} 里.
     *
     * <p>注意这几行必须自己带着标签: 回退分支是按 {@code anime.tags} 那一列筛的
     * (索引那一路的标签信息在 anime_tag 里, 这里没有), 不带标签的行在这一步就被筛掉了,
     * 压根走不到排序 —— 那种写法下这条用例会得到空列表, 而失败信息只会说"少了三行".
     */
    @Test
    @DisplayName("回退路径: 按播出日倒序, 没有日期的排最后")
    void fallbackSortsByDateWithUnknownLast() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(false);
        when(animeRepository.findAll()).thenReturn(List.of(
                tagged(BASE_ID, "没日期", null),
                tagged(BASE_ID + 1, "较旧", "2020-01-01"),
                tagged(BASE_ID + 2, "较新", "2025-01-01")));

        assertThat(animeService.getByTags(Set.of("治愈"))).extracting(Anime::getId)
                .containsExactly(BASE_ID + 2, BASE_ID + 1, BASE_ID);
    }

    /** 回退路径上的封顶是 Java 侧切的(那边没有 SQL 可以下推) */
    @Test
    @DisplayName("回退路径: limit 在内存里切, 留下的仍然是最近的那几部")
    void fallbackStillHonoursTheLimit() {
        when(animeTagRepository.existsByAnimeIdNotNull()).thenReturn(false);
        when(animeRepository.findAll()).thenReturn(seedTagged(60));

        List<Anime> capped = animeService.getByTags(Set.of("日常"), 5);

        assertThat(capped).extracting(Anime::getId)
                .containsExactly(BASE_ID + 59, BASE_ID + 58, BASE_ID + 57, BASE_ID + 56, BASE_ID + 55);
    }

    /** 带标签的一行 —— 回退分支是按 anime.tags 那一列筛的 */
    private static Anime tagged(int id, String title, String date) {
        return Anime.builder().id(id).title(title).date(date).tags("治愈").build();
    }

    /** 60 部带标签的番剧, id 越大播出越晚 */
    private static List<Anime> seedTagged(int n) {
        List<Anime> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(Anime.builder().id(BASE_ID + i).title("番" + i)
                    .date(String.format("%04d-01-01", 1960 + i)).tags("日常").build());
        }
        return list;
    }

    /** 编译期的小保险: 用例里用到的集合类型就是调用方传的那种 */
    @Test
    @DisplayName("接受任意 Set 实现(controller 传的是 HashSet)")
    void acceptsAnySetImplementation() {
        givenTagsResolveTo(7L);
        when(animeRepository.findFilteredByTagDate(any(), any(), any(), any(), any()))
                .thenReturn(List.of(anime(BASE_ID, "A", "2024-01-01")));

        Set<String> fromController = Arrays.stream(new String[]{"治愈", "Healing"})
                .collect(Collectors.toSet());

        assertThat(animeService.getByTags(fromController)).hasSize(1);
    }
}

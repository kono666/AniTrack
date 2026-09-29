package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.dto.BangumiDTO.SearchResponse;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排行榜的三个调用点有没有都走到那条加权查询上, 以及传进去的参数对不对.
 *
 * <p>排序**本身**对不对不在这里验 —— 那要打真库, 见 {@code AnimeRankingIntegrationTest}.
 * 这里用 mock 仓库, 验的是另一半: 加权查询被叫到了没有、参数的顺序有没有传反、
 * 回源补齐之后有没有换成别的路.
 *
 * <p>为什么"三个调用点"值得单独一组: 用加权序的地方是浏览模式的默认序、
 * {@code getRanking} 的首次查询、以及回源补齐后的**重查**. 前两处漏掉一处, 表现是
 * "平时对、一旦本地数据不够要回源就变回按评分原值排"—— 数据少的时候根本不复现.
 * 现在三处都收敛到 {@code rankedByWeightedScore()} 里了, 这一组是那个收敛的守卫.
 */
class AnimeRankingWiringTest {

    /** 故意用与代码默认值(200 / 7.0)都不同的数: 传反了、或者写死成默认值, 都会被看出来 */
    private static final double PRIOR_VOTES = 4321.0;
    private static final double PRIOR_SCORE = 1.75;

    private AnimeRepository animeRepository;
    private BangumiApiClient bangumiApiClient;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        bangumiApiClient = mock(BangumiApiClient.class);

        RankingProperties ranking = new RankingProperties();
        ranking.setPriorVotes(PRIOR_VOTES);
        ranking.setPriorScore(PRIOR_SCORE);

        animeService = new AnimeService(
                animeRepository,
                mock(EpisodeRepository.class),
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                bangumiApiClient,
                mock(BangumiApiProperties.class),
                ranking);
    }

    private static List<Anime> fiveRows() {
        return List.of(
                Anime.builder().id(1).title("a").build(),
                Anime.builder().id(2).title("b").build(),
                Anime.builder().id(3).title("c").build());
    }

    /** 断言这一次调用拿到的两个参数就是配置里那两个, 且**顺序没传反** */
    private void assertCalledWithConfiguredPriors(int expectedCalls) {
        ArgumentCaptor<Double> votes = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);

        verify(animeRepository, times(expectedCalls))
                .findByWeightedScoreDesc(votes.capture(), score.capture());

        assertThat(votes.getValue())
                .as("第一个参数是先验票数 m").isEqualTo(PRIOR_VOTES);
        assertThat(score.getValue())
                .as("第二个参数是先验分数 C —— 与 m 传反了会让两个量纲完全不同")
                .isEqualTo(PRIOR_SCORE);
    }

    @Test
    @DisplayName("getRanking 用的是加权查询, 参数取自配置")
    void getRankingUsesWeightedQuery() {
        when(animeRepository.findByWeightedScoreDesc(anyDouble(), anyDouble()))
                .thenReturn(fiveRows());

        animeService.getRanking(3);

        assertCalledWithConfiguredPriors(1);
    }

    @Test
    @DisplayName("无关键词浏览与 getRanking 是同一个序")
    void browseBranchUsesTheSameOrder() {
        when(animeRepository.findByWeightedScoreDesc(anyDouble(), anyDouble()))
                .thenReturn(fiveRows());

        animeService.searchAnime("", 1, 20);

        // 两处用两个不同的序的话, 同一批数据在浏览页与排行榜页会排出两个榜首,
        // 看起来就像其中一个坏了 —— 而两边各自都"没有 bug".
        assertCalledWithConfiguredPriors(1);
    }

    /**
     * 回源补齐之后那次**重查**也必须是加权序.
     *
     * <p>这一条是"只改前两处"那个漏改的专门守卫: 首次查询与重查走的是两个不同的
     * 代码位置, 只修前者的话, 表现是"库里有数据时排序正确, 一旦本地不足触发回源,
     * 排出来的榜就变回按评分原值" —— 而那正好是数据库还空着、也就是第一次上线时
     * 必然出现的状态.
     *
     * <p>远端故意返回一个**非 null 但为空**的 data: 它能让
     * {@code resp.getData() != null} 那个判断通过(从而走到重查)、又不会真的往库里
     * 写任何东西, 于是这条用例只盯着"重查走了哪条路", 不被 upsert 的细节干扰.
     */
    @Test
    @DisplayName("回源补齐之后重查的还是加权序")
    void refillRequeryUsesTheSameOrder() {
        when(animeRepository.findByWeightedScoreDesc(anyDouble(), anyDouble()))
                .thenReturn(List.of())                 // 首次: 本地一条都没有 -> 触发回源
                .thenReturn(fiveRows());               // 重查
        when(bangumiApiClient.searchSubjects(anyString(), anyInt(), anyInt()))
                .thenReturn(emptySearchResponse());

        animeService.getRanking(5);

        // 两次: 回源前一次, 补齐之后又一次. 只看到一次就说明重查绕开了这条路.
        assertCalledWithConfiguredPriors(2);
    }

    private static SearchResponse emptySearchResponse() {
        SearchResponse resp = new SearchResponse();
        resp.setTotal(0);
        resp.setData(new ArrayList<>());
        return resp;
    }
}

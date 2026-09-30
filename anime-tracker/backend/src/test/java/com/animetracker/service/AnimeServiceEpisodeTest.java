package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.dto.BangumiDTO.EpisodeDTO;
import com.animetracker.entity.Anime;
import com.animetracker.entity.Episode;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 剧集缓存的"够数才算命中".
 *
 * <p>守的是修复的第二个洞: 改动前只要本地有一行就当已经取全了 ({@code !cached.isEmpty()}).
 * 以前那行没毛病 —— 存的永远是截断的前 100 条, "有没有"和"全没全"分不出来. 一旦回源能
 * 翻页, 它就成了拦路虎: 500 集的番第一次仍然只显示改动前存下的那 100 条.
 *
 * <p>同时守两条边界: 没翻完的批**不许**落库 (落下去就等于把"取了一半"固化成"取过了"),
 * 以及回源失败时**不许**把本地已有的那批丢掉 (改动前就是这么干的).
 */
class AnimeServiceEpisodeTest {

    private AnimeRepository animeRepository;
    private EpisodeRepository episodeRepository;
    private BangumiApiClient apiClient;
    private AnimeService animeService;

    @BeforeEach
    void setUp() {
        animeRepository = mock(AnimeRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        apiClient = mock(BangumiApiClient.class);
        animeService = new AnimeService(
                animeRepository,
                episodeRepository,
                mock(TagRepository.class),
                mock(AnimeTagRepository.class),
                apiClient,
                mock(BangumiApiProperties.class),
                new RankingProperties(),
                new ConcurrentMapCacheManager("ranking", "latest", "tags", "calendar"));
    }

    @Test
    @DisplayName("完整取回: 落库 + 记下条数, 下次靠这一列命中")
    void completeFetchIsStoredAndCounted() {
        Anime anime = anime(null);
        givenAnime(anime);
        givenCached(List.of());

        when(apiClient.getEpisodes(2782)).thenReturn(fetch(500, true));

        List<Episode> got = animeService.getEpisodes(2782);

        assertThat(got).hasSize(500);
        verify(episodeRepository).saveAll(any());
        assertThat(anime.getEpisodeTotal()).isEqualTo(500);
        verify(animeRepository).save(anime);
    }

    @Test
    @DisplayName("改动前存下的截断数据(null)会重取一次 —— 老数据自愈, 用户不用做任何事")
    void truncatedLegacyCacheIsRefetched() {
        Anime anime = anime(null);                  // 老行: 那一列是 NULL
        givenAnime(anime);
        givenCached(episodes(221, 100));            // 改动前只存得下 100 条

        when(apiClient.getEpisodes(2782)).thenReturn(fetch(500, true));

        assertThat(animeService.getEpisodes(2782)).hasSize(500);
        assertThat(anime.getEpisodeTotal()).isEqualTo(500);
    }

    @Test
    @DisplayName("条数够了就不再回源")
    void completeCacheIsServedWithoutFetching() {
        Anime anime = anime(500);
        givenAnime(anime);
        givenCached(episodes(221, 500));

        List<Episode> got = animeService.getEpisodes(2782);

        assertThat(got).hasSize(500);
        verifyNoInteractions(apiClient);
    }

    @Test
    @DisplayName("没翻完: 半批可以给这次看, 但绝不落库, 也不记条数")
    void partialFetchIsServedButNotStored() {
        Anime anime = anime(null);
        givenAnime(anime);
        givenCached(List.of());

        when(apiClient.getEpisodes(2782)).thenReturn(fetch(100, false));

        assertThat(animeService.getEpisodes(2782)).hasSize(100);
        // 关键: 什么都没写. 写下去等于把"取了一半"固化成"取过了"(episodeTotal 一落,
        // 下次就命中缓存不再回源) —— 那正是这次要修的 bug 的形状.
        verify(episodeRepository, never()).saveAll(any());
        assertThat(anime.getEpisodeTotal()).isNull();
        verify(animeRepository, never()).save(any());
    }

    @Test
    @DisplayName("回源失败: 退回本地已有的那批, 不是空表")
    void fetchFailureKeepsWhatWeAlreadyHad() {
        Anime anime = anime(null);
        givenAnime(anime);
        givenCached(episodes(221, 100));

        when(apiClient.getEpisodes(2782)).thenReturn(fetch(0, false));   // 一条都没拿到

        // 改动前这里 return emptyList(), 把用户本来看得见的那几集也一并弄没了
        assertThat(animeService.getEpisodes(2782)).hasSize(100);
        verify(episodeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("完整取回却是空的: 记成 0, 并把库里那批老数据清掉, 免得库和响应各说各话")
    void completeButEmptyIsRecordedAsZero() {
        Anime anime = anime(null);
        givenAnime(anime);
        List<Episode> stale = episodes(221, 100);
        givenCached(stale);

        when(apiClient.getEpisodes(2782)).thenReturn(fetch(0, true));

        assertThat(animeService.getEpisodes(2782)).isEmpty();
        assertThat(anime.getEpisodeTotal()).isZero();
        verify(episodeRepository).deleteAll(stale);
        verify(animeRepository).save(anime);
    }

    @Test
    @DisplayName("本地一行都没有的条目: 回源失败不会写出一行 null 条数的假记录")
    void unknownSubjectStaysUntouchedWhenFetchFails() {
        givenAnime(anime(null));
        givenCached(List.of());

        when(apiClient.getEpisodes(2782)).thenReturn(fetch(0, false));

        assertThat(animeService.getEpisodes(2782)).isEmpty();
        verify(animeRepository, never()).save(any());
    }

    // ==================== 辅助 ====================

    private void givenAnime(Anime anime) {
        when(animeRepository.findById(2782)).thenReturn(Optional.of(anime));
    }

    private void givenCached(List<Episode> cached) {
        when(episodeRepository.findByAnimeOrderByEpisodeNumAsc(any())).thenReturn(cached);
    }

    private static Anime anime(Integer episodeTotal) {
        Anime a = new Anime();
        a.setId(2782);
        a.setTitle("NARUTO");
        a.setEpisodeTotal(episodeTotal);
        return a;
    }

    private static List<Episode> episodes(int from, int count) {
        return IntStream.range(from, from + count)
                .mapToObj(n -> Episode.builder().id(10_000L + n).episodeNum(n).title("ep-" + n).build())
                .collect(Collectors.toList());
    }

    private static BangumiApiClient.EpisodeFetch fetch(int count, boolean complete) {
        if (count == 0) {
            return new BangumiApiClient.EpisodeFetch(Collections.emptyList(), complete);
        }
        return new BangumiApiClient.EpisodeFetch(dtos(count), complete);
    }

    private static List<EpisodeDTO> dtos(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(n -> {
            EpisodeDTO d = new EpisodeDTO();
            d.setId(10_000L + n);
            d.setEp((double) n);
            d.setName("ep-" + n);
            return d;
        }).collect(Collectors.toList());
    }
}

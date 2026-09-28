package com.animetracker.service;

import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.EpisodeWatched;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「打勾」这个切换动作在撞上唯一约束时的去向.
 *
 * <p>这里最值得钉死的是**返回值**: 并发下插不进去的那一次, 目标状态其实已经达成了
 * (那一集确实被标成看过了), 所以必须回 true. 如果实现里图省事写成「插失败就回 false」,
 * 用户会看到「点了没反应」, 然后再点一次 —— 而第二次点又变成了取消, 状态来回翻.
 *
 * <p>末尾另有一组「统计接口怎么取数」的用例: 类型分布与最近活动过去都在循环里
 * 逐条 findById. 返回值看不出区别, 所以那几条断言的是调用了哪个方法、调了几次.
 */
class StatsServiceTest {

    private EpisodeWatchedRepository epWatchedRepo;
    private TrackingRepository trackingRepo;
    private AnimeRepository animeRepo;
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        epWatchedRepo = mock(EpisodeWatchedRepository.class);
        trackingRepo = mock(TrackingRepository.class);
        animeRepo = mock(AnimeRepository.class);
        statsService = new StatsService(trackingRepo, animeRepo,
                epWatchedRepo, mock(ReviewRepository.class), new IsolatedInsert());
        when(epWatchedRepo.saveAndFlush(any(EpisodeWatched.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user() {
        return User.builder().id(1L).username("alice").role("USER").status("ACTIVE").build();
    }

    @Test
    @DisplayName("没看过: 打上勾, 返回 true")
    void marksEpisodeWhenNotYetWatched() {
        when(epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(any(), any(), any()))
                .thenReturn(false);

        assertThat(statsService.toggleEpisode(user(), 300, 1)).isTrue();
    }

    @Test
    @DisplayName("看过: 取消打勾, 返回 false")
    void unmarksEpisodeWhenAlreadyWatched() {
        when(epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(any(), any(), any()))
                .thenReturn(true);

        assertThat(statsService.toggleEpisode(user(), 300, 1)).isFalse();
        verify(epWatchedRepo).deleteByUserAndAnimeIdAndEpisodeNum(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("并发落败: 另一个请求刚插进去, 这一侧如实回答「已看过」而不是报错")
    void reportsWatchedWhenAnotherRequestJustInsertedTheSameRow() {
        when(epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(any(), any(), any()))
                // 第一次查: 对手还没提交
                .thenReturn(false)
                // 撞了约束之后复查: 那一行在了
                .thenReturn(true);
        when(epWatchedRepo.saveAndFlush(any(EpisodeWatched.class)))
                .thenThrow(new DataIntegrityViolationException("uk_episode_watched_user_anime_episode"));

        assertThat(statsService.toggleEpisode(user(), 300, 1)).isTrue();
    }

    @Test
    @DisplayName("插入炸了但复查还是没有: 原样抛出, 别把外键之类的真问题说成「打勾成功」")
    void rethrowsWhenTheFailureWasNotTheConcurrentInsert() {
        when(epWatchedRepo.existsByUserAndAnimeIdAndEpisodeNum(any(), any(), any()))
                .thenReturn(false);
        DataIntegrityViolationException foreign =
                new DataIntegrityViolationException("fk_episode_watched_user");
        when(epWatchedRepo.saveAndFlush(any(EpisodeWatched.class))).thenThrow(foreign);

        assertThatThrownBy(() -> statsService.toggleEpisode(user(), 300, 1))
                .isSameAs(foreign);
    }

    // ========== 统计接口的取数方式 ==========

    private static AnimeTracking tracked(int subjectId, String status) {
        return AnimeTracking.builder().subjectId(subjectId).status(status).progress(0).build();
    }

    private static Anime anime(int id, String tags) {
        return Anime.builder().id(id).title("t" + id).tags(tags).build();
    }

    @Test
    @DisplayName("类型分布: 番剧一次批量取回, 不在循环里逐条 findById")
    void genreDistributionLoadsAnimeInOneBatch() {
        when(trackingRepo.findByUserOrderByUpdatedAtDesc(any())).thenReturn(List.of(
                tracked(100, "watching"), tracked(101, "watched")));
        when(animeRepo.findAllById(any())).thenReturn(List.of(
                anime(100, "科幻, 日常"), anime(101, "科幻, 音乐")));

        Map<String, Integer> genre = statsService.getGenreDistribution(user());

        assertThat(genre).containsEntry("科幻", 2)
                .containsEntry("日常", 1)
                .containsEntry("音乐", 1);

        verify(animeRepo, times(1)).findAllById(any());
        verify(animeRepo, never()).findById(any());
    }

    @Test
    @DisplayName("类型分布: 番剧在本地库查不到时跳过它, 不报错")
    void genreDistributionSkipsMissingAnime() {
        when(trackingRepo.findByUserOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of(tracked(100, "watching"), tracked(999, "watched")));
        when(animeRepo.findAllById(any())).thenReturn(List.of(anime(100, "科幻")));

        assertThat(statsService.getGenreDistribution(user())).containsExactly(Map.entry("科幻", 1));
    }

    @Test
    @DisplayName("最近活动: 取数下推到数据库(前 10 条), 番剧信息一次批量取回")
    void recentActivityPagesInTheDatabaseAndBatchesTheAnimeLookup() {
        when(trackingRepo.findByUserOrderByUpdatedAtDesc(any(), any())).thenReturn(List.of(
                tracked(100, "watching"), tracked(101, "watched")));
        when(animeRepo.findAllById(any())).thenReturn(List.of(
                anime(100, null), anime(101, null)));

        assertThat(statsService.getRecentActivity(user())).hasSize(2);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(trackingRepo).findByUserOrderByUpdatedAtDesc(any(), page.capture());
        assertThat(page.getValue().getPageSize()).as("只取前 10 条").isEqualTo(10);
        assertThat(page.getValue().getOffset()).as("从第一页开始").isZero();
        // 不带 Pageable 的那个重载一旦被走回, 就是"把全部追番读进内存再丢掉"
        verify(trackingRepo, never()).findByUserOrderByUpdatedAtDesc(any());

        verify(animeRepo, times(1)).findAllById(any());
        verify(animeRepo, never()).findById(any());
    }

    @Test
    @DisplayName("最近活动: 番剧没缓存在本地时仍然返回这条活动, 只是没有标题")
    void recentActivityKeepsEntriesWithoutATitle() {
        when(trackingRepo.findByUserOrderByUpdatedAtDesc(any(), any()))
                .thenReturn(List.of(tracked(100, "watching")));
        when(animeRepo.findAllById(any())).thenReturn(List.of());

        List<Map<String, Object>> activity = statsService.getRecentActivity(user());

        assertThat(activity).hasSize(1);
        assertThat(activity.get(0)).containsEntry("type", "tracking")
                .containsEntry("subjectId", 100)
                .doesNotContainKey("animeTitle");
    }
}

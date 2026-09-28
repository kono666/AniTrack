package com.animetracker.service;

import com.animetracker.entity.EpisodeWatched;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.EpisodeWatchedRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「打勾」这个切换动作在撞上唯一约束时的去向.
 *
 * <p>这里最值得钉死的是**返回值**: 并发下插不进去的那一次, 目标状态其实已经达成了
 * (那一集确实被标成看过了), 所以必须回 true. 如果实现里图省事写成「插失败就回 false」,
 * 用户会看到「点了没反应」, 然后再点一次 —— 而第二次点又变成了取消, 状态来回翻.
 */
class StatsServiceTest {

    private EpisodeWatchedRepository epWatchedRepo;
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        epWatchedRepo = mock(EpisodeWatchedRepository.class);
        statsService = new StatsService(mock(TrackingRepository.class), mock(AnimeRepository.class),
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
}

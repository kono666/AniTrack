package com.animetracker.service;

import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.TrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追番写入在「撞上唯一约束」前后的分支.
 *
 * <p>真库那边({@link WriteConflictIntegrationTest})验的是「重复提交只有一行」这种结果,
 * 但有一条分支它够不着, 也不该靠运气去够: 竞态窗口在「查完」和「插入」之间,
 * 只有在那一瞬间另一个请求落了库, 才会走到 catch 里. 用多线程去撞是碰运气, 用例会时绿时红,
 * 所以这一层用 mock 把冲突摆成必然, 专测 catch 之后的去向.
 *
 * <p>另一条同样够不着的分支是「插入炸了, 但重查还是没有」—— 那说明这次冲突不是
 * (user_id, subject_id) 这条唯一约束引起的(比如外键炸了). 那种情况下**必须原样抛出去**:
 * 吞掉它换来的是「接口 200, 但数据根本没存」, 比 500 更难查.
 *
 * <p>末尾还有一组追番列表的取数方式用例(批量取番剧而不是逐条查). 放在同一个类里
 * 是因为它们测的是同一个 service; 与上面几条不同的是, 那几条断言的是"结果对不对",
 * 而取数次数错了结果也照样对, 所以只能断言调了哪个方法、调了几次.
 */
class TrackServiceTest {

    private TrackingRepository trackingRepository;
    private AnimeRepository animeRepository;
    private TrackService trackService;

    @BeforeEach
    void setUp() {
        trackingRepository = mock(TrackingRepository.class);
        animeRepository = mock(AnimeRepository.class);
        // IsolatedInsert 的 @Transactional 要靠 Spring 代理才生效, 单测里直接 new 即可 ——
        // attempt 就是个直通调用. 它有没有真的开新事务, 由集成测试和实际使用来保证
        trackService = new TrackService(trackingRepository, animeRepository,
                new IsolatedInsert());
        when(trackingRepository.saveAndFlush(any(AnimeTracking.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user() {
        return User.builder().id(1L).username("alice").role("USER").status("ACTIVE").build();
    }

    private static TrackRequest req(int subjectId, String status, Integer progress) {
        TrackRequest req = new TrackRequest();
        req.setSubjectId(subjectId);
        req.setStatus(status);
        req.setProgress(progress);
        return req;
    }

    private static AnimeTracking row(Long id, int subjectId, String status) {
        return AnimeTracking.builder().id(id).subjectId(subjectId).status(status).progress(0).build();
    }

    @Test
    @DisplayName("没追过: 新建一条, 请求里的字段都落上去")
    void createsRowWhenNoneExists() {
        when(trackingRepository.findByUserAndSubjectId(any(), any())).thenReturn(Optional.empty());

        AnimeTracking saved = trackService.saveTracking(user(), req(100, "watching", 5));

        assertThat(saved.getId()).isNull();
        assertThat(saved.getSubjectId()).isEqualTo(100);
        assertThat(saved.getStatus()).isEqualTo("watching");
        assertThat(saved.getProgress()).isEqualTo(5);
    }

    @Test
    @DisplayName("追过: 改原来那条, 不再插一条新的")
    void updatesExistingRowInsteadOfInserting() {
        AnimeTracking existing = row(7L, 100, "want_to_watch");
        when(trackingRepository.findByUserAndSubjectId(any(), any()))
                .thenReturn(Optional.of(existing));

        AnimeTracking saved = trackService.saveTracking(user(), req(100, "watching", 5));

        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo("watching");
        assertThat(saved.getProgress()).isEqualTo(5);
    }

    @Test
    @DisplayName("并发落败: 插入撞了唯一约束, 就回头改对手那行, 而不是把 500 抛给用户")
    void fallsBackToUpdatingTheWinnersRowOnConflict() {
        AnimeTracking winner = row(7L, 100, "want_to_watch");
        when(trackingRepository.findByUserAndSubjectId(any(), any()))
                // 第一次查: 对手还没提交, 查不到 —— 于是走插入
                .thenReturn(Optional.empty())
                // 撞了约束之后重查: 对手那行在了
                .thenReturn(Optional.of(winner));
        when(trackingRepository.saveAndFlush(any(AnimeTracking.class)))
                .thenThrow(new DataIntegrityViolationException("uk_anime_tracking_user_subject"))
                .thenAnswer(inv -> inv.getArgument(0));

        AnimeTracking saved = trackService.saveTracking(user(), req(100, "watched", 12));

        // 落在对手那一行上(同一个 id), 而不是又插了一条
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo("watched");
        assertThat(saved.getProgress()).isEqualTo(12);
    }

    @Test
    @DisplayName("插入炸了但重查还是没有: 原样抛出, 别把真问题吞成一次「保存成功」")
    void rethrowsWhenTheConflictWasNotOurs() {
        when(trackingRepository.findByUserAndSubjectId(any(), any())).thenReturn(Optional.empty());
        DataIntegrityViolationException foreign =
                new DataIntegrityViolationException("fk_anime_tracking_user");
        when(trackingRepository.saveAndFlush(any(AnimeTracking.class))).thenThrow(foreign);

        assertThatThrownBy(() -> trackService.saveTracking(user(), req(100, "watching", 1)))
                .isSameAs(foreign);

        // 查了两次: 进方法时一次, catch 里重查一次 —— 重查是必要的, 它正是用来判断
        // 「这次冲突是不是我们这条约束引起的」. 但只写过一次: 没查出对手行, 就不能再
        // 拿一个不存在的东西去更新, 那样只是把异常换成了另一副面孔
        verify(trackingRepository, times(2)).findByUserAndSubjectId(any(), any());
        verify(trackingRepository, times(1)).saveAndFlush(any(AnimeTracking.class));
    }

    // ========== 追番列表的取数方式 ==========
    //
    // 这一段与上面几条无关, 单独说: 它盯的不是控制流而是**取数次数**.
    // 逐条查和批量查的结果完全一样, 只是慢 —— 所以任何「看返回值」的用例都发现不了它,
    // 只能直接断言调用了哪个方法、调了几次. 真实 SQL 条数由 QueryCountIntegrationTest
    // 在真库上再钉一遍.

    private static Anime anime(int id, String titleCn) {
        return Anime.builder().id(id).title("t" + id).titleCn(titleCn).build();
    }

    @Test
    @DisplayName("追番列表: 番剧信息一次批量取回, 不在循环里逐条 findById")
    void loadsAnimeInOneBatch() {
        when(trackingRepository.findByUserOrderByUpdatedAtDesc(any())).thenReturn(List.of(
                row(1L, 100, "watching"), row(2L, 101, "watched"), row(3L, 102, "watching")));
        when(animeRepository.findAllById(any())).thenReturn(List.of(
                anime(100, "番A"), anime(101, "番B"), anime(102, "番C")));

        List<Map<String, Object>> result = trackService.getUserTrackings(user());

        assertThat(result).hasSize(3);
        assertThat(result).extracting(m -> m.get("animeTitle"))
                .containsExactly("番A", "番B", "番C");

        verify(animeRepository, times(1)).findAllById(any());
        // 这一条才是防止 N+1 悄悄回来的一半: 只看 findAllById 调了一次,
        // 下一次有人"顺手"补一个 findById 进去照样能过
        verify(animeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("本地库里没有这部番时, 那一行照样返回, 只是不带标题(与改动前一致)")
    void keepsRowsWhoseAnimeIsNotCachedLocally() {
        when(trackingRepository.findByUserOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of(row(1L, 100, "watching")));
        when(animeRepository.findAllById(any())).thenReturn(List.of());

        List<Map<String, Object>> result = trackService.getUserTrackings(user());

        // 追番记录本身不能因为「番剧没缓存」就消失 —— 那会让用户的番从列表里凭空少掉
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("subjectId", 100);
        assertThat(result.get(0)).doesNotContainKey("animeTitle");
    }
}

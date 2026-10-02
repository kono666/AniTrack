package com.animetracker.service;

import com.animetracker.config.SubjectExtrasProperties;
import com.animetracker.dto.BangumiDTO.CharacterDTO;
import com.animetracker.dto.BangumiDTO.ImagesDTO;
import com.animetracker.dto.BangumiDTO.PersonDTO;
import com.animetracker.dto.BangumiDTO.RelatedSubjectDTO;
import com.animetracker.entity.SubjectCharacter;
import com.animetracker.entity.SubjectExtras;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import com.animetracker.repository.SubjectCharacterActorRepository;
import com.animetracker.repository.SubjectCharacterRepository;
import com.animetracker.repository.SubjectExtrasRepository;
import com.animetracker.repository.SubjectRelationRepository;
import com.animetracker.repository.SubjectStaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SubjectExtrasService} 的生命周期: 看本地 → (旧了)回源 → 落库 → 从库里读.
 *
 * <p>上游、落库、四张表全是替身, 所以这里量的是<b>什么时候去问上游</b>与<b>要不要落库</b>
 * —— 也就是这个类里所有"错了也不报错"的判断。真正"落库与 marker 同一个事务"那条
 * 只能在 {@code SubjectExtrasWriterTest} 里用真 H2 验(这里是 mock, 事务根本不存在)。
 *
 * <p><b>这个类里最重要的一条是"完整取回但为空"那一对</b>({@code EmptyButComplete}):
 * 判据写成"内容表里有行吗"时, 上游回 {@code []} 的条目(实测 subject 21 的 /characters
 * 就是)会<b>每次都重取</b>, 而<b>所有"有角色"的用例都是绿的</b> —— 只有这一对能打红。
 */
class SubjectExtrasServiceTest {

    private static final Integer SUBJECT = 8;

    private BangumiApiClient apiClient;
    private SubjectExtrasWriter writer;
    private SubjectExtrasRepository extrasRepository;
    private SubjectCharacterRepository characterRepository;
    private SubjectCharacterActorRepository actorRepository;
    private SubjectStaffRepository staffRepository;
    private SubjectRelationRepository relationRepository;
    private SubjectExtrasProperties props;
    private SubjectExtrasService service;

    @BeforeEach
    void setUp() {
        apiClient = mock(BangumiApiClient.class);
        writer = mock(SubjectExtrasWriter.class);
        extrasRepository = mock(SubjectExtrasRepository.class);
        characterRepository = mock(SubjectCharacterRepository.class);
        actorRepository = mock(SubjectCharacterActorRepository.class);
        staffRepository = mock(SubjectStaffRepository.class);
        relationRepository = mock(SubjectRelationRepository.class);
        props = new SubjectExtrasProperties();
        // mapper 用真的: 它是纯函数, 而且"落库收到的是哪几行"正是这里要看的东西之一
        service = new SubjectExtrasService(apiClient, new SubjectExtrasMapper(), writer,
                extrasRepository, characterRepository, actorRepository,
                staffRepository, relationRepository, props);
    }

    // ── 桩与造数 ────────────────────────────────────────

    /** 账本那一行. 传 null 表示"这一块没取过" */
    private void marker(LocalDateTime characters, LocalDateTime staff, LocalDateTime relations) {
        when(extrasRepository.findById(SUBJECT)).thenReturn(Optional.of(
                SubjectExtras.builder().subjectId(SUBJECT)
                        .charactersFetchedAt(characters)
                        .staffFetchedAt(staff)
                        .relationsFetchedAt(relations)
                        .build()));
    }

    /** 账本里连这一行都没有 —— 从没碰过这个条目 */
    private void noMarkerRow() {
        when(extrasRepository.findById(SUBJECT)).thenReturn(Optional.empty());
    }

    private static ImagesDTO image(String grid) {
        ImagesDTO dto = new ImagesDTO();
        dto.setGrid(grid);
        return dto;
    }

    private static CharacterDTO characterDto(Integer id, String name) {
        CharacterDTO dto = new CharacterDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation("主角");
        dto.setImages(image("https://lain.bgm.tv/pic/crt/g/" + id + ".jpg"));
        return dto;
    }

    private static PersonDTO personDto(Integer id, String name) {
        PersonDTO dto = new PersonDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation("监督");
        return dto;
    }

    private static RelatedSubjectDTO relatedDto(Integer id, String name) {
        RelatedSubjectDTO dto = new RelatedSubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation("续集");
        return dto;
    }

    private static SubjectCharacter characterRow(Integer characterId, String name, int sortOrder) {
        return SubjectCharacter.builder().id((long) characterId).subjectId(SUBJECT)
                .characterId(characterId).name(name).sortOrder(sortOrder).build();
    }

    private static SubjectStaff staffRow(Integer personId, String name) {
        return SubjectStaff.builder().id((long) personId).subjectId(SUBJECT)
                .personId(personId).name(name).relation("监督").sortOrder(0).build();
    }

    private static SubjectRelation relationRow(Integer relatedId, String name) {
        return SubjectRelation.builder().id((long) relatedId).subjectId(SUBJECT)
                .relatedId(relatedId).name(name).relation("续集").sortOrder(0).build();
    }

    private static <T> BangumiApiClient.SubjectCollectionFetch<T> ok(List<T> items) {
        return new BangumiApiClient.SubjectCollectionFetch<>(items, true);
    }

    private static <T> BangumiApiClient.SubjectCollectionFetch<T> incomplete(List<T> items) {
        return new BangumiApiClient.SubjectCollectionFetch<>(items, false);
    }

    // ══════════ 冷启动 ══════════

    @Nested
    @DisplayName("冷启动: 没取过")
    class ColdStart {

        /** 取回来要落库, 而且给出去的是"从库里读出来的那一份" */
        @Test
        @DisplayName("回源一次 + 落库 + 从库里读出来")
        void fetchesThenStoresThenReads() {
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of(characterDto(1, "角色"))));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(characterRow(1, "角色", 0)));

            SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> result =
                    service.getCharacters(SUBJECT);

            verify(apiClient, times(1)).getCharacters(SUBJECT);
            verify(writer, times(1)).replaceCharacters(eq(SUBJECT), any());
            assertThat(result.failed()).isFalse();
            assertThat(result.data().characters())
                    .extracting(SubjectCharacter::getCharacterId).containsExactly(1);
        }

        /**
         * <b>给出去的必须是从库里读的那一份, 不是上游的 DTO 直接转的。</b>
         *
         * <p>让"库里读出来的"与"上游给的"故意不一样 —— 这条用例才分得出两种实现。
         * 分开的意义是: 中间那层截断、选图、排序只可能有一份实现, 于是不会出现
         * "第一次打开是好的、刷新之后少了一截"这种只在第二次才显形的 bug。
         */
        @Test
        @DisplayName("响应里的数据来自库, 不是上游那一份")
        void servesWhatTheDatabaseHolds() {
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT))
                    .thenReturn(ok(List.of(characterDto(1, "上游给的名字"))));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(characterRow(1, "库里读出来的", 0)));

            SubjectExtrasService.CharacterBlock block = service.getCharacters(SUBJECT).data();

            assertThat(block.characters())
                    .singleElement()
                    .extracting(SubjectCharacter::getName)
                    .as("上游那份没经过 mapper 的价值只在于落库, 不该直接出现在响应里")
                    .isEqualTo("库里读出来的");
        }

        /** 三块各自接自己的上游方法、自己的表、自己的落库方法 —— 接错了不会有编译错误 */
        @Test
        @DisplayName("三块各自对上自己的上游方法/表/落库方法, 互不串门")
        void eachSectionIsWiredToItsOwnParts() {
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of(characterDto(1, "角色"))));
            when(apiClient.getPersons(SUBJECT)).thenReturn(ok(List.of(personDto(2, "人员"))));
            when(apiClient.getRelatedSubjects(SUBJECT)).thenReturn(ok(List.of(relatedDto(3, "续集"))));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(characterRow(1, "角色", 0)));
            when(staffRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(staffRow(2, "人员")));
            when(relationRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(relationRow(3, "续集")));

            assertThat(service.getCharacters(SUBJECT).data().characters())
                    .extracting(SubjectCharacter::getCharacterId).containsExactly(1);
            assertThat(service.getStaff(SUBJECT).data())
                    .extracting(SubjectStaff::getPersonId).containsExactly(2);
            assertThat(service.getRelations(SUBJECT).data())
                    .extracting(SubjectRelation::getRelatedId).containsExactly(3);

            verify(writer, times(1)).replaceCharacters(eq(SUBJECT), any());
            verify(writer, times(1)).replaceStaff(eq(SUBJECT), any());
            verify(writer, times(1)).replaceRelations(eq(SUBJECT), any());
            verify(apiClient, times(1)).getCharacters(SUBJECT);
            verify(apiClient, times(1)).getPersons(SUBJECT);
            verify(apiClient, times(1)).getRelatedSubjects(SUBJECT);
        }
    }

    // ══════════ marker 的新旧 ══════════

    @Nested
    @DisplayName("marker 决定要不要回源")
    class MarkerFreshness {

        /** 刚取过就不再问上游 */
        @Test
        @DisplayName("marker 是新的: 一次上游都不打")
        void freshMarkerSkipsUpstream() {
            marker(LocalDateTime.now().minusMinutes(1), null, null);
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(characterRow(1, "角色", 0)));

            service.getCharacters(SUBJECT);

            verifyNoInteractions(apiClient);
            verify(writer, never()).replaceCharacters(any(), any());
        }

        /**
         * <b>过期了要重取。</b> 没有 TTL 的话, 首播那天取回来的角色表会被永久固化
         * —— 半年后打开还是那几个人, 而没有任何地方会报错。
         */
        @Test
        @DisplayName("marker 是 25 小时前的(默认 TTL 24h): 重取一次")
        void expiredMarkerRefetches() {
            marker(LocalDateTime.now().minusHours(25), null, null);
            when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of()));

            service.getCharacters(SUBJECT);

            verify(apiClient, times(1)).getCharacters(SUBJECT);
        }

        /**
         * <b>每块的 TTL 覆盖值真的生效。</b>
         *
         * <p>两个 marker 都是 5 分钟前: 角色那一块的 TTL 被配成 1 分钟(所以该重取),
         * 人员那一块没配(默认 24h, 所以不该重取)。这种"两块对照"的写法才能在
         * {@code ttlFor} 忽略覆盖值、永远返回 {@code ttl} 时打红 —— 只测单块的话,
         * 配置写错了与写对了表现一样。
         */
        @Test
        @DisplayName("characters-ttl 被配成 1 分钟时, 5 分钟前的角色重取, 而人员不重取")
        void perSectionTtlOverridesTheDefault() {
            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
            props.setCharactersTtl(Duration.ofMinutes(1));
            marker(fiveMinutesAgo, fiveMinutesAgo, null);
            when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of()));

            service.getCharacters(SUBJECT);
            service.getStaff(SUBJECT);

            verify(apiClient, times(1)).getCharacters(SUBJECT);
            verify(apiClient, never()).getPersons(any());
        }
    }

    // ══════════ 确实为空 vs 从没取过 ══════════

    @Nested
    @DisplayName("确实为空 vs 从没取过")
    class EmptyButComplete {

        /**
         * <b>完整取回但是空的, 照样要落库(也就照样会写 marker)。</b>
         *
         * <p>"上游说这里没有角色"(实测 subject 21)是一个<b>确定的答案</b>, 值得记下来 ——
         * 否则每次打开详情页都要再问一次上游。写成用例是因为"空列表就不必写库了"
         * 是一个很自然的优化, 而它的后果是那个条目永远在重取。
         */
        @Test
        @DisplayName("完整取回但为空: 仍然落库(marker 因此被写上)")
        void completeAndEmptyStillStores() {
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenReturn(ok(List.of()));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of());

            SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> result =
                    service.getCharacters(SUBJECT);

            verify(writer, times(1)).replaceCharacters(eq(SUBJECT), any());
            assertThat(result.data().characters()).isEmpty();
            assertThat(result.failed())
                    .as("'这里确实没有'不是失败 —— 界面上那一块要静默隐藏, 而不是显示重试")
                    .isFalse();
        }

        /**
         * <b>上一条的孪生兄弟, 缺了它上一条就不成立。</b>
         *
         * <p>marker 是新的、内容表是空的 —— 如果判据写成"内容表里有没有行", 这次就会
         * 又去打一次上游。而所有"有角色"的用例在那个写法下都是绿的。
         */
        @Test
        @DisplayName("marker 是新的但内容表是空的: 一次上游都不打")
        void freshMarkerWithEmptyTableDoesNotRefetch() {
            marker(LocalDateTime.now().minusMinutes(1), null, null);
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of());

            SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> result =
                    service.getCharacters(SUBJECT);

            verifyNoInteractions(apiClient);
            assertThat(result.data().characters()).isEmpty();
            assertThat(result.failed()).isFalse();
        }
    }

    // ══════════ 半批一律不落 ══════════

    @Nested
    @DisplayName("没取全")
    class Incomplete {

        /**
         * <b>没取全就不落库。</b> 把"没取到"写成"取过了"会让一次瞬时故障固化成一个
         * 产品状态, 直到 TTL 到期 —— 这正是 {@code getCalendar} 缺 {@code unless}
         * 那个 bug 的形状, 只不过那一次固化的是两小时, 这一次是一天。
         */
        @Test
        @DisplayName("没取全 + 库里本来就没东西: 不落库, failed=true")
        void incompleteWithNothingLocalIsAFailure() {
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenReturn(incomplete(List.of()));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of());

            SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> result =
                    service.getCharacters(SUBJECT);

            verify(writer, never()).replaceCharacters(any(), any());
            assertThat(result.failed())
                    .as("库里的旧行还在, 或从没取过, 都不该把这一块当成'确实没有'")
                    .isTrue();
        }

        /**
         * 取失败但库里有上一版: 不算失败 —— 陈旧的角色表比一个错误提示有用。
         *
         * <p>这一条同时钉住了"failed 不是只看 fetch.complete()", 而是
         * {@code !complete && 读出来是空的}。
         */
        @Test
        @DisplayName("没取全但库里有旧数据: 照常给出去, failed=false")
        void incompleteWithStaleDataIsNotAFailure() {
            marker(LocalDateTime.now().minusHours(25), null, null);
            when(apiClient.getCharacters(SUBJECT)).thenReturn(incomplete(List.of()));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(characterRow(1, "上次取到的", 0)));

            SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> result =
                    service.getCharacters(SUBJECT);

            verify(writer, never()).replaceCharacters(any(), any());
            assertThat(result.data().characters())
                    .extracting(SubjectCharacter::getName).containsExactly("上次取到的");
            assertThat(result.failed()).isFalse();
        }

        /** 没取全时也不许动旧行 —— delete-then-insert 只有在同一个事务里才安全 */
        @Test
        @DisplayName("没取全时四张表一行都不动")
        void incompleteTouchesNothing() {
            noMarkerRow();
            when(apiClient.getPersons(SUBJECT)).thenReturn(incomplete(List.of()));
            when(apiClient.getRelatedSubjects(SUBJECT)).thenReturn(incomplete(List.of()));
            when(staffRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT)).thenReturn(List.of());
            when(relationRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT)).thenReturn(List.of());

            service.getStaff(SUBJECT);
            service.getRelations(SUBJECT);

            verifyNoInteractions(writer);
        }
    }

    // ══════════ 单飞 ══════════

    @Nested
    @DisplayName("同时到达的请求")
    class InFlight {

        /**
         * <b>已经有人在取这一块时不重复取, 也不阻塞。</b>
         *
         * <p>第二个请求拿库里现有的(冷启动时就是空), 且<b>不算失败</b> —— 报失败会让
         * 界面上闪一下"加载失败 · 重试", 而其实几毫秒后数据就来了。
         *
         * <p>变异检验: 删掉 {@code inFlight.add(flightKey)} 那个判断, 上游会被打两次。
         */
        @Test
        @DisplayName("两个线程同调一块: 上游只被打一次, 后到的那个不报失败")
        void secondCallerDoesNotHitUpstreamAgain() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenAnswer(invocation -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return ok(List.of(characterDto(1, "角色")));
            });
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(characterRow(1, "角色", 0)));

            AtomicReference<SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock>> first =
                    new AtomicReference<>();
            Thread flyer = new Thread(() -> first.set(service.getCharacters(SUBJECT)), "flyer");
            flyer.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("第一个线程应该已经进到上游里了").isTrue();

            SubjectExtrasService.SectionResult<SubjectExtrasService.CharacterBlock> second =
                    service.getCharacters(SUBJECT);

            assertThat(second.failed())
                    .as("别人正在取不算失败: 报失败会让界面上闪一下「加载失败 · 重试」")
                    .isFalse();
            verify(apiClient, times(1)).getCharacters(SUBJECT);

            release.countDown();
            flyer.join(5000);
            assertThat(first.get().failed()).isFalse();
        }

        /**
         * <b>在飞的键必须带块名。</b>
         *
         * <p>只按条目 id 去重的话, 同一页的三个请求同时到达时, 后到的两块会白白读一次
         * 空库(冷启动时读到的就是空) —— 页面上表现为"有时候只出来一块"。
         */
        @Test
        @DisplayName("同一部番的角色正在飞时, 人员那一块照样自己去取")
        void differentSectionsDoNotBlockEachOther() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenAnswer(invocation -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return ok(List.of());
            });
            when(apiClient.getPersons(SUBJECT)).thenReturn(ok(List.of(personDto(2, "人员"))));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT)).thenReturn(List.of());
            when(staffRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT))
                    .thenReturn(List.of(staffRow(2, "人员")));

            Thread flyer = new Thread(() -> service.getCharacters(SUBJECT), "flyer");
            flyer.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.getStaff(SUBJECT).data())
                    .extracting(SubjectStaff::getPersonId).containsExactly(2);

            verify(apiClient, times(1)).getPersons(SUBJECT);
            release.countDown();
            flyer.join(5000);
        }

        /**
         * <b>异常路径也必须把在飞的键清掉。</b>
         *
         * <p>{@code remove} 不放在 {@code finally} 里的话, 中途抛一次异常之后这一块就
         * <b>永远</b>不会再回源了 —— 此后每次请求都以为自己不是那个"在飞"的, 于是
         * 每次都直接读空库, 而日志里一条错都没有(唯一那条异常早就滚过去了)。
         */
        @Test
        @DisplayName("回源抛异常之后, 下一次调用仍然会去问上游")
        void clearsTheKeyEvenWhenFetchThrows() {
            noMarkerRow();
            when(apiClient.getCharacters(SUBJECT)).thenThrow(new IllegalStateException("上游炸了"));
            when(characterRepository.findBySubjectIdOrderBySortOrderAsc(SUBJECT)).thenReturn(List.of());

            assertThatThrownBy(() -> service.getCharacters(SUBJECT))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.getCharacters(SUBJECT))
                    .isInstanceOf(IllegalStateException.class);

            verify(apiClient, times(2))
                    .getCharacters(SUBJECT);
        }
    }
}

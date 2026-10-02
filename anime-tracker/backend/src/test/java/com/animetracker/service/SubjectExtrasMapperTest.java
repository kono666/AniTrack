package com.animetracker.service;

import com.animetracker.dto.BangumiDTO.ActorDTO;
import com.animetracker.dto.BangumiDTO.CharacterDTO;
import com.animetracker.dto.BangumiDTO.ImagesDTO;
import com.animetracker.dto.BangumiDTO.PersonDTO;
import com.animetracker.dto.BangumiDTO.RelatedSubjectDTO;
import com.animetracker.dto.response.SubjectExtrasDTO;
import com.animetracker.entity.SubjectCharacter;
import com.animetracker.entity.SubjectCharacterActor;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubjectExtrasMapper} 的纯函数部分 —— 不起 Spring, 不碰 IO.
 *
 * <p>这里钉的是两类"写错了也照样绿"的地方:
 *
 * <ol>
 *   <li><b>截断。</b> 一行超长会打死整批(整批一个事务 → 全回滚 → marker 不写 →
 *       下次再取 → 再失败), 而这条路径的可见症状只是"这块内容永远空着"。
 *       所以 500 字进去必须 200 字出来, 且<b>不许把代理对切成半个</b> ——
 *       半个代理项编码不成 UTF-8, 写库时抛的异常会走回同一条路上。</li>
 *   <li><b>选图档位。</b> 断言"有 URL"在这里是<b>没用的</b> —— 三个接口给的变体不同,
 *       而 {@code CoverImages.proxied()} 对白名单外的地址<b>原样返回</b>, 所以选错档
 *       照样给出一个非空字符串, 照样能显示。只有断言它<b>等于哪一个地址</b>
 *       (以及它是不是 {@code /api/img?url=…}) 才分得出来。</li>
 * </ol>
 */
class SubjectExtrasMapperTest {

    private final SubjectExtrasMapper mapper = new SubjectExtrasMapper();

    private static ImagesDTO images(String large, String common, String medium,
                                    String grid, String small) {
        ImagesDTO dto = new ImagesDTO();
        dto.setLarge(large);
        dto.setCommon(common);
        dto.setMedium(medium);
        dto.setGrid(grid);
        dto.setSmall(small);
        return dto;
    }

    private static CharacterDTO character(Integer id, String name, String relation, ImagesDTO imgs) {
        CharacterDTO dto = new CharacterDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation(relation);
        dto.setImages(imgs);
        return dto;
    }

    private static ActorDTO actor(Integer id, String name, ImagesDTO imgs) {
        ActorDTO dto = new ActorDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setImages(imgs);
        return dto;
    }

    private static PersonDTO person(Integer id, String name, String relation, ImagesDTO imgs) {
        PersonDTO dto = new PersonDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setRelation(relation);
        dto.setImages(imgs);
        return dto;
    }

    private static RelatedSubjectDTO related(Integer id, String name, String nameCn,
                                             String relation, ImagesDTO imgs) {
        RelatedSubjectDTO dto = new RelatedSubjectDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setNameCn(nameCn);
        dto.setRelation(relation);
        dto.setImages(imgs);
        return dto;
    }

    // ══════════ 截断 ══════════

    @Nested
    @DisplayName("截断")
    class Capping {

        /**
         * 计划里点名的那个数: 500 字进去 200 字出来。
         *
         * <p>它还带一个对照 —— 下面 {@code doesNotTrimBelowTheLimit} 证明我们不是
         * "无脑减一"。
         */
        @Test
        @DisplayName("500 字的角色名截到 200")
        void capsToTheColumnWidth() {
            assertThat(SubjectExtrasMapper.cap("字".repeat(500), SubjectExtrasMapper.NAME_MAX))
                    .hasSize(200)
                    .isEqualTo("字".repeat(200));
        }

        /** 恰好等于列宽的不许动 —— 否则"减一"那种实现也会在上面那条通过 */
        @Test
        @DisplayName("正好 200 字的原样返回, 不截")
        void doesNotTrimBelowTheLimit() {
            String exact = "字".repeat(SubjectExtrasMapper.NAME_MAX);
            assertThat(SubjectExtrasMapper.cap(exact, SubjectExtrasMapper.NAME_MAX)).isSameAs(exact);
        }

        /**
         * <b>代理对不许被切成半个。</b>
         *
         * <p>第 200 个位置正好是一个 emoji 的高位代理项, 所以正确答案是<b>退到 199</b>。
         * 直接 {@code substring(0, 200)} 会得到一个以孤立高位代理项结尾的串 ——
         * 它编码不成 UTF-8, 写库时抛异常, 于是整批回滚、这块内容永远空着。
         *
         * <p>断长度是 199 而不是"没有孤立代理项": 后者在没有守卫时也可能碰巧成立
         * (比如实现整个不做截断), 而长度把两种错法分开。
         */
        @Test
        @DisplayName("截断点上正好是代理对时退一个字符 —— 半个代理项编码不成 UTF-8")
        void neverSplitsASurrogatePair() {
            String s = "a".repeat(199) + "😀" + "tail";

            String capped = SubjectExtrasMapper.cap(s, 200);

            assertThat(capped).hasSize(199);
            assertThat(Character.isHighSurrogate(capped.charAt(capped.length() - 1)))
                    .as("结尾是孤立的高位代理项的话, 写库时会抛异常 → 整批回滚 → 这块永远空着")
                    .isFalse();
        }

        /** null 走 null: 列可空的那几列(relation/image)靠这个 */
        @Test
        @DisplayName("cap(null) 是 null")
        void capKeepsNull() {
            assertThat(SubjectExtrasMapper.cap(null, 10)).isNull();
        }

        /** 而 NOT NULL 的 name 列走 text(): null 变空串, 不是 null */
        @Test
        @DisplayName("text(null) 是空串 —— name 那一列是 NOT NULL")
        void textTurnsNullIntoEmpty() {
            assertThat(SubjectExtrasMapper.text(null)).isEmpty();
            assertThat(SubjectExtrasMapper.text("名字")).isEqualTo("名字");
        }
    }

    // ══════════ 角色 + 声优 ══════════

    @Nested
    @DisplayName("角色与声优")
    class Characters {

        /**
         * <b>角色的图片首选 grid, 不是 large。</b>
         *
         * <p>实测两者的白名单通过率相同(92.9%)而 grid 体积小得多 —— 所以"选到 large"
         * 不是错数据, 只是每次多发几倍的字节。断言具体等于哪一个地址, 而不是"非空"。
         */
        @Test
        @DisplayName("grid 与 large 都有时选 grid")
        void prefersGridOverLarge() {
            CharacterDTO dto = character(1, "角色", "主角", images(
                    "https://lain.bgm.tv/pic/crt/l/1.jpg", null, null,
                    "https://lain.bgm.tv/pic/crt/g/1.jpg", null));

            List<SubjectCharacter> rows = mapper.toCharacters(8, List.of(dto)).characters();

            assertThat(rows).singleElement()
                    .extracting(SubjectCharacter::getImage)
                    .isEqualTo("https://lain.bgm.tv/pic/crt/g/1.jpg");
        }

        /** 缺失时退到下一档, 而不是留一个空图 */
        @Test
        @DisplayName("没有 grid 时退到 large")
        void fallsBackToLarge() {
            CharacterDTO dto = character(1, "角色", null, images(
                    "https://lain.bgm.tv/pic/crt/l/1.jpg", null, null, null, null));

            assertThat(mapper.toCharacters(8, List.of(dto)).characters())
                    .singleElement()
                    .extracting(SubjectCharacter::getImage)
                    .isEqualTo("https://lain.bgm.tv/pic/crt/l/1.jpg");
        }

        /** 一档都没有就是 null(列可空), 不是空串 */
        @Test
        @DisplayName("一档都没有时是 null")
        void noImageIsNull() {
            assertThat(mapper.toCharacters(8, List.of(character(1, "角色", null, images(null, null, null, null, null))))
                    .characters())
                    .singleElement()
                    .extracting(SubjectCharacter::getImage)
                    .isNull();
        }

        /**
         * <b>声优的 sortOrder 是整个条目里的一条序列, 不是角色内部的序号。</b>
         *
         * <p>读的时候要按它还原上游给的顺序。写成"每个角色内部从 0 开始"的话,
         * 两条来自不同角色的声优会有相同的 sortOrder —— 排序变成不稳定的,
         * 而这在界面上只表现为"声优顺序偶尔会换一下", 几乎不可能被当成 bug 报上来。
         */
        @Test
        @DisplayName("声优的 sortOrder 跨角色连续编号")
        void actorSortOrderIsSubjectWide() {
            CharacterDTO first = character(1, "甲", null, null);
            first.setActors(List.of(actor(100, "声优A", null), actor(101, "声优B", null)));
            CharacterDTO second = character(2, "乙", null, null);
            second.setActors(List.of(actor(102, "声优C", null)));

            List<SubjectCharacterActor> rows =
                    mapper.toCharacters(8, List.of(first, second)).actors();

            assertThat(rows).extracting(SubjectCharacterActor::getSortOrder)
                    .containsExactly(0, 1, 2);
            assertThat(rows).extracting(SubjectCharacterActor::getCharacterId)
                    .containsExactly(1, 1, 2);
        }

        /** 实测 128 条角色里 63 条没有声优 —— 那不是异常, 只是没有这一行 */
        @Test
        @DisplayName("角色没有 actors 时照常落库, 只是没有声优行")
        void characterWithoutActorsIsFine() {
            SubjectExtrasMapper.CharacterRows rows =
                    mapper.toCharacters(8, List.of(character(1, "角色", null, null)));

            assertThat(rows.characters()).hasSize(1);
            assertThat(rows.actors()).isEmpty();
        }

        /** actors 里混进一个没有 id 的元素时跳过它, 不连累同批的其它行 */
        @Test
        @DisplayName("没有 id 的声优被跳过, 同批其余照常")
        void skipsActorsWithoutId() {
            CharacterDTO dto = character(1, "角色", null, null);
            dto.setActors(Arrays.asList(actor(100, "有id", null), actor(null, "没id", null)));

            assertThat(mapper.toCharacters(8, List.of(dto)).actors())
                    .extracting(SubjectCharacterActor::getActorId)
                    .containsExactly(100);
        }

        /**
         * 没有 id 的角色整行跳过。
         *
         * <p>{@code character_id} 是这张表全部的意义 —— 落一个 null 下去既没法跳转
         * 也没法关联声优。而它在列表里出现一次就够让整批插入失败(列是 NOT NULL)。
         */
        @Test
        @DisplayName("没有 id 的角色被跳过, 别的角色不受影响")
        void skipsCharactersWithoutId() {
            List<CharacterDTO> src = new ArrayList<>();
            src.add(character(1, "有id", null, null));
            src.add(character(null, "没id", null, null));

            assertThat(mapper.toCharacters(8, src).characters())
                    .extracting(SubjectCharacter::getCharacterId)
                    .containsExactly(1);
        }

        /** null 名字塞进空串 —— name 那一列是 NOT NULL, 塞 null 会让整批回滚 */
        @Test
        @DisplayName("名字是 null 时落空串, 不是 null")
        void nullNameBecomesEmpty() {
            assertThat(mapper.toCharacters(8, List.of(character(1, null, null, null))).characters())
                    .singleElement()
                    .extracting(SubjectCharacter::getName)
                    .isEqualTo("");
        }

        /** 一个元素都没有时给出两个空列表, 而不是 null —— 落库那边直接 saveAll */
        @Test
        @DisplayName("上游给了空列表: 两批都是空的, 不是 null")
        void emptyInputGivesEmptyLists() {
            SubjectExtrasMapper.CharacterRows rows = mapper.toCharacters(8, List.of());

            assertThat(rows.characters()).isEmpty();
            assertThat(rows.actors()).isEmpty();
        }
    }

    // ══════════ 制作人员 ══════════

    @Nested
    @DisplayName("制作人员")
    class Staff {

        /** 实测同一 id 会以不同 relation 反复出现(最多 5 次) —— 两行都要留下来 */
        @Test
        @DisplayName("同一个人的两条职务各留一行")
        void keepsRepeatedPersonsWithDifferentRelations() {
            List<PersonDTO> src = List.of(
                    person(419, "某人", "原作", null),
                    person(419, "某人", "脚本", null));

            assertThat(mapper.toStaff(8, src))
                    .extracting(SubjectStaff::getRelation, SubjectStaff::getSortOrder)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("原作", 0),
                            org.assertj.core.groups.Tuple.tuple("脚本", 1));
        }

        /** sortOrder 是原始下标: 跳过中间一行时, 后面那些的行号不许因此前移 */
        @Test
        @DisplayName("跳到没有 id 的行时, 其余行的 sortOrder 保持上游下标")
        void sortOrderKeepsUpstreamIndex() {
            List<PersonDTO> src = Arrays.asList(
                    person(1, "甲", "监督", null),
                    person(null, "没id", null, null),
                    person(3, "丙", "原画", null));

            assertThat(mapper.toStaff(8, src))
                    .extracting(SubjectStaff::getPersonId, SubjectStaff::getSortOrder)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, 0),
                            org.assertj.core.groups.Tuple.tuple(3, 2));
        }

        /** 落的是上游地址, 不是代理地址 —— 存代理地址等于把白名单规则冻结进数据 */
        @Test
        @DisplayName("存的是上游原地址, 不走 proxied()")
        void storesTheUpstreamUrl() {
            List<SubjectStaff> rows = mapper.toStaff(8, List.of(person(1, "甲", "监督",
                    images(null, null, null, "https://lain.bgm.tv/pic/crt/g/9.jpg", null))));

            String stored = rows.get(0).getImage();
            assertThat(stored)
                    .as("存代理地址会把当前的白名单规则冻结进数据里")
                    .isEqualTo("https://lain.bgm.tv/pic/crt/g/9.jpg")
                    .doesNotStartWith("/api/img");
        }
    }

    // ══════════ 关联条目 ══════════

    @Nested
    @DisplayName("关联条目")
    class Relations {

        /**
         * <b>关联条目的首选是 large, 与角色/人员那一侧相反。</b>
         *
         * <p>实测这个接口的 grid/common/medium/small <b>全部</b>是
         * {@code /r/N/pic/…} 形式(过不了白名单, 0%), 只有 large 是 {@code /pic/…}(100%)。
         * 排错顺序的后果不是报错, 而是每张卡都退回直连上游 —— 能用, 但白名单形同虚设,
         * 而且没有任何症状。
         *
         * <p>这条用例同时钉住了两件事: mapper 选了 large, 以及选出来的地址
         * <b>确实变成了代理地址</b>({@code /api/img?url=…} 开头)。只断言后者的话,
         * 一个把 grid 放在首位、又恰好只有 large 的输入也会绿; 只断言前者的话,
         * DTO 忘了过 {@code proxied()} 也会绿。
         */
        @Test
        @DisplayName("grid 是 /r/ 前缀而 large 能过白名单时, 选 large 且真的换成了代理地址")
        void prefersLargeAndProxiesIt() {
            RelatedSubjectDTO dto = related(100, "原名", "中文名", "续集", images(
                    "https://lain.bgm.tv/pic/cover/l/abc.jpg", null, null,
                    "https://lain.bgm.tv/r/400/pic/cover/g/abc.jpg", null));

            List<SubjectRelation> rows = mapper.toRelations(8, List.of(dto));

            assertThat(rows).singleElement()
                    .extracting(SubjectRelation::getImage)
                    .as("选的是 large 那一档")
                    .isEqualTo("https://lain.bgm.tv/pic/cover/l/abc.jpg");

            assertThat(SubjectExtrasDTO.RelationDTO.from(rows.get(0)).getImage())
                    .as("选对了档还不够 —— 白名单内的地址必须换成 /api/img?url=…, "
                            + "断言'非空'在这里是没用的: proxied() 对白名单外原样返回")
                    .startsWith("/api/img?url=")
                    .contains("lain.bgm.tv");
        }

        /** name 与 nameCn 两个都存下来, 挑哪个显示是前端的事 */
        @Test
        @DisplayName("name_cn 是空串时照存空串, 不做退化成原名")
        void keepsBothNames() {
            List<SubjectRelation> rows = mapper.toRelations(8,
                    List.of(related(100, "原名", "", "前传", null)));

            assertThat(rows).singleElement()
                    .extracting(SubjectRelation::getName, SubjectRelation::getNameCn)
                    .containsExactly("原名", "");
        }

        /**
         * 关联到自己那一行<b>不过滤</b>。
         *
         * <p>实测上游的关联列表里不会出现自己, 所以这条路径现在取不到。刻意不留过滤:
         * "上游会不会返回自己"不该由我们猜, 而过滤掉一行会让行数与上游对不上。
         */
        @Test
        @DisplayName("relatedId 与 subjectId 相同时照样落库(不猜上游)")
        void doesNotFilterSelfRelation() {
            List<SubjectRelation> rows = mapper.toRelations(8, List.of(related(8, "自己", null, "系列", null)));

            assertThat(rows).singleElement()
                    .extracting(SubjectRelation::getRelatedId)
                    .isEqualTo(8);
        }

        /** name 那一列可空, 所以走 cap 而不是 text: null 保持 null */
        @Test
        @DisplayName("没有原名时 name 是 null(这一列可空)")
        void nullNameStaysNull() {
            assertThat(mapper.toRelations(8, List.of(related(100, null, null, null, null))))
                    .singleElement()
                    .extracting(SubjectRelation::getName)
                    .isNull();
        }

        /** 没有 id 的行跳过 */
        @Test
        @DisplayName("没有 id 的关联条目被跳过")
        void skipsRelationsWithoutId() {
            List<RelatedSubjectDTO> src = Arrays.asList(
                    related(1, "甲", null, null, null),
                    related(null, "没id", null, null, null));

            assertThat(mapper.toRelations(8, src))
                    .extracting(SubjectRelation::getRelatedId)
                    .containsExactly(1);
        }
    }
}

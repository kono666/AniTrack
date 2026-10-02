package com.animetracker.service;

import com.animetracker.entity.SubjectCharacter;
import com.animetracker.entity.SubjectCharacterActor;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import com.animetracker.service.SubjectExtrasMapper.CharacterRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SubjectExtrasWriter} 在真库上的行为 —— 起 Spring 上下文, 真 H2, 真事务.
 *
 * <p><b>为什么这一条非得用真库。</b> 这个类存在的全部理由是那三段"先删后插 + 写 marker"
 * 必须同一个事务, 而事务在 mock 里根本不存在: 用替身写出来的用例<b>无论有没有
 * {@code @Transactional} 都是绿的</b>。这里量的是"出异常之后库里到底剩下什么",
 * 只有真库会留下痕迹。
 *
 * <p><b>类上刻意不加 {@code @Transactional}</b>(与 {@code WriteConflictIntegrationTest} 同一条
 * 理由): 加了之后每个用例自己成了外层事务, 被测代码里那个事务会加入它, 于是"回滚"
 * 变成"回滚到用例开始之前" —— 一条都不会红, 而这个类就白写了。不挂事务才更接近真实
 * 路径: {@code SubjectExtrasService} 是无事务的, 这里调 {@code writer} 时的处境与生产一致。
 *
 * <p><b>这个类同时是"跨 bean 不是装饰"那条变异的哨兵。</b> 把 {@code replaceCharacters}
 * 搬回 {@code SubjectExtrasService} 里改成 {@code this.} 自调用, 或者去掉这里的
 * {@code @Transactional}, 效果是同一个: 删除自己提交了、插入失败回滚不了 ——
 * 下面 {@code failedReplaceKeepsTheOldRows} 会红, 而<b>正常路径上的用例一条都不红</b>。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-subject-extras;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@ActiveProfiles("dev")
class SubjectExtrasWriterTest {

    private static final Integer SUBJECT = 8001;

    @Autowired
    private SubjectExtrasWriter writer;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTables() {
        jdbc.execute("DELETE FROM subject_character");
        jdbc.execute("DELETE FROM subject_character_actor");
        jdbc.execute("DELETE FROM subject_staff");
        jdbc.execute("DELETE FROM subject_relation");
        jdbc.execute("DELETE FROM subject_extras");
    }

    // ── 造行 ────────────────────────────────────────────

    private static SubjectCharacter character(Integer sid, Integer characterId, String name, int order) {
        return SubjectCharacter.builder().subjectId(sid).characterId(characterId)
                .name(name).relation("主角").sortOrder(order).build();
    }

    private static SubjectCharacterActor actor(Integer sid, Integer characterId, Integer actorId, String name) {
        return SubjectCharacterActor.builder().subjectId(sid).characterId(characterId)
                .actorId(actorId).name(name).sortOrder(0).build();
    }

    private static SubjectStaff staff(Integer sid, Integer personId, String name) {
        return SubjectStaff.builder().subjectId(sid).personId(personId)
                .name(name).relation("监督").sortOrder(0).build();
    }

    private static SubjectRelation relation(Integer sid, Integer relatedId, String name) {
        return SubjectRelation.builder().subjectId(sid).relatedId(relatedId)
                .name(name).relation("续集").sortOrder(0).build();
    }

    // ── 问库 ────────────────────────────────────────────

    private long rows(String table, Integer subjectId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE subject_id = ?", Long.class, subjectId);
        return n == null ? 0 : n;
    }

    private LocalDateTime marker(String column, Integer subjectId) {
        List<LocalDateTime> found = jdbc.queryForList(
                "SELECT " + column + " FROM subject_extras WHERE subject_id = ?",
                LocalDateTime.class, subjectId);
        return found.isEmpty() ? null : found.get(0);
    }

    // ══════════ 正常路径 ══════════

    @Test
    @DisplayName("角色与声优两批一起落库, 并推 characters 那一列的 marker")
    void replacesCharactersAndMarksTheSection() {
        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 1, "角色甲", 0), character(SUBJECT, 2, "角色乙", 1)),
                List.of(actor(SUBJECT, 1, 100, "声优A"))));

        assertThat(rows("subject_character", SUBJECT)).isEqualTo(2);
        assertThat(rows("subject_character_actor", SUBJECT)).isEqualTo(1);
        assertThat(marker("characters_fetched_at", SUBJECT)).isNotNull();
    }

    /**
     * 第二次回源是<b>替换</b>而不是追加。
     *
     * <p>写成追加的话, 每次 TTL 到期就多一批行, 而界面上看到的是角色列表里同一批人
     * 重复出现 —— 一天翻一倍, 没有任何地方会报错。
     */
    @Test
    @DisplayName("再写一次是替换: 上一批行被删掉, 不是追加")
    void theSecondWriteReplacesTheFirst() {
        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 1, "老的甲", 0), character(SUBJECT, 2, "老的乙", 1)),
                List.of(actor(SUBJECT, 1, 100, "老声优"), actor(SUBJECT, 1, 101, "老声优二"))));

        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 3, "新的", 0)),
                List.of(actor(SUBJECT, 3, 200, "新声优"))));

        assertThat(rows("subject_character", SUBJECT)).isEqualTo(1);
        assertThat(rows("subject_character_actor", SUBJECT)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM subject_character WHERE subject_id = ?", String.class, SUBJECT))
                .isEqualTo("新的");
    }

    /**
     * <b>只删自己这个条目的行。</b>
     *
     * <p>{@code deleteBySubjectId} 少了那个 where 会把整张表清掉 —— 而只用一个条目
     * 写用例的话永远发现不了。这条同时是"另外两部番的数据还在"的证明。
     */
    @Test
    @DisplayName("替换只动自己这个条目, 别的条目的行不受影响")
    void replacingOneSubjectLeavesTheOthersAlone() {
        writer.replaceCharacters(8002, new CharacterRows(
                List.of(character(8002, 1, "别的番的角色", 0)), List.of()));
        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 1, "本番的角色", 0)), List.of()));

        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 2, "本番的新角色", 0)), List.of()));

        assertThat(rows("subject_character", 8002)).as("别的条目的行不该被删").isEqualTo(1);
        assertThat(rows("subject_character", SUBJECT)).isEqualTo(1);
    }

    /**
     * "完整取回但是空的"也是一个要落库的结果 —— 它把这一块清空并推上 marker。
     *
     * <p>没有这条路径的话, 一个条目从"有角色"变成"上游说没有"时就永远清不掉了,
     * 而 marker 的判据认为已经取过, 于是旧数据留在页面上直到永远。
     */
    @Test
    @DisplayName("空列表是有效输入: 清空这一块并且照样推 marker")
    void anEmptyBatchClearsTheSectionAndStillMarksIt() {
        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 1, "旧的", 0)), List.of(actor(SUBJECT, 1, 100, "旧声优"))));

        writer.replaceCharacters(SUBJECT, new CharacterRows(List.of(), List.of()));

        assertThat(rows("subject_character", SUBJECT)).isZero();
        assertThat(rows("subject_character_actor", SUBJECT)).isZero();
        assertThat(marker("characters_fetched_at", SUBJECT)).isNotNull();
    }

    /** 三列 marker 各自独立: 只写角色那一块时, 另外两列还是 NULL */
    @Test
    @DisplayName("三列 marker 各自独立, 只写自己那一列")
    void eachSectionMarksOnlyItsOwnColumn() {
        writer.replaceCharacters(SUBJECT, new CharacterRows(List.of(), List.of()));

        assertThat(marker("characters_fetched_at", SUBJECT)).isNotNull();
        assertThat(marker("staff_fetched_at", SUBJECT)).isNull();
        assertThat(marker("relations_fetched_at", SUBJECT)).isNull();
    }

    /** 三块的落库方法各推自己那一列 —— 接错了不会有编译错误 */
    @Test
    @DisplayName("人员与关联条目各推自己那一列, 且账本那一行只建一次")
    void staffAndRelationsMarkTheirOwnColumns() {
        writer.replaceStaff(SUBJECT, List.of(staff(SUBJECT, 10, "监督")));
        writer.replaceRelations(SUBJECT, List.of(relation(SUBJECT, 20, "续集")));

        assertThat(marker("staff_fetched_at", SUBJECT)).isNotNull();
        assertThat(marker("relations_fetched_at", SUBJECT)).isNotNull();
        assertThat(marker("characters_fetched_at", SUBJECT)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject_extras WHERE subject_id = ?", Long.class, SUBJECT))
                .as("三块共用一个条目的账本行, 第一次插入之后就不该再插")
                .isEqualTo(1);
    }

    /** 先写人员再写角色: 第二次是在已有的账本行上补一列, 不是插第二行 */
    @Test
    @DisplayName("第二块写进来时是在同一行上补列, 不是新建一行")
    void theMarkerRowIsReusedAcrossSections() {
        writer.replaceStaff(SUBJECT, List.of(staff(SUBJECT, 10, "监督")));
        LocalDateTime staffAt = marker("staff_fetched_at", SUBJECT);

        writer.replaceRelations(SUBJECT, List.of(relation(SUBJECT, 20, "续集")));

        assertThat(marker("staff_fetched_at", SUBJECT))
                .as("补第二列不该把第一列覆盖掉")
                .isEqualTo(staffAt);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject_extras WHERE subject_id = ?", Long.class, SUBJECT))
                .isEqualTo(1);
    }

    // ══════════ 事务: 唯一的守卫 ══════════

    /**
     * <b>这个类的核心用例: 落库中途失败时, 老行必须还在, marker 必须没动。</b>
     *
     * <p>触发失败的方式是给一个 {@code name} 为 null 的行 —— {@code name} 那一列是
     * NOT NULL, 而且 {@code saveAll} 在 IDENTITY 主键下必须立刻发出 INSERT 才能拿到 id,
     * 所以异常就在这一批中途抛出来。(mapper 那条 {@code text(null) → ""} 挡的是上游数据,
     * 这里刻意绕过 mapper 直接造行, 因为要验的正是"出异常之后剩下什么"。)
     *
     * <p>去掉 {@code @Transactional} 之后会发生什么, 说清楚: 删除是靠
     * {@code @Modifying @Transactional} 的仓储方法发的, 它<b>自己提交</b>; 插入在另一个
     * 事务里失败回滚。于是库里只剩一个"这一块被清空了"的状态, 而 marker 还是上一次的
     * 值 —— 判据认为取过了, 于是那块内容<b>永远空着</b>, 直到 TTL 到期。
     *
     * <p>把 {@code replaceCharacters} 搬回 {@code SubjectExtrasService} 改成 {@code this.}
     * 自调用, 结果与去掉 {@code @Transactional} 完全相同(代理不生效) —— 这是同一条变异的
     * 两种写法, 所以这一条用例同时守住了"拆 bean"那件事不是装饰。
     */
    @Test
    @DisplayName("一批里有非法行时整批回滚: 老行还在, marker 还是上一次的值")
    void failedReplaceKeepsTheOldRows() {
        writer.replaceCharacters(SUBJECT, new CharacterRows(
                List.of(character(SUBJECT, 1, "老的甲", 0), character(SUBJECT, 2, "老的乙", 1)),
                List.of(actor(SUBJECT, 1, 100, "老声优"))));
        LocalDateTime firstMark = marker("characters_fetched_at", SUBJECT);
        assertThat(firstMark).isNotNull();

        // 绕过 mapper 造一个 name 为 null 的行: NOT NULL 会在这一批中途炸掉
        CharacterRows broken = new CharacterRows(
                List.of(character(SUBJECT, 9, "这一行是好的", 0),
                        SubjectCharacter.builder().subjectId(SUBJECT).characterId(10)
                                .name(null).sortOrder(1).build()),
                List.of());

        assertThatThrownBy(() -> writer.replaceCharacters(SUBJECT, broken))
                .as("name 那一列是 NOT NULL, 这一批必须炸")
                .isInstanceOf(Exception.class);

        assertThat(rows("subject_character", SUBJECT))
                .as("删除必须跟着回滚 —— 不回滚的话这一块就凭空消失了, 而 marker 还说取过")
                .isEqualTo(2);
        assertThat(rows("subject_character_actor", SUBJECT)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM subject_character WHERE subject_id = ? AND character_id = 1",
                String.class, SUBJECT))
                .isEqualTo("老的甲");
        assertThat(marker("characters_fetched_at", SUBJECT))
                .as("marker 不许被推进: 推进了就变成「账上说取过、表里是老数据」")
                .isEqualTo(firstMark);
    }

    /**
     * 从没写过这个条目时失败, 账本里连那一行都不该出现。
     *
     * <p>与上一条是两个方向: 上一条防"老行被删", 这一条防"账本先落" ——
     * {@code mark()} 在插入之后, 但事务拆开时它在另一个事务里, 照样会落。
     */
    @Test
    @DisplayName("第一次就失败时, 账本里连一行都不留")
    void failedFirstWriteLeavesNoMarkerRow() {
        CharacterRows broken = new CharacterRows(
                List.of(SubjectCharacter.builder().subjectId(SUBJECT).characterId(10)
                        .name(null).sortOrder(0).build()),
                List.of());

        assertThatThrownBy(() -> writer.replaceCharacters(SUBJECT, broken))
                .isInstanceOf(Exception.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject_extras WHERE subject_id = ?", Long.class, SUBJECT))
                .as("账本行落了的话, 判据就认为取过了, 而表里一行都没有")
                .isZero();
        assertThat(rows("subject_character", SUBJECT)).isZero();
    }

    /**
     * 人员与关联条目那两条路径上挂着同一套保证 —— 只测角色那一块的话,
     * 另外两个方法各去掉一次 {@code @Transactional} 都不会红。
     */
    @Test
    @DisplayName("人员那一块失败时同样整批回滚")
    void failedStaffReplaceKeepsTheOldRows() {
        writer.replaceStaff(SUBJECT, List.of(staff(SUBJECT, 10, "老的监督")));
        LocalDateTime firstMark = marker("staff_fetched_at", SUBJECT);

        assertThatThrownBy(() -> writer.replaceStaff(SUBJECT, List.of(
                staff(SUBJECT, 11, "新的监督"),
                SubjectStaff.builder().subjectId(SUBJECT).personId(12).name(null).sortOrder(1).build())))
                .isInstanceOf(Exception.class);

        assertThat(rows("subject_staff", SUBJECT)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM subject_staff WHERE subject_id = ?", String.class, SUBJECT))
                .isEqualTo("老的监督");
        assertThat(marker("staff_fetched_at", SUBJECT)).isEqualTo(firstMark);
    }

    /**
     * ⚠️ 这一张表造失败用的是 {@code related_id} 而不是 {@code name} —— 它是四张内容表里
     * 唯一一张 {@code name} <b>可空</b>的(关联条目本来就可能没有原名), 拿 null 名字去撞
     * 是撞不响的, 用例会在 {@code assertThatThrownBy} 上直接红。
     */
    @Test
    @DisplayName("关联条目那一块失败时同样整批回滚")
    void failedRelationsReplaceKeepsTheOldRows() {
        writer.replaceRelations(SUBJECT, List.of(relation(SUBJECT, 20, "老的续集")));
        LocalDateTime firstMark = marker("relations_fetched_at", SUBJECT);

        assertThatThrownBy(() -> writer.replaceRelations(SUBJECT, List.of(
                relation(SUBJECT, 21, "新的续集"),
                SubjectRelation.builder().subjectId(SUBJECT).relatedId(null).sortOrder(1).build())))
                .as("related_id 是 NOT NULL; 这一列的 name 反而是可空的, 撞不响")
                .isInstanceOf(Exception.class);

        assertThat(rows("subject_relation", SUBJECT)).isEqualTo(1);
        assertThat(marker("relations_fetched_at", SUBJECT)).isEqualTo(firstMark);
    }
}

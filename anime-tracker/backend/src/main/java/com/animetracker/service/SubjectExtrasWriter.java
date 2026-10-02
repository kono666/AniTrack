package com.animetracker.service;

import com.animetracker.entity.SubjectExtras;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import com.animetracker.repository.SubjectCharacterActorRepository;
import com.animetracker.repository.SubjectCharacterRepository;
import com.animetracker.repository.SubjectExtrasRepository;
import com.animetracker.repository.SubjectRelationRepository;
import com.animetracker.repository.SubjectStaffRepository;
import com.animetracker.service.SubjectExtrasMapper.CharacterRows;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 附属数据的落库 —— <b>全项目唯一一个写这四张表的地方, 而且是一个独立的 bean</b>.
 *
 * <h2>为什么必须是另一个 bean</h2>
 *
 * <p>Spring 的 {@code @Transactional} 靠代理生效, 而<b>同类内的自调用不走代理</b> ——
 * 把这三段逻辑搬回 {@link SubjectExtrasService} 里、改成 {@code this.replaceCharacters(...)},
 * 事务会<b>静默消失</b>: 方法照样跑完、数据照样写进去, 只是不再原子。
 * 那不是"少了一层保护", 而是下面这条保证变成了谎话, 并且在正常路径上<b>一条用例都不会红</b>
 * —— 只有真出异常时才看得出来。这个坑本仓在 {@code AnimeService.upsertCalendarItem} 上
 * 记过一次, 这里的形状更险: 它有三段, 每段都是"先删后插"。
 *
 * <h2>这个事务圈住的到底是什么</h2>
 *
 * <p>三件事必须同生共死:
 * <ol>
 *   <li><b>删旧行</b> —— 完整回源时先清空这个条目的这一块;</li>
 *   <li><b>插新行</b>;</li>
 *   <li><b>写 marker</b>(那张只有三列时间戳的账)。</li>
 * </ol>
 *
 * <p>拆开的后果各不相同, 而且都不会报错:
 * <ul>
 *   <li>删与插分开 → 中间有一个"这个条目没有任何角色"的窗口, 别的请求读到的就是它,
 *       用户看到一块凭空消失的内容;</li>
 *   <li>marker 先落 → 内容插失败时留下"账上说取过、表里是空的", 于是这块<b>永远空着</b>
 *       (判据认为已经取过了), 直到 TTL 到期为止;</li>
 *   <li>任何一行超长(列宽不够) → 全部回滚, marker 也不写, 下次重取再失败 ——
 *       这就是 {@link SubjectExtrasMapper} 里那个 {@code cap()} 存在的理由。</li>
 * </ul>
 *
 * <p>⚠️ <b>这里刻意没有 {@code REQUIRES_NEW}。</b> {@code IsolatedInsert} 用它是为了
 * "外层已经有事务时, 把一个可能撞唯一约束的插入摘出去", 而本类的调用方
 * ({@link SubjectExtrasService}) 是<b>无事务</b>的(HTTP 被刻意放在事务外), 所以
 * 默认的 {@code REQUIRED} 开出来的就是它自己的那个事务。改成 {@code REQUIRES_NEW}
 * 在今天不会有任何区别, 但那会让"这个事务的边界在哪"重新变成一个需要想的问题。
 */
@Component
public class SubjectExtrasWriter {

    private final SubjectExtrasRepository extrasRepository;
    private final SubjectCharacterRepository characterRepository;
    private final SubjectCharacterActorRepository actorRepository;
    private final SubjectStaffRepository staffRepository;
    private final SubjectRelationRepository relationRepository;

    public SubjectExtrasWriter(SubjectExtrasRepository extrasRepository,
                               SubjectCharacterRepository characterRepository,
                               SubjectCharacterActorRepository actorRepository,
                               SubjectStaffRepository staffRepository,
                               SubjectRelationRepository relationRepository) {
        this.extrasRepository = extrasRepository;
        this.characterRepository = characterRepository;
        this.actorRepository = actorRepository;
        this.staffRepository = staffRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * 用上游这一批替换掉这个条目的角色与声优.
     *
     * <p>两批一起进来而不是分两次调用: 它们来自<b>同一个响应</b>, 分开写就有"角色写进去了、
     * 声优没写"的中间态, 而且那个状态是<b>自洽的</b> —— 界面上会画出一排没有 CV 的角色卡,
     * 看起来就像"这部番的声优信息没收录"。
     */
    @Transactional
    public void replaceCharacters(Integer subjectId, CharacterRows rows) {
        characterRepository.deleteBySubjectId(subjectId);
        actorRepository.deleteBySubjectId(subjectId);
        characterRepository.saveAll(rows.characters());
        actorRepository.saveAll(rows.actors());
        mark(subjectId, e -> e.setCharactersFetchedAt(LocalDateTime.now()));
    }

    /** 用上游这一批替换掉这个条目的制作人员 */
    @Transactional
    public void replaceStaff(Integer subjectId, List<SubjectStaff> rows) {
        staffRepository.deleteBySubjectId(subjectId);
        staffRepository.saveAll(rows);
        mark(subjectId, e -> e.setStaffFetchedAt(LocalDateTime.now()));
    }

    /** 用上游这一批替换掉这个条目的关联条目 */
    @Transactional
    public void replaceRelations(Integer subjectId, List<SubjectRelation> rows) {
        relationRepository.deleteBySubjectId(subjectId);
        relationRepository.saveAll(rows);
        mark(subjectId, e -> e.setRelationsFetchedAt(LocalDateTime.now()));
    }

    /**
     * 把某一列的 marker 推到"现在", 没有这一行就建一行.
     *
     * <p>时刻取<b>应用这一侧</b>的 {@code now} 而不是数据库的, 与 {@code LoginEvent} 同一
     * 口径 —— 两处混用会让"这个时间到底是谁的表"变成一个每次都要求证的问题。
     *
     * <p>它是 private 且只能被上面三个事务方法调用: marker 与内容必须在同一个事务里,
     * 所以这个类不对外提供"只推一下 marker"的口子。{@link SubjectExtrasRepository}
     * 上也刻意没有别的方法。
     */
    private void mark(Integer subjectId, Consumer<SubjectExtras> setter) {
        SubjectExtras row = extrasRepository.findById(subjectId)
                .orElseGet(() -> SubjectExtras.builder().subjectId(subjectId).build());
        setter.accept(row);
        extrasRepository.save(row);
    }
}

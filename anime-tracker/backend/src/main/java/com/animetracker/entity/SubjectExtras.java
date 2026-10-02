package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 一个条目的附属数据「取过没有」的账 —— 一行, 三列时间戳.
 *
 * <p>这张表里<b>一行业务数据都没有</b>, 它存在的唯一理由是让"要不要回源"这件事
 * 有一个可查的答案。内容在 {@link SubjectCharacter} / {@link SubjectCharacterActor} /
 * {@link SubjectStaff} / {@link SubjectRelation} 四张表里。
 *
 * <p><b>为什么是时间戳而不是计数器或布尔.</b> 布尔只能说"取过", 而附属数据会变
 * (新季度加角色、补录人员、新关联条目上线), 所以"取过"必须配上"什么时候取的"才能
 * 判断还算不算数 —— 这一列同时承担了 TTL 判据。计数器则是第二份事实来源, 它与内容表
 * 的行数会漂移, 而漂移不报错。
 *
 * <p><b>{@code NULL} 与"有值"是一对干净的判据:</b> NULL = 从没取过, 或者取到过但
 * 没取全(见 {@code SubjectExtrasWriter}); 非 NULL = 某个时刻完整取回过。
 * 「取全了但确实是空的」(上游 404, 或者这个条目真的没有角色) 也会写上一个非 NULL 的值
 * —— 那是一个确定的答案, 值得记下来, 否则每次打开详情页都要再问一次上游。
 *
 * <p><b>三个 section 是三列而不是 {@code (subject_id, section)} 两列</b>, 理由写在
 * {@code db/migration/h2/V17__add_subject_extras.sql} 的头部: 后者把 section 变成字符串,
 * 拼错不编译失败、只静默地永远查不到 marker, 于是每次打开详情页都重取一遍。
 *
 * <p><b>主键是 {@code subjectId} 本身(自然键), 没有自增列.</b> 一个条目一行, 这是它的
 * 定义。也没有外键 —— 与 {@code login_event.user_id} 同一条理由: 三个 extras 请求与
 * {@code /subject/{id}} 是并行发的, 写这一行时 {@code anime} 那行可能还不存在。
 */
@Entity
@Table(name = "subject_extras")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectExtras {

    /** Bangumi 的条目 id, 也是主键 —— 没有自增列 */
    @Id
    @Column(name = "subject_id")
    private Integer subjectId;

    /** 角色那一块完整取回的时刻. NULL = 没取过 / 没取全 */
    @Column(name = "characters_fetched_at")
    private LocalDateTime charactersFetchedAt;

    /** 制作人员那一块完整取回的时刻 */
    @Column(name = "staff_fetched_at")
    private LocalDateTime staffFetchedAt;

    /** 关联条目那一块完整取回的时刻 */
    @Column(name = "relations_fetched_at")
    private LocalDateTime relationsFetchedAt;
}

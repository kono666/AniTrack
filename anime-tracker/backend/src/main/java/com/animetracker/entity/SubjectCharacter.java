package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 一个条目下的一个角色. 声优在 {@link SubjectCharacterActor}(另一张表).
 *
 * <p><b>为什么主键是自增的代理键, 而不是照抄 {@code episode} 那套自然键.</b>
 * 自然键的前提是"上游给的 id 在这一组里唯一", 而实测这个前提在附属数据上不成立:
 * 同一个 person 会以不同职务反复出现(最多 5 次), 同一个人名会原样出现两遍。
 * 角色这一侧 {@code id} 实测是唯一的(128/128), 但声优那一侧不是, 而两边的形状
 * 必须一致 —— 一半自然键一半代理键, 读的人要先想清楚"这张表能不能有重复行",
 * 而那个问题没有一眼可见的答案。完整论证见
 * {@code db/migration/h2/V17__add_subject_extras.sql}.
 *
 * <p><b>为什么存 {@code sortOrder}.</b> 落库走的是"完整回源时先删后插", 于是行的物理
 * 顺序不再是上游给的顺序; 而 SELECT 不带 ORDER BY 时数据库返回的顺序没有任何保证
 * (小表上碰巧是插入序, 一旦换了执行计划就变)。角色的排列顺序是有意义的 ——
 * 主角不该排在闲角后面 —— 所以把上游给的顺序抄下来, 而不是读的时候按 relation 猜一个。
 *
 * <p><b>{@code subjectId} 是普通字段而不是 {@code @ManyToOne Anime}</b>: 表上没有外键
 * (写这一行时 {@code anime} 那行可能还不存在), 而 {@code @ManyToOne} 会在写的时候去挂
 * 一个托管实体 —— 那正是这张表刻意不要的形状。与 {@code LoginEvent.userId} 同一个取舍。
 */
@Entity
@Table(name = "subject_character")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属条目. 没有外键, 见类注释 */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 上游的角色 id */
    @Column(name = "character_id", nullable = false)
    private Integer characterId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 角色定位: 主角 / 配角 / 闲角 / 旁白. 上游可能不给 */
    @Column(length = 32)
    private String relation;

    /**
     * 上游原样的图片地址 —— <b>存原串, 不存代理地址</b>。
     *
     * <p>与 {@code anime.cover_url} 一致: 过 {@code CoverImages.proxied()} 是**读的时候**
     * 做的事(见 {@code AnimeMapper.buildImages})。存代理地址等于把"当前的白名单规则"
     * 冻结进数据里 —— 哪天白名单放宽了, 存量行不会跟着变, 得重新抓一遍上游才能修好。
     */
    @Column(length = 500)
    private String image;

    /** 上游给的顺序, 从 0 开始 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}

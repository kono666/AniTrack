package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 一个条目的一个关联条目 —— 前传 / 续集 / 剧场版 / 游戏 / 画集 …
 *
 * <p><b>为什么 {@code name} 与 {@code nameCn} 都可空.</b> 上游的 {@code name}(原名)
 * 实测总是有值, 但 {@code name_cn} 大量是空串或干脆没有 —— 不是"取坏了", 是那些条目
 * 本来就没有中文名。这一列可空之后, "没有中文名"才是一个能表达的事实; 硬塞一个占位串
 * (比如把原名抄过去)会让"这条到底有没有中文名"变得看不出来, 而界面上那两种情况的
 * 排版是不一样的。
 *
 * <p><b>没有 {@code date} 列</b> —— 上游的这三个接口返回的关联条目里<b>没有日期字段</b>
 * (实测键只有 {@code id, images, name, name_cn, relation, type})。所以卡片上不印年份
 * 不是"我们没取", 是上游不给; 这一点写在这里, 免得下一个人以为补个字段就行。
 *
 * <p>{@code relation} 是从**我们这个条目**看过去的方向(前传/续集), 而不是对面条目的
 * 属性 —— 同一条数据在对面那条目上看是反的。所以它存下来是这个条目的性质,
 * 与 {@code relatedId} 一起构成一行。
 */
@Entity
@Table(name = "subject_relation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属条目. 没有外键 —— 被关联的那个条目我们库里多半根本没有 */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 被关联的条目 id. 按下面的取舍, 它也不需要是一个我们库里的条目 */
    @Column(name = "related_id", nullable = false)
    private Integer relatedId;

    /** 原名. 上游总是给 */
    @Column(length = 200)
    private String name;

    /** 中文名. 大量为空, 见类注释 */
    @Column(name = "name_cn", length = 200)
    private String nameCn;

    /** 前传 / 续集 / 剧场版 / 游戏 / 画集 … */
    @Column(length = 32)
    private String relation;

    /** 上游原样地址, 代理在读取侧做 —— 理由见 {@link SubjectCharacter#getImage()} */
    @Column(length = 500)
    private String image;

    /** 上游给的顺序, 从 0 开始 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}

package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 一个条目下的一位制作人员(上游叫 persons, 界面上叫"制作人员").
 *
 * <p>形状与 {@link SubjectCharacter} 几乎一样, 差别只有一个: 这里的 {@code relation}
 * (原画 / 作画监督 / 演出 …) <b>是主要信息而不是补充</b>, 而且同一个人会以不同职务
 * 反复出现 —— 实测 {@code /v0/subjects/8/persons} 里 person 419 出现 5 次,
 * {@code /v0/subjects/253/persons} 里 {@code 95767|助理制片人} 原样出现两遍(名字也相同)。
 * 所以这张表上一样不能有唯一约束, 主键一样是代理键。
 *
 * <p>为什么叫 staff 而不是照抄上游的 persons: 界面上那个标题是"制作人员", 而
 * {@code persons} 在代码里与 {@code user} 那一侧的人容易混。上游路径仍写 persons,
 * 只有我们这一侧的名字换成更清楚的这个。
 */
@Entity
@Table(name = "subject_staff")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属条目. 没有外键 */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 上游的人物 id. 同一人可重复出现, 见类注释 */
    @Column(name = "person_id", nullable = false)
    private Integer personId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 职务. 可空: 上游不是每条都给 */
    @Column(length = 32)
    private String relation;

    /** 上游原样地址, 代理在读取侧做 —— 理由见 {@link SubjectCharacter#getImage()} */
    @Column(length = 500)
    private String image;

    /** 上游给的顺序, 从 0 开始 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}

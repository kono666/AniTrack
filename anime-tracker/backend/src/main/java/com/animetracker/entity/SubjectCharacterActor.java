package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 一个角色的一个声优 —— <b>单独一张表, 不是 {@link SubjectCharacter} 上的一列 CSV</b>.
 *
 * <p>为什么不能用 CSV: {@code anime.tags} 敢用逗号拼串, 前提是"40127 个标签一个都不含
 * 逗号"(有实测), 而人名<b>没有</b>这个前提(实测一个关联作品名里就带着分号,
 * {@code コードギアス Genesic Re;CODE})。更要紧的是 CSV 分不出"这个角色没有声优"与
 * "有一个名字是空串的声优" —— 而"没有声优"是常态: 实测 128 条角色里 63 条没有,
 * 有的最多 2 个。一对多 + 一个必须能表达的"零", 就是一张表。
 *
 * <p><b>为什么 {@code characterId} 在这里而不是靠外键指回 {@link SubjectCharacter}.</b>
 * 读路径是"取一个条目的全部声优, 按 characterId 分组贴到角色卡上", 一个普通列就够。
 * 挂外键反而会引入一个顺序要求: 得先插角色再插声优, 而这两批来自同一个上游响应、
 * 本该可以任意顺序写。表上没有外键是本项目的既有取舍(见
 * {@code db/migration/h2/V17__add_subject_extras.sql})。
 *
 * <p><b>没有 {@code relation} 列</b> —— 不是漏了: 实测上游的 actor 元素里
 * <b>0 条</b>带这个字段(角色那一侧有"主角/配角", 声优这一侧没有对应的东西)。
 *
 * <p>⚠️ <b>允许完全重复的行.</b> 实测同一个 {@code actorId} 在一个条目里最多出现 4 次
 * (同一个人配了不同角色), 声优与角色之间也没有"一对一"的保证。所以这张表上<b>不能</b>
 * 建任何唯一约束 —— 建了的结果不是"挡住坏数据", 而是整批插入失败、整块永远空着。
 */
@Entity
@Table(name = "subject_character_actor")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectCharacterActor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属条目. 没有外键 */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 这个声优配的是哪个角色 —— 读的时候按它分组 */
    @Column(name = "character_id", nullable = false)
    private Integer characterId;

    /** 上游的声优(人物)id. 同一人可在同一条目里重复出现, 见类注释 */
    @Column(name = "actor_id", nullable = false)
    private Integer actorId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 上游原样地址, 代理在读取侧做 —— 理由见 {@link SubjectCharacter#getImage()} */
    @Column(length = 500)
    private String image;

    /** 上游给的顺序(整个 actors 数组的序) */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}

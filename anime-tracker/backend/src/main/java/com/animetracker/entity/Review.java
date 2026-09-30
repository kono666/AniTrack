package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 短评: 一个用户对一部番只有一条. 约束说明同 {@link AnimeTracking}.
 *
 * <p><b>不要在这里加 {@code @OneToMany} 的回复集合。</b>回复走
 * {@code ReviewReplyRepository} 的查询, 理由有两条:
 * 一是删评论时库级的 {@code ON DELETE CASCADE}(V8)本来一条 {@code DELETE FROM review}
 * 就把回复和回复的赞全带走了, 而 Hibernate 一旦知道有这个集合, 它会先自己把子行读出来、
 * 逐个置空外键或逐条 DELETE —— 前者撞 NOT NULL 直接失败, 后者是 N 条语句;
 * 二是集合关联会让"对 review 做分页"退化成"把整个结果集读进内存再切页"。
 */
@Entity
@Table(name = "review",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_user_subject",
                columnNames = {"user_id", "subject_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Bangumi subject_id */
    @Column(name = "subject_id", nullable = false)
    private Integer subjectId;

    /** 评分 1-10 */
    @Column(nullable = false)
    private Integer rating;

    /** 评论内容 */
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 点赞数, 由 {@code review_like} 的行数冗余而来(V7)。
     *
     * <p><b>这个字段里有三个坑, 都不是风格问题。</b>
     *
     * <p><b>一、{@code insertable=false}: 插入时这一列根本不进 INSERT, 让列默认值 0 生效。</b>
     * "列默认值"要**两处都写**: 迁移脚本里那份(生产/开发)在 V7 里, 还有一份在这里的
     * {@code columnDefinition} —— 因为 {@code ddl-auto=create-drop} 的那几个测试用的是
     * <b>实体生成出来的表</b>, 根本不跑迁移。只写脚本的话, 那些环境里这一列是
     * "可空且没有默认值", 被省略的列就落成 NULL, 下一次读回来炸在一句
     * {@code Can not set long field ... Review.likeCount to null value} 上 ——
     * 一个完全指不到"schema 是从哪来的"的报错(实测见 AgentAsyncToolExecutionTest)。
     *
     * <p>至于字段类型: 这里用基本类型 {@code long} 而不是 {@code Long}, 理由是
     * <b>与库里的 {@code NOT NULL} 对齐</b>: 这一列任何时刻都有值, 映射成可能为 null
     * 的类型等于凭空造出一个不存在的状态。顺带一提, 基本类型在 Lombok 生成的 builder 里
     * 默认是 {@code 0} 而不是 null({@code @Builder} 忽略字段初始值, 但基本类型本来
     * 也没有 null 这个取值)。
     *
     * <p><b>但它不是靠类型救的</b> —— 实测把这里改成 {@code Long} 之后整个后端套件
     * 依然全绿, 因为 {@code insertable=false} 已经把这一列挡在 INSERT 之外了,
     * builder 里那个 null 到不了数据库。所以<b>别把 {@code insertable=false} 当成
     * 可选项</b>: 去掉它, null 就直接撞 {@code NOT NULL}, 而且只在新建评论时炸。
     *
     * <p><b>三、{@code updatable=false} 是防一条真实的丢数据路径, 不是洁癖。</b>
     * Hibernate 的默认 UPDATE 会把**所有**可更新列都写进 SET 子句, 于是任何
     * "读出来一份、之后再存回去"的路径都会把<b>读那一刻</b>的计数写回去, 这期间别人
     * 点的赞全部消失, 而且不会有任何报错。这套代码里这种形状是存在的:
     * {@code IsolatedInsert} 的注释就明说它返回的是**游离实体**, 而
     * {@code ReviewService.saveReview} 的冲突重试分支也是"拿到一个实体再存回去"。
     *
     * <p>注意走网页的那条路(read 与 write 在同一个持久化上下文里, 窗口只有几微秒)
     * <b>测不出这一条</b> —— 去掉 {@code updatable=false} 它照样是绿的, 这点是实测过的。
     * 真正守着它的是 {@code ReviewLikeIntegrationTest.savingAStaleReviewDoesNotClobberTheCounter},
     * 那条直接把"旧副本存回去"这个状态摆出来。
     *
     * <p>所以这一列的唯一写入者是 {@code ReviewRepository.increment/decrementLikeCount}
     * 里的 JPQL UPDATE(它是数据库里的原子自增, 不是读改写)。改这一列时请只改那条路径,
     * 别再开第二条; 对账用例在 {@code ReviewLikeIntegrationTest} 里守着。
     */
    @Column(name = "like_count", insertable = false, updatable = false,
            columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long likeCount;

    /**
     * 回复数, 由 {@code review_reply} 的行数冗余而来(V8)。三个约束与 {@link #likeCount}
     * **逐条相同**, 理由也一样(见上面那段的完整说明), 这里只说它与赞数的两处差别:
     *
     * <p>一是写入路径是 {@code ReviewRepository.increment/decrementReplyCount}, 与赞数
     * 那两条是并列的四条 JPQL, 不存在第二条路径。
     *
     * <p>二是这个数是**删出来的**, 而赞数是点出来的: 删一条回复要减一, 而删一条评论会
     * 连带删掉它下面的全部回复 —— 后者减的是"这条评论的回复数", 而那条评论本身正在被
     * 删除, 于是那个计数已经没有读者了。所以删评论的路径**不需要**去动任何 reply_count,
     * 库级的 CASCADE 把子行带走就够了。这一点容易反着想错(去"先算出有几条回复再减"),
     * 那会多出几条毫无意义的查询。
     */
    @Column(name = "reply_count", insertable = false, updatable = false,
            columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long replyCount;

    /** 两条时间戳取自同一次 {@code now()} —— 分两次调在纳秒级时钟上会落进不同的微秒,
     *  插入的行看着就像"被编辑过"。完整理由见 {@link ReviewReply} 的同名方法。 */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

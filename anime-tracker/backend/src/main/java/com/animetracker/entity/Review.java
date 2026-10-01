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
     * 软删时间(V14): {@code null} = 在架上, 有值 = 已被管理员移除.
     *
     * <p><b>为什么删评论改成了打时间戳而不是删行</b> —— 管理端那几个破坏性动作里,
     * 只有它是不可逆的: 封禁能解、角色能改回来、锁能解、密码能重置, 而删掉的评论
     * 连正文都找不回来。这一列让「删」变成可撤销, 也让账本里那条 {@code REVIEW_DELETE}
     * 不再是唯一一处能证明"曾经有过这么一条评论"的地方。
     *
     * <p><b>这个字段的读者比看上去多。</b> 一条读路径漏了它, 表现是「删掉的评论又冒
     * 出来」, 而且**没有任何东西会报错** —— 所以判定收在 {@code ReviewQueries.ALIVE}
     * 与 {@code ReviewRepository.existsByIdAndDeletedAtIsNull} 两处, 每条读路径都有
     * 一条用例钉着。别在这里新增第三种写法。
     *
     * <p><b>只有管理员删是软删。</b> 作者删自己的评论仍然是硬删(一条 DELETE,
     * 库级 CASCADE 带走回复与赞) —— 自己的东西不需要审计, 删完要能重发, 而"连带删掉
     * 下面的回复"正是作者要的。所以这一列非空时, 删的人一定是管理员。
     */
    private LocalDateTime deletedAt;

    /**
     * 把这条评论撤下来的人(V14), 与 {@link #deletedAt} 同时有值或同时为空.
     *
     * <p><b>为什么是个裸 id, 而不是像 {@code ReviewReport.handler} 那样的
     * {@code @ManyToOne User}。</b> 那位是要**显示给人看**的(明细面板上「谁处理的」),
     * 所以必须 fetch; 这一位不是 —— 管理端列表刻意不显示「谁移除的」, 因为那要么多一次
     * join、要么变成每行一次懒加载(N+1, 而那一页的语句条数是有一条用例钉着的常数)。
     * 要读人名的地方是**账本**(操作日志页), 它写的时候就存了用户名快照。
     *
     * <p>库上仍然有外键(V14), 不给 CASCADE: 记的是"谁干的"这个**事实**, 那个人将来
     * 不在了也不该把这行抹掉。理由与 {@code admin_action_log.actor_id} 同源 ——
     * 只是那边连外键都不挂(账本必须活得比目标久), 这里挂在一条活着的行上, 挂得起。
     */
    private Long deletedBy;

    /**
     * 这条评论是否已被管理员移除。
     *
     * <p>给 {@code deletedAt != null} 一个名字, 是因为调用点上「是不是被移除了」比
     * 「时间戳是不是空」好读, 而这两种写法混用之后, 下一个人会开始猜它们是不是同一件事。
     */
    public boolean isRemoved() {
        return deletedAt != null;
    }

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

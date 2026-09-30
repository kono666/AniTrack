package com.animetracker.repository;

/**
 * 短评列表读路径上的 JPQL 片段 —— 取哪些列、按什么排序只此一份.
 *
 * <p>形状与 {@link UserQueries} / {@link AnimeQueries} 完全一致(包私有 final、
 * {@code static final String}、编译期 {@code +} 拼接), 理由也一样: 这些常量是在
 * {@code @Query} 注解里被拼进语句文本的, 编译期常量会被内联, 所以注解里拿到的是
 * 最终值, 不是"运行时才拼好的字符串"。口径一旦被抄成两份, 某一份少写半句时接口照样
 * 返回 200, 只是偶尔排错、分页跟着漏行 —— 而分页漏行是最难看出来的那类错误:
 * 两条一样的数据里少一条, 且只在有并列值时出现。
 *
 * <p><b>为什么这一批要把排序从仓储里搬出来</b>
 *
 * <p>改前 {@code findPageBySubjectIdWithUser} 的 ORDER BY 是直接写在注解里的
 * {@code "ORDER BY r.createdAt DESC"}。这一轮要加"按热度"排序, 于是 review 表上
 * 有了**两种**排序口径; 再让它们各自躺在注解里, 就等于把"这两种序的边界规矩"
 * 分成两处维护 —— 而它们必须一起对(见下面那条三段式)。
 *
 * <p><b>最要紧的一条规矩: 整条 ORDER BY 里不出现 NULL</b>(与 {@link AnimeQueries}
 * 类注释、{@link UserQueries} 第三条同源)。
 *
 * <p>{@code ORDER BY x DESC} 时 NULL 排哪, H2 与 PostgreSQL 的默认正好相反 ——
 * H2 把 NULL 当最小值(排最后), PG 当最大值(排最前)。而排序后面跟着 LIMIT/OFFSET,
 * 序一变, 第 2 页就会混进本该在第 1 页的行、并且漏掉几条。
 *
 * <p>短评的 {@code created_at} 是**可空**的(V1 建表时没写 NOT NULL;
 * {@code @PrePersist} 会填它, 所以只有历史行和手工插的行会是空)。所以两条排序都写成
 * 「先按有没有值分组 + 再把缺值的换成常量 + 最后用 {@code r.id} 兜底」三段。
 *
 * <p>第二键用 {@code COALESCE(r.createdAt, :epoch)} 而不是像日期那样换个空串:
 * 时间戳没有"空值"({@code UserQueries.ORDER_CREATED_DESC} 是同一处境、同一写法)。
 * {@code :epoch} 的值其实**无所谓** —— 第一键已经把缺值的行分到最后一组, 组内第二键
 * 彼此相等, 由 {@code r.id} 定序; 写 COALESCE 只是为了让"ORDER BY 里没有 NULL"
 * 这句话字面成立。
 *
 * <p><b>为什么两条排序的第三键都是 {@code r.id}。</b> 没有它, 并列的行在两次查询之间
 * 顺序不定, 而分页是在这个序上切片的。热度那条尤其需要: 没人点过赞时**所有**行的
 * {@code likeCount} 都是 0, 第一键完全并列 —— 此时整条排序退化成"按时间倒序",
 * 靠的正是第二、三键, 而 {@code id} 是主键, 天然唯一。
 */
final class ReviewQueries {

    private ReviewQueries() {
    }

    /**
     * 某部番的短评 + 作者, 一次取回; 取多少条由 Pageable 决定。
     *
     * <p>{@code JOIN FETCH r.user} 的理由写在仓储那个方法上(避免 N+1), 这里只用记住
     * 它**不会**影响分页: 对 {@code @ManyToOne} 做 fetch join, 一行短评仍然只对应
     * 一行用户, 结果集不被放大, 所以 limit 是真的下推到 SQL 的。若是集合关联,
     * Hibernate 就只能把整个结果集读进内存再切页。
     *
     * <p>参数写成 {@code :subjectId} 而不是 {@code ?1}: 这一条要跟下面两个 ORDER BY
     * 片段拼成两句不同的完整语句, 而位置参数在两条语句里的含义靠人去数, 命名参数不用。
     */
    static final String SELECT_PAGE =
            "SELECT r FROM Review r JOIN FETCH r.user WHERE r.subjectId = :subjectId";

    /**
     * 默认序: 最新在前。与改动前的 {@code ORDER BY r.createdAt DESC} 相比只多了一个
     * {@code r.id} 兜底 —— 时间戳在微秒级撞上的两行(批量灌数据时很常见)改前谁在前
     * 是不定的, 于是"第 1 页的最后一条"和"第 2 页的第一条"可能是同一行。
     */
    static final String ORDER_CREATED_DESC =
            " ORDER BY CASE WHEN r.createdAt IS NULL THEN 1 ELSE 0 END ASC,"
                    + " COALESCE(r.createdAt, :epoch) DESC, r.id ASC";

    /**
     * 热度序: 赞多的在前, 同赞数按时间倒序。
     *
     * <p><b>第一键 {@code r.likeCount} 不需要 NULL 处理</b> —— 它在 V7 里是
     * {@code NOT NULL DEFAULT 0}, 排序里不会出现 NULL。**但这只解决了第一键**:
     * 赞数并列(没人点过赞时是全部并列)之后就要比时间, 而 {@code created_at} 可空,
     * 所以第二键照样得写成上面那条三段式。只按第一键是 NOT NULL 就下结论,
     * 会把 {@link UserQueries} 那条注释里记的坑原样搬过来。
     *
     * <p><b>为什么"没人点赞"不特殊对待</b>: 第一键全为 0 时, 这条排序自然退化成
     * 按时间倒序, 与默认序给出同一批结果。不需要分支, 也不需要"热度序里过滤掉 0 赞"
     * 那种会让新评论凭空消失的规则。
     *
     * <p><b>第二键那半句只有 SQL 文本断言守得住, 这是实测过的。</b>把上面那句
     * {@code CASE WHEN r.createdAt IS NULL …} 整段抹掉之后, <b>语义用例一条都不红</b> ——
     * 热度序翻页那条断言的第一页/第二页逐行不变, 因为 H2 本来就把 NULL 当最小值排在
     * DESC 的最后, 与那个 CASE 分出来的组完全一致(同 {@link UserQueries} 里记的那条限制)。
     * 真正有差别的是 PG(DESC 下它把 NULL 当最大值排最前), 而线上 PG 不在本地验证射程内。
     * 所以唯一的哨兵是
     * {@code QueryCountIntegrationTest.hotOrderSqlIsPushedDownWithNullSafeOrdering} ——
     * 与 {@code userPageSqlIsPushedDownWithNullSafeOrdering} 是同一条理由。改这一句时
     * <b>必须跑那条形状断言</b>: "别的用例还是绿的"在这里说明不了任何事情。
     */
    static final String ORDER_HOT_DESC =
            " ORDER BY r.likeCount DESC,"
                    + " CASE WHEN r.createdAt IS NULL THEN 1 ELSE 0 END ASC,"
                    + " COALESCE(r.createdAt, :epoch) DESC, r.id ASC";

    // ==================== 拼好的完整语句 ====================

    static final String PAGE_CREATED_DESC = SELECT_PAGE + ORDER_CREATED_DESC;

    static final String PAGE_HOT_DESC = SELECT_PAGE + ORDER_HOT_DESC;
}

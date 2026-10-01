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
     * 「这条评论还在架上」—— V14 给 review 加了软删之后, **每一条读路径都要带上它**。
     *
     * <p><b>为什么值得一个常量, 而不是各处直接写 {@code r.deletedAt IS NULL}。</b>
     * 漏掉一处不会报错, 只会让一条已经被管理员移除的评论在某些界面上继续出现
     * (评论区、评分统计、个人页的评论数……), 而"少了一处过滤"这件事在代码里长得
     * 和"这里本来就该显示全部"一模一样。收成一个常量之后, 「哪些读路径带过滤」
     * 可以用一句 grep 回答, 每一条路径也各有一条用例钉着。
     *
     * <p><b>别名固定是 {@code r}。</b> 这是它唯一的形状约束 —— 这个类里所有语句的
     * review 都叫 {@code r}(包括管理端那条 {@code SELECT r FROM Review r JOIN FETCH
     * r.user u})。在别的仓储里给别的别名写同一个判断时(比如
     * {@code ReviewReportRepository.findPendingSummaries} 里的 {@code rr.review}),
     * 常量拼不进去, 只能照写 —— 那两处必须一起改, 详见各自那行注释。
     *
     * <p><b>刻意不加进 {@code WHERE_ADMIN}。</b> 管理端那六条取页与计数是**唯一**
     * 看得见被移除评论的地方: 管理员要能在列表里找到自己刚删掉的那一条并恢复它。
     * 所以 {@code ALIVE} 在管理端只出现在「只看被举报」那个开关里(见
     * {@link #FILTER_REPORTED})—— 队列说的是「还有什么要处理」, 而一条已经被移除的
     * 评论不需要处理(已经处理过了, 只是方式更重)。
     */
    static final String ALIVE = "r.deletedAt IS NULL";

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
            "SELECT r FROM Review r JOIN FETCH r.user WHERE r.subjectId = :subjectId AND " + ALIVE;

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

    // ==================== 管理端(后台评论管理) ====================
    //
    // 上面的片段服务的是「某部番的评论区」, 下面这一组服务的是「全站评论的后台列表」.
    // 两者有四条实质差别, 逐条写清楚, 因为看着很像、照着上面的写法抄会抄错.

    /**
     * 管理端取页: 全部评论 + 作者, 条件是**可选**的, 所以这条不带 WHERE.
     *
     * <p><b>差别一: {@code JOIN FETCH r.user} 必须带别名 {@code u}。</b>
     * 上面那条 {@code SELECT_PAGE} 是不带别名的 —— 它的 WHERE 只用 {@code r.subjectId},
     * 不需要引用用户的列, 所以别名没有用处。这条的关键词要
     * {@code LOWER(u.username)}, 没有别名就只能写成 {@code r.user.username}: 那是
     * <b>再拼一次 join</b>, 而 Hibernate 会不会把它复用成同一个 join 是**实现选择,
     * 不是语言保证** —— 今天复用、换个版本多一条 join, 而两条 join 的语义在
     * (对 {@code @ManyToOne}) 恰好等价, 所以错了也看不出来。
     */
    static final String SELECT_ADMIN = "SELECT r FROM Review r JOIN FETCH r.user u";

    /**
     * 关键词: 评论**正文**或作者名的包含匹配, 大小写不敏感。
     *
     * <p>不含番剧名 —— 那是拍板的选择({@code review.subject_id} 与 {@code anime}
     * 之间连外键都没有, 要搜番剧名得先拿名字反查 id 再进这条, 是另一条查询)。
     *
     * <p>{@code content} 可空(用户可以不写字只打分), 与 {@code UserQueries} 里
     * email 那半边同一条理由: {@code NULL LIKE ..} 求值为未知(按假处理), 这一行不会
     * 被错误命中, 整条 WHERE 也不会因此挂掉 —— 第二个析取项为真就短路了。
     */
    static final String FILTER_KEYWORD =
            "(:keywordPattern IS NULL"
                    + " OR LOWER(r.content)  LIKE LOWER(:keywordPattern) ESCAPE '!'"
                    + " OR LOWER(u.username) LIKE LOWER(:keywordPattern) ESCAPE '!')";

    /**
     * 评分档位: {@code [minRating, maxRating]} 闭区间, 两个一起给或一起不给。
     *
     * <p>界面上是一个三选一的下拉(差评 1–4 / 中评 5–7 / 好评 8–10)而不是
     * 1~10 的十一个选项 —— 后者没人会去点。解析成区间是在 service 里做的
     * ({@code AdminService.RatingBand}), 这里只认这两个数。
     *
     * <p>两个参数必须**同时**为空或同时有值: 只给 min 会变成"4 分以上"(把档位
     * 变成开区间), 只给 max 变成"7 分以下" —— 而列表上分不出这是"我筛错了"还是
     * "库里就这些"。所以 service 侧用一个 record 而不是两个各自可空的局部变量。
     *
     * <p>{@code rating} 是 V1 的 {@code NOT NULL}, 但这里的 {@code r.rating >= ..}
     * 仍然写成受 {@code :minRating IS NULL} 保护的形式: 保护的是"不筛"这一件事,
     * 不是 NULL 语义。
     */
    static final String FILTER_RATING_BAND =
            "(:minRating IS NULL OR (r.rating >= :minRating AND r.rating <= :maxRating))";

    /**
     * 只看有待处理举报的评论. {@code reported=true} 时生效, 其它值(包括不给)不筛.
     *
     * <p><b>为什么是 {@code EXISTS} 而不是 {@code JOIN review_report ... GROUP BY}。</b>
     * 后者会让取页那句从「{@code JOIN FETCH r.user} + LIMIT 下推」退化成
     * 「把整个结果集读进内存再切页」—— 因为 {@code GROUP BY} 之后行数不再与评论一一对应,
     * Hibernate 就无法把 {@code LIMIT} 交给数据库。那正是这一轮要消灭的毛病本身。
     * {@code EXISTS} 是半连接: 每行至多贡献一行, 分页照样下推。
     *
     * <p><b>为什么写成 {@code :reported = FALSE OR ...} 而不是 {@code :reported IS NULL OR ...}</b>
     * ——与上面两条不同, 这里也<em>可以</em>写成 {@code IS NULL}, 但本仓已经有一条既定的
     * 写法: {@code AnimeQueries} 那四个标签开关用的正是 {@code :xxxActive = FALSE OR ...},
     * 而参数是基本类型 {@code boolean}。那一条在 H2 与 PG 上都跑过, 是现成的先例;
     * 基本类型也顺带把「参数没传」这个状态从类型上消灭掉(与本类别处用小 record 而不是
     * 两个可空局部变量是同一个手法)。
     *
     * <p>只认 {@code PENDING}: 被忽略掉的举报不该继续把评论留在队列里, 否则「忽略」
     * 这个动作在界面上的效果是「点了没反应」。
     *
     * <p><b>再加上半句 {@code ALIVE}, 于是「待处理」= 状态是 PENDING **且这条评论还在
     * 架上**。</b> 这半句是 V14 补的, 而 V13 那一段注释早就把这条口径写在那儿了 ——
     * 硬删时代它靠"行都没了"天然成立, 改成软删之后必须明写出来, 否则被移除的评论会
     * 一直挂在队列里等一个永远不会来的处理。
     *
     * <p>⚠️ <b>同一件事还有另一半, 在 {@code ReviewReportRepository.findPendingSummaries}
     * 里</b> —— 管理端行上那个「被举报 N 次」的徽标走的是那条查询。两处必须一起改:
     * 只改这里, 队列干净了而列表上仍挂着徽标; 只改那里, 徽标归零了而队列里还留着行。
     * 钉住它们一致的是 {@code AdminReviewIntegrationTest} 里那条
     * 「被移除的评论既不在队列里、徽标也归零, 恢复之后两样一起回来」。
     *
     * <p>⚠️ <b>H2 上这个 {@code OR} 不会被折叠掉。</b> 应用发的是**绑定参数**,
     * H2 无法按参数值把没选中的那一支从计划里摘掉, 于是即使 {@code reported} 是 false,
     * 那半句仍然在计划里(每行一次 {@code review_report} 的索引探针 —— 走
     * {@code uk_review_report_review_reporter} 的最左前缀, 一次探针换一行,
     * 一页几十次)。线上 PG 会按参数值折掉, 没选中的组整个不出现。这是**开发档独有**的
     * 开销, 与 c78 那次「H2 消不掉 OR」是同一回事, 别拿 H2 的计划去推 PG。
     */
    static final String FILTER_REPORTED =
            "(:reported = FALSE OR (" + ALIVE + " AND EXISTS ("
                    + " SELECT 1 FROM ReviewReport rr"
                    + " WHERE rr.review.id = r.id AND rr.status = 'PENDING')))";

    /** 三个可选条件的合取. 给值才筛、不给就不筛, 理由同 {@code UserQueries.WHERE} */
    static final String WHERE_ADMIN =
            " WHERE " + FILTER_KEYWORD + " AND " + FILTER_RATING_BAND + " AND " + FILTER_REPORTED;

    /**
     * <b>差别二: 六条排序都不需要 {@code CASE WHEN .. IS NULL} 那一段, 一条都不需要。</b>
     *
     * <p>上面那两条排序(以及 {@code UserQueries} 的四条)之所以要三段式, 唯一的原因是
     * 它们排的 {@code created_at} <b>可空</b> —— 而 {@code ORDER BY x DESC} 时 NULL 排哪
     * 两个库正好相反, 后面跟着 LIMIT/OFFSET, 序一变第 2 页就会混进第 1 页的行。
     *
     * <p>这里排的三个键<b>全都非空</b>: {@code id} 是主键,
     * {@code like_count}/{@code reply_count} 是 V7/V8 建的 {@code NOT NULL DEFAULT 0}。
     * 没有 NULL ⇒ 没有方言分歧 ⇒ 不需要 {@code :epoch} 参数。这是「默认排序选 id
     * 而不是 created_at」换来的直接好处, 不是省事。
     *
     * <p><b>差别三: 第二键一律是 {@code r.id}, 方向与第一键**无关**、恒为 {@code DESC}。</b>
     * 并列是常态(没人点过赞时整表并列), 没有第二键时两条相同查询的序不定, 而分页正是
     * 在这个序上切片的。第二键的方向不跟着第一键翻: 它只是"打破并列"用的, 翻不翻它
     * 都不影响正常排序的可见结果, 恒定反而让"第 2 页接不接得上第 1 页"这件事
     * 在正序倒序下是同一条推理。
     */

    /** 默认序: 主键倒序. 见上面「时间列排序用的是 id」那段 */
    static final String ORDER_ID_DESC = " ORDER BY r.id DESC";

    /** 同上, 正序 */
    static final String ORDER_ID_ASC = " ORDER BY r.id ASC";

    /** 赞多的在前. {@code like_count} 非空, 见上面那条 */
    static final String ORDER_LIKES_DESC = " ORDER BY r.likeCount DESC, r.id DESC";

    /** 赞少的在前. 调这个序通常是为了找"没人理的评论" */
    static final String ORDER_LIKES_ASC = " ORDER BY r.likeCount ASC, r.id DESC";

    /** 回复多的在前 —— 一条挂着二十条回复的评论删掉, 带走的是一整串对话 */
    static final String ORDER_REPLIES_DESC = " ORDER BY r.replyCount DESC, r.id DESC";

    /** 同上, 正序 */
    static final String ORDER_REPLIES_ASC = " ORDER BY r.replyCount ASC, r.id DESC";

    // ==================== 拼好的管理端语句 ====================

    static final String ADMIN_ID_DESC = SELECT_ADMIN + WHERE_ADMIN + ORDER_ID_DESC;
    static final String ADMIN_ID_ASC = SELECT_ADMIN + WHERE_ADMIN + ORDER_ID_ASC;
    static final String ADMIN_LIKES_DESC = SELECT_ADMIN + WHERE_ADMIN + ORDER_LIKES_DESC;
    static final String ADMIN_LIKES_ASC = SELECT_ADMIN + WHERE_ADMIN + ORDER_LIKES_ASC;
    static final String ADMIN_REPLIES_DESC = SELECT_ADMIN + WHERE_ADMIN + ORDER_REPLIES_DESC;
    static final String ADMIN_REPLIES_ASC = SELECT_ADMIN + WHERE_ADMIN + ORDER_REPLIES_ASC;

    /**
     * 计数. 与上面六条共用同一份 {@link #WHERE_ADMIN} —— 分页与 total 不会各自漂移的原因。
     *
     * <p><b>差别四: 这条要自己带上 {@code JOIN r.user u}, 少一个别名就整个应用起不来。</b>
     *
     * <p>一开始这里写的是 {@code "SELECT COUNT(r) FROM Review r"} —— 想的是"HQL 会隐式
     * join"。<b>不会。</b>隐式 join 只在把路径写成 {@code r.user.username} 时才发生, 而
     * {@link #WHERE_ADMIN} 里那半句是 {@code u.username}: 一个从未声明过的别名,
     * Hibernate 解析不了, 报
     * {@code SemanticException: Could not interpret path expression 'u.username'} ——
     * 而这条 {@code @Query} 是在**建仓 bean 时**校验的, 于是整个 ApplicationContext 起不来,
     * 整个后端起不来。它不是"这条查询返回错结果", 是"应用根本启动不了"。
     *
     * <p><b>为什么 {@code AdminServiceReviewPageTest} 48 个用例全绿也没发现</b>: 那个类
     * 里的 {@code reviewRepository} 是 Mockito 造的, {@code @Query} 文本根本不进解析器。
     * 换句话说"单元测试全绿"在这里**一点保证都没有** —— 唯一守得住这条的是任何一条
     * {@code @SpringBootTest}(它会在起上下文时炸)。这个常量改完必须跑一遍真上下文的用例。
     *
     * <p>内连接**不会丢行**: {@code review.user_id} 是 {@code NOT NULL} + 外键, 所以
     * 不会出现"有评论但不计入总数"—— 那正是分页最常见的一种坏法(总数比实际少, 最后一页
     * 永远差几条)。也正因为不会丢行, 这里用 {@code JOIN} 而不是 {@code LEFT JOIN}。
     *
     * <p>不带 fetch: COUNT 只数行, 不需要把用户实体取回来。
     *
     * <p>参数比取页那条少: 计数不需要排序, 也就没有 {@code :epoch} 那类参数。
     * 与 {@code UserQueries.COUNT_USERS} 同一条说明。
     */
    static final String COUNT_ADMIN = "SELECT COUNT(r) FROM Review r JOIN r.user u" + WHERE_ADMIN;
}

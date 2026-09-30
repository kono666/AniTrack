package com.animetracker.repository;

/**
 * 读路径上的 JPQL 片段 —— 排序/筛选口径只此一份.
 *
 * <p><b>为什么要有这个类.</b> 这一遍把六条读路径的排序、筛选、分页从 Java 搬进 SQL,
 * 而"搬进 SQL"最容易搬歪的地方, 是同一套口径被抄成好几份: 排行榜一份、筛选页一份、
 * 按标签浏览又一份, 三份各自看着都对, 只有某一处少写半句时才露出来 —— 而那种情况下
 * 接口照样返回 200, 只是偶尔排错、分页还会跟着漏行. 所以口径在这里写一遍, 由
 * {@link AnimeRepository} 的 {@code @Query} 拼装使用.
 *
 * <p><b>为什么可以拼进 {@code @Query}.</b> {@code static final String} 加字面量拼接是
 * <b>编译期常量表达式</b>(JLS 15.29), javac 在注解处理器之前就把它折成一个字面量,
 * 所以注解里拿到的就是最终值, 不是"运行时才拼好的字符串". 代价是这些片段之间
 * 有声明顺序要求(常量变量的初始化器里不能向前引用简单名), 下面按依赖顺序排好了.
 *
 * <p><b>最要紧的一条规矩: 整条 ORDER BY 里不出现 NULL.</b>
 *
 * <p>{@code ORDER BY x DESC} 时 <b>NULL 排哪, H2 与 PostgreSQL 的默认正好相反</b>
 * —— H2 把 NULL 当最小值(排最后), PG 当最大值(排最前). 而排序后面跟着切片分页,
 * 序一变, 第 2 页就会混进本该在第 1 页的行、并且漏掉几条. 所以每个排序都是
 * 「先按有没有值分组 + 再把缺值换成常量 + 最后用 a.id 兜底」三段, 让 NULL 在
 * ORDER BY 里根本不出现, 两个库上排出来的序完全一致. 下面那些
 * {@code THEN ''} 与 {@code THEN 0} 就是为了让这句话字面成立, 不是要算出一个
 * 有意义的值.
 *
 * <p>这个坑是 {@code AnimeRankingIntegrationTest#rowsWithoutVotesSortLastAndSurvive}
 * 在 H2 上抓出来的(那条用例第一次跑就是红的). 要留意它<b>看不见 PG 那一侧</b> ——
 * H2 上全绿推不出 PG 上也对, 边界的守靠 CI 的 compose 冒烟.
 */
final class AnimeQueries {

    private AnimeQueries() {
    }

    static final String SELECT_ANIME = "SELECT a FROM Anime a";

    static final String COUNT_ANIME = "SELECT COUNT(a) FROM Anime a";

    // ==================== 可选的筛选条件 ====================
    //
    // 三个条件都是"给了值才筛". 写成 :param IS NULL OR ... 而不是在 Java 侧拼 SQL:
    // 后者会让语句与参数名一起动态生成, 于是"这条查询有没有注入面"要靠人肉审查;
    // 这里语句是编译期常量、条件值全部是 JDBC 绑定参数, 两个问题各自独立.

    /**
     * 年份: 前缀匹配, 模式串由 {@link com.animetracker.util.SearchPatterns#prefix}
     * 拼好(特殊字符已转义), 传 NULL 表示不限年份.
     *
     * <p>{@code ESCAPE '!'} 必须与模式串同进同出 —— 少了它, 模式串里的转义字符会被
     * 当成普通字符去匹配, 于是 {@code year=20%} 又变回"匹配所有 20 开头的".
     * 与 {@link AnimeRepository#searchByKeywordPattern} 是同一个约定, 完整理由见
     * {@link com.animetracker.util.SearchPatterns}.
     */
    static final String FILTER_YEAR =
            "(:yearPattern IS NULL OR a.date LIKE :yearPattern ESCAPE '!')";

    /** 季度: {@code yyyy-MM} 精确相等. 库里推不出季度的行是 NULL, 因此筛任何季度都不会命中它 */
    static final String FILTER_SEASON = "(:season IS NULL OR a.season = :season)";

    /** 状态: {@code airing} / {@code finished}, 精确相等 */
    static final String FILTER_STATUS = "(:status IS NULL OR a.status = :status)";

    /**
     * 三个可选条件的合取 —— 与改动前 {@code getFiltered} 里那三个 {@code .filter(...)}
     * 是同一个语义: 给值才筛、没给就不筛、给多个是「与」而不是「或」.
     */
    static final String FILTER_WHERE =
            " WHERE " + FILTER_YEAR + " AND " + FILTER_SEASON + " AND " + FILTER_STATUS;

    /**
     * 「已经播了」—— 播出日不晚于 {@code :today}.
     *
     * <p><b>只给「最近更新」用</b>, 没给 {@link #FILTERED_DATE} / {@link #TAGGED_DATE}
     * 那两条按日期倒序的路径用. 区别在于: 分类浏览里的「最新」只是一个排法, 未上映的
     * 作品出现在那儿不算说谎; 而「最近更新」是一个对内容做了承诺的标题 —— 第一屏摆着
     * 2029 年的电影, 用户不会认为"这站数据很全", 只会认为这站坏了.
     *
     * <p><b>{@code a.date IS NULL} 那一半不是可有可无的.</b> JPQL 里 NULL 参与的比较
     * 结果还是 NULL, 也就是**假** —— 只写 {@code a.date <= :today} 会把库里所有没有
     * 日期的行一起筛掉, 而 {@link #ORDER_DATE_DESC_NULL_LAST} 的口径明写的是
     * 「缺日期的排最后」, 不是"不要它们". 这一条留白的代价是静默少行, 而榜单少几行
     * 没有任何人会看出来.
     *
     * <p><b>比的是字符串.</b> date 列存的就是字符串, 形状是 ISO 的
     * {@code 'yyyy-MM-dd'} / {@code 'yyyy-MM'} / {@code 'yyyy'} —— 按字典序比与按时间比
     * 结果一致(短的天然排在同年更长的前缀前面). 这不是这次新引入的假设: 上面那条
     * ORDER BY 本来就靠它成立.
     */
    static final String NOT_FUTURE = "(a.date IS NULL OR a.date <= :today)";

    /**
     * "这部番挂着这几个标签里的任意一个" —— 半连接, 每部番至多出一行.
     *
     * <p><b>为什么不是顶层 JOIN {@code anime_tag}.</b> 顶层 JOIN 会让同一部番按命中的
     * 标签数出现多次(它同时挂在"百合"和"Yuri"两个名字下就出两行), 于是 {@code total}
     * 虚高, 而且 LIMIT/OFFSET 会去数这些重复行 —— 翻页时相邻两页重叠、末尾几行永远
     * 看不到. 用 {@code SELECT DISTINCT} 补救是个陷阱: PostgreSQL 要求
     * {@code SELECT DISTINCT} 的每个 {@code ORDER BY} 表达式都出现在选择列表里,
     * 而上面那些排序用的是 CASE, 不是被选中的列, 会直接报错.
     *
     * <p><b>为什么是 {@code IN} 子查询而不是等价的 {@code EXISTS}.</b> 两者语义完全相同
     * (都是半连接、每部番至多一行), 差别只在优化器怎么排这个连接 —— 而 H2 排得很不一样.
     * 原先写的是 {@code EXISTS (SELECT at.id FROM AnimeTag at WHERE at.animeId = a.id AND ...)},
     * 关联列 {@code a.id} 在外层, H2 把它排成了对 {@code anime} 的**全表扫描**:
     * 每读到一行就拿它的主键去 {@code anime_tag} 里探一次(EXPLAIN 里的
     * {@code ANIME.tableScan}, 条件里才是 {@code EXISTS(...)}). 几百行时看不出来,
     * 满库之后每次按标签浏览要探两万九千次, 而且是在二级索引与其主键之间来回
     * ({@code MVSecondaryIndex$MVStoreCursor.get → MVPrimaryIndex.getRow}),
     * 连页缓存都被冲垮 —— 实测同一份库、同一个请求 {@code /by-tag?tag=TV}:
     * 改前 0.90 s, 改后 **124.29 s**, 138 倍, 而输出一字不差. 对照组
     * {@code /filter?year=2024} 两次都是 0.14~0.19 s, 排除"机器变慢了".
     *
     * <p>写成 {@code a.id IN (...)} 之后子查询与外层不再有任何相关列, H2 可以把它整个
     * 算成一个 id 集合、再拿主键索引去配(EXPLAIN:
     * {@code PUBLIC.PRIMARY_KEY_ED6: ID IN(SELECT DISTINCT X.ANIME_ID FROM ANIME_TAG X ...)}),
     * 也就是改动前"先取一批 id 再按 id 查番剧"那条老路的代价量级.
     *
     * <p><b>这是两边优化器不一致的典型, 选型依据只有 H2 一侧.</b> 同一条 JPQL 在
     * PostgreSQL 上很可能两种写法都好, 但本机没有 PG(CI 的 compose 只种 25 行,
     * 验的是"不炸"而不是"快")—— 所以这个改动的 PG 侧属于**已知的验证空缺**,
     * 不是"验过了". 之所以仍然照 H2 选: 它是本机唯一能拿到数的那个库, 也是开发库.
     *
     * <p><b>为什么参数是 tag <em>id</em> 而不是标签名.</b> 名字先由
     * {@code TagRepository.findByNameIn} 解析成 id(那条查询走 uk_tag_name, 而且传进来的
     * 通常只有 1~3 个名字 —— 一个中文名加它的英文写法), 好处是这里省掉一次 JOIN:
     * {@code at.tag.id} 直接落在 {@code anime_tag.tag_id} 上, 走 idx_animetag_tag.
     * 反过来把名字塞进来就得在这里 JOIN tag 表, 换到的只是省一次"最多三行"的查询.
     *
     * <p><b>为什么不用空集合表达"不限标签".</b> 空 IN 列表在 H2 上是语法错误, 而
     * "不筛标签"完全可以在调用方选另一条查询来表达 —— 一个不存在的情况不该在这里编码.
     */
    static final String TAG_MATCHES =
            " a.id IN (SELECT at.animeId FROM AnimeTag at WHERE at.tag.id IN :tagIds)";

    // ==================== 排序 ====================

    /**
     * 名次升序, 没有名次的排最后.
     *
     * <p>第一键按"有没有名次"分成 0/1 两组, 组内再按名次升序; 没名次的那组第二键统一
     * 是常量 0(彼此相等, 交给第三键 a.id 定序). 不写成
     * {@code Comparator.comparingInt(a -> a.getRank() != null ? a.getRank() : 9999)} 那种
     * 哨兵值写法: 哨兵是"选一个比所有真实值都大的数", 而名次的上界由 Bangumi 决定,
     * 不是一个我们能保证的量. 分组写法不依赖任何上界.
     *
     * <p>{@code <= 0} 也算"没有名次", 与 {@link com.animetracker.util.AnimeFields#rankOf}
     * 把 {@code rank <= 0} 归一成 NULL 是同一个口径: <b>Bangumi 的 0 表示"没有这个值",
     * 不是"值为零"</b>. 那里归一过一次, 这里再兜一次是为了兼容归一之前落库的历史行.
     */
    static final String ORDER_RANK_ASC_NULL_LAST =
            " ORDER BY"
                    + " CASE WHEN a.rank IS NULL OR a.rank <= 0 THEN 1 ELSE 0 END ASC,"
                    + " CASE WHEN a.rank IS NULL OR a.rank <= 0 THEN 0 ELSE a.rank END ASC,"
                    + " a.id ASC";

    /**
     * 播出日倒序, 缺日期的排最后.
     *
     * <p>这是改动前 {@code AnimeService.DATE_DESC_UNKNOWN_LAST} 那条比较器的 SQL 版,
     * 口径必须一模一样 —— 那条注释记着它修过什么: 缺日期的行一度排在最前,
     * 首页"最近更新"打开就是一屏没有日期的番. 当时那版写的是
     * {@code nullsLast(...).compare(b, a)}, 内外两次"反过来"叠在一起, 净效果恰好与
     * 意图相反; 也刻意不写 {@code nullsLast().reversed()}, 因为 {@code reversed()}
     * 会把 null 的处理一起翻过去.
     *
     * <p>第二键用空串当缺值的替身: 空串在 {@code DESC} 下排在所有真实日期之后,
     * 正好就是"缺日期排最后". date 是 {@code yyyy-MM-dd} 这类 ASCII 串, Java 的
     * {@code String.compareTo} 与两个库的默认排序对它给出一致的次序, 所以这次搬移
     * 不改变相对次序.
     */
    static final String ORDER_DATE_DESC_NULL_LAST =
            " ORDER BY"
                    + " CASE WHEN a.date IS NULL THEN 1 ELSE 0 END ASC,"
                    + " CASE WHEN a.date IS NULL THEN '' ELSE a.date END DESC,"
                    + " a.id ASC";

    /**
     * 排行榜: 按<b>加权评分</b>倒序, 而不是评分原值.
     *
     * <p>为什么必须加权、m 与 C 为什么取那两个值, 见
     * {@link com.animetracker.config.RankingProperties} —— 完整推导在那里.
     * 这里只记三件与这条 SQL 本身有关的事.
     *
     * <p><b>一, 没有票的条目排在最后, 而不是被过滤掉.</b> 排序分成两级: 先按
     * "有没有票"分成 0/1 两组, 组内再按加权分倒序. 之所以不写成
     * {@code WHERE rating_count > 0}, 是因为 {@code rating_count} 允许为 NULL
     * (历史行, 以及 Bangumi 偶尔不返回 rating 块的条目), 过滤会把它们<b>悄悄删掉</b>
     * —— 而这是排行榜唯一的数据源, 一旦库里多数行都是 NULL, 榜就空了, 接口还照样
     * 返回 200. 分组排序同时保证"没票的不会霸榜"和"榜不会空", 是这两条约束里唯一
     * 都满足的写法. 把 {@code <= 0} 也算成"没有票", 与
     * {@link com.animetracker.util.AnimeFields#rankOf} 把 {@code rank <= 0} 归一成
     * NULL 是同一个口径: <b>Bangumi 的 0 表示"没有这个值", 不是"值为零"</b>.
     *
     * <p><b>一之补, 那个 {@code CASE} 在第二排序键上又写了一遍, 不是重复.</b>
     * 如果第二键直接写加权表达式, 没票的那些行算出来会是 NULL —— 而 NULL 在
     * {@code DESC} 里排哪, H2 与 PostgreSQL 的默认正好相反(见类注释). 也就是说
     * "没票的行之间谁在前"会随数据库而变, 而分页是在这个序上切片的. 写成
     * {@code CASE ... THEN 0 ELSE 加权表达式 END} 之后, 没票的行第二键统一是常量 0
     * (彼此相等, 交给 {@code a.id} 定序), 有票的行则必然大于 0 —— 整条 ORDER BY
     * 里不再出现任何 NULL, 两个库上排出来的序完全一致.
     *
     * <p><b>二, {@code * 1.0} 不是多余的.</b> {@code rating_count} 是整型, 整数除法
     * 在 H2 与 PostgreSQL 上都会截断({@code 1/201} 得 0), 那样算出来的权重恒为 0,
     * 整个表达式退化成 {@code rating} 原值 —— 也就是加权这次改动要修的那个 bug
     * 原样复活, 而且不报错、不抛异常, 只是榜首又变回那条 1 票 10 分的番. 乘 1.0
     * 把分子提成浮点, 两个参数也声明成 {@code double}, 是同一个理由. 这条有测试钉着
     * (见 {@code AnimeRankingIntegrationTest} 里"加权序与原始分序不一致"那一组).
     *
     * <p><b>三, 最后按 {@code a.id} 兜底</b>, 理由与
     * {@link AnimeRepository#searchByKeywordPattern} 里那条相同: 没有它, 同分的行
     * (最典型的就是所有"没票"的行, 它们的第二排序键是常量 0)在两次查询之间顺序不定,
     * 而分页是在这个序上切片的. id 就是 Bangumi 的 subject_id, 天然唯一.
     */
    static final String ORDER_WEIGHTED_DESC =
            " ORDER BY"
                    + " CASE WHEN a.rating IS NULL OR a.rating <= 0"
                    + "           OR a.ratingCount IS NULL OR a.ratingCount <= 0"
                    + "        THEN 1 ELSE 0 END ASC,"
                    + " CASE WHEN a.rating IS NULL OR a.rating <= 0"
                    + "           OR a.ratingCount IS NULL OR a.ratingCount <= 0"
                    + "        THEN 0"
                    + "        ELSE ((a.ratingCount * 1.0 / (a.ratingCount + :priorVotes)) * a.rating"
                    + "              + (:priorVotes * 1.0 / (a.ratingCount + :priorVotes)) * :priorScore)"
                    + " END DESC,"
                    + " a.id ASC";

    // ==================== 拼好的完整语句 ====================
    //
    // 下面是 SELECT + WHERE + ORDER BY 的成品. 拆成这几条常量而不是让仓储里各写一遍
    // 字符串拼接, 是为了让 AnimeRepository 那一侧只剩"方法名 → 用哪条语句"这一件事.

    /** 排行榜 / 无关键词浏览: 全表按加权评分, 由 Pageable 切片 */
    static final String RANKED = SELECT_ANIME + ORDER_WEIGHTED_DESC;

    /** "最近更新": **已经播出的**按播出日倒序, 由 Pageable 切片(为什么排除未来见 {@link #NOT_FUTURE}) */
    static final String LATEST = SELECT_ANIME + " WHERE " + NOT_FUTURE + ORDER_DATE_DESC_NULL_LAST;

    /** 筛选(不带标签), 默认序: 名次升序 */
    static final String FILTERED_RANK = SELECT_ANIME + FILTER_WHERE + ORDER_RANK_ASC_NULL_LAST;

    /** 筛选(不带标签), {@code sort=date} */
    static final String FILTERED_DATE = SELECT_ANIME + FILTER_WHERE + ORDER_DATE_DESC_NULL_LAST;

    /** 筛选(不带标签), {@code sort=rating}: 与排行榜同一个加权口径 */
    static final String FILTERED_RATING = SELECT_ANIME + FILTER_WHERE + ORDER_WEIGHTED_DESC;

    /** 筛选的计数. 与上面三条共用同一份 {@link #FILTER_WHERE} —— 这是分页与 total 不会各自漂移的原因 */
    static final String COUNT_FILTERED = COUNT_ANIME + FILTER_WHERE;

    /** 同上三条, 但限定在若干标签下. 按标签浏览走的也是 {@link #TAGGED_DATE}(三个筛选条件传 NULL) */
    static final String TAGGED_RANK =
            SELECT_ANIME + FILTER_WHERE + " AND " + TAG_MATCHES + ORDER_RANK_ASC_NULL_LAST;

    static final String TAGGED_DATE =
            SELECT_ANIME + FILTER_WHERE + " AND " + TAG_MATCHES + ORDER_DATE_DESC_NULL_LAST;

    static final String TAGGED_RATING =
            SELECT_ANIME + FILTER_WHERE + " AND " + TAG_MATCHES + ORDER_WEIGHTED_DESC;

    static final String COUNT_TAGGED = COUNT_ANIME + FILTER_WHERE + " AND " + TAG_MATCHES;

    /**
     * 年份下拉框的取值: date 的前四位.
     *
     * <p>改动前是"把整张表按日期读回来, 再用 Java 取 substring + distinct" —— 只为
     * 得到三十来个字符串. 这里让库做投影与去重, 只回那一列.
     *
     * <p>不在这里 ORDER BY: {@code SELECT DISTINCT} 下的排序表达式必须出现在选择列表
     * 里, 而这个表达式两边写法要一致才合法, 是个不必要的方言风险. 三十来个值的倒序
     * 在 Java 侧排, 见 {@code AnimeService#getFilterMeta}.
     *
     * <p>{@code SUBSTRING} 短于 4 位时返回原串(与改动前的
     * {@code d.length() >= 4 ? d.substring(0, 4) : d} 一致).
     */
    static final String DISTINCT_YEAR_PREFIXES =
            "SELECT DISTINCT SUBSTRING(a.date, 1, 4) FROM Anime a WHERE a.date IS NOT NULL";
}

package com.animetracker.repository;

/**
 * 用户列表读路径上的 JPQL 片段 —— 筛选与排序口径只此一份.
 *
 * <p>形状与 {@link AnimeQueries} 完全一致(包私有 final、{@code static final String}、
 * 编译期 {@code +} 拼接), 理由也一样: 口径一旦被抄成两份, 某一份少写半句时接口照样
 * 返回 200, 只是偶尔筛错、分页跟着漏行. 完整的说明看那个类的注释, 这里只记用户表
 * 特有的三条.
 *
 * <p><b>一、关键词要同时匹配用户名和邮箱, 而 email 可以是 NULL.</b> SQL 里
 * {@code NULL LIKE ..} 的结果是未知(按假处理), 所以没填邮箱的账号不会被错误命中;
 * 整条 WHERE 也不会因此挂掉 —— 第一个析取项为真就短路了.
 *
 * <p><b>二、"已锁定"不是 status 的第三个取值.</b> {@code lockedUntil} 与
 * {@code status} 是两个**正交**的字段, 一个账号可以既 DISABLED 又被锁. 界面上为了
 * 只放一个下拉, 把"已锁定"做成了一个伪值, 但落到 SQL 里它落在
 * {@link #FILTER_LOCKED} 上, 与 {@link #FILTER_STATUS} 互不叠加.
 *
 * <p><b>三、排序里不能出现 NULL</b>, 理由与 {@link AnimeQueries} 那条长注释同源
 * (H2 把 NULL 当最小值排最后, PG 在 DESC 下当最大值排最前, 序一变分页就漏行).
 * 差别只有一处: date 是字符串列, 缺值能换成空串; 时间戳没有"空值", 所以中间那一键
 * 用 {@code COALESCE(u.createdAt, :epoch)}.
 */
final class UserQueries {

    private UserQueries() {
    }

    static final String SELECT_USER = "SELECT u FROM User u";

    static final String COUNT_USER = "SELECT COUNT(u) FROM User u";

    // ==================== 可选的筛选条件 ====================

    /**
     * 关键词: 用户名**或**邮箱的包含匹配, 大小写不敏感.
     *
     * <p>模式串由 {@link com.animetracker.util.SearchPatterns#contains} 拼好
     * ({@code %}、{@code _} 与 {@code !} 都已经转义), 传 NULL 表示不筛.
     *
     * <p>{@code ESCAPE '!'} 必须与模式串同进同出 —— 少了它, {@code "%"} 会从字面量
     * 变回通配符, 搜一个 {@code %} 就命中全部用户, 而接口照样 200.
     *
     * <p>{@code LOWER(..)} 两边都包: 折叠交给数据库, 让模式串与列走**同一个库**的
     * 规则. 在 Java 里 toLowerCase 再比, 规则不一致时(土耳其语 İ 那类)会静默漏匹配.
     * 与 {@link AnimeRepository} 的 {@code searchByKeywordPattern} 是同一个约定.
     */
    static final String FILTER_KEYWORD =
            "(:keywordPattern IS NULL"
                    + " OR LOWER(u.username) LIKE LOWER(:keywordPattern) ESCAPE '!'"
                    + " OR LOWER(u.email)    LIKE LOWER(:keywordPattern) ESCAPE '!')";

    /** 角色: {@code USER} / {@code ADMIN}, 精确相等. 传 NULL 表示不筛 */
    static final String FILTER_ROLE = "(:role IS NULL OR u.role = :role)";

    /**
     * 账号状态: {@code ACTIVE} / {@code DISABLED}, 精确相等. 传 NULL 表示不筛.
     *
     * <p>{@code LOCKED} 不在这里 —— 它不是 status 的一个取值, 见 {@link #FILTER_LOCKED}.
     */
    static final String FILTER_STATUS = "(:status IS NULL OR u.status = :status)";

    /**
     * 登录锁定: 现在是否处于锁定期.
     *
     * <p><b>必须是 {@code u.lockedUntil > :now}, 不能写成 {@code u.locked}.</b>
     * 后者是实体上的 Java 谓词({@code User.isLocked()}), 不是持久化属性, JPQL 里
     * 根本不存在; 而且它的"现在"取的是 {@code LocalDateTime.now()}, 一条语句里
     * 逐行调用会得到不同的时刻.
     *
     * <p><b>也不能只判 {@code lockedUntil IS NOT NULL}。</b> 过期的锁定时间戳仍然留在
     * 字段里(解锁是定时失效, 不是定时清扫), 按非空判断会把早已自动解锁的账号筛成
     * "已锁定", 管理员就会去点一个没有意义的解锁按钮 —— 与
     * {@code AdminService} 里映射 {@code locked} 那一行是同一条规矩.
     *
     * <p>传 NULL 表示不按锁定筛.
     */
    static final String FILTER_LOCKED =
            "(:locked IS NULL OR (u.lockedUntil IS NOT NULL AND u.lockedUntil > :now))";

    /**
     * 四个可选条件的合取 —— 给值才筛、不给就不筛, 与改动前的"全量列表"语义连续.
     *
     * <p>写成 {@code :param IS NULL OR ..} 而不是在 Java 侧拼 SQL: 后者会让语句与参数名
     * 一起动态生成, "这条查询有没有注入面"就变成需要人肉审查的事. 这里语句是编译期
     * 常量、条件值全部是 JDBC 绑定参数, 两个问题各自独立.
     */
    static final String WHERE =
            " WHERE " + FILTER_KEYWORD + " AND " + FILTER_ROLE
                    + " AND " + FILTER_STATUS + " AND " + FILTER_LOCKED;

    // ==================== 排序 ====================

    /**
     * 注册时间倒序, 没有时间的排最后.
     *
     * <p>{@code created_at} 在 V1 建表里是可空的(没有 NOT NULL), 而
     * {@code ORDER BY x DESC} 时 NULL 排哪两个库正好相反, 后面还跟着 LIMIT/OFFSET ——
     * 序一变, 第 2 页就会混进本该在第 1 页的行、并漏掉几条. 所以按
     * {@link AnimeQueries} 的三段式写: 先按"有没有值"分成 0/1 两组, 组内再排,
     * 最后用 {@code u.id} 兜底.
     *
     * <p>第二键用 {@code COALESCE(u.createdAt, :epoch)} 而不是像日期那条换个空串:
     * 时间戳没有"空值". {@code :epoch} 的值其实**无所谓** —— 第一键已经把缺值的行
     * 分到最后一组, 组内第二键彼此相等, 由 {@code u.id} 定序; 写 COALESCE 只是为了让
     * "ORDER BY 里没有 NULL" 这句话字面成立.
     */
    static final String ORDER_CREATED_DESC =
            " ORDER BY CASE WHEN u.createdAt IS NULL THEN 1 ELSE 0 END ASC,"
                    + " COALESCE(u.createdAt, :epoch) DESC, u.id ASC";

    /** 同上, 正序 */
    static final String ORDER_CREATED_ASC =
            " ORDER BY CASE WHEN u.createdAt IS NULL THEN 1 ELSE 0 END ASC,"
                    + " COALESCE(u.createdAt, :epoch) ASC, u.id ASC";

    /**
     * 用户名升序.
     *
     * <p>{@code username} 是 NOT NULL(V1 的 {@code VARCHAR(50) NOT NULL}), 没有缺值,
     * 所以不需要三段式. 仍然带 {@code u.id} 兜底 —— 唯一索引其实兜不到什么, 但两条
     * 排序形状写得一致, 下一个人加列时不会以为"这里可以省".
     */
    static final String ORDER_USERNAME_ASC = " ORDER BY u.username ASC, u.id ASC";

    /** 同上, 倒序 */
    static final String ORDER_USERNAME_DESC = " ORDER BY u.username DESC, u.id ASC";

    // ==================== 拼好的完整语句 ====================

    static final String PAGE_CREATED_DESC = SELECT_USER + WHERE + ORDER_CREATED_DESC;
    static final String PAGE_CREATED_ASC = SELECT_USER + WHERE + ORDER_CREATED_ASC;
    static final String PAGE_USERNAME_ASC = SELECT_USER + WHERE + ORDER_USERNAME_ASC;
    static final String PAGE_USERNAME_DESC = SELECT_USER + WHERE + ORDER_USERNAME_DESC;

    /**
     * 计数. 与上面四条共用同一份 {@link #WHERE} —— 这是分页与 total 不会各自漂移的原因.
     *
     * <p>它比取页少一个 {@code :epoch}: 计数不需要排序. 参数名的差异由
     * {@code UserRepository} 那两个方法签名各自声明, 不是漏写.
     */
    static final String COUNT_USERS = COUNT_USER + WHERE;
}

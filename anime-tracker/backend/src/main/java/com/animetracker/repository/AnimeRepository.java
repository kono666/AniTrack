package com.animetracker.repository;

import com.animetracker.entity.Anime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AnimeRepository extends JpaRepository<Anime, Integer> {
    List<Anime> findByOrderByRankAsc();
    List<Anime> findByOrderByDateDesc();

    /**
     * 排行榜: 按**加权评分**倒序, 而不是评分原值.
     *
     * <p>为什么必须加权、m 与 C 为什么取那两个值, 见
     * {@link com.animetracker.config.RankingProperties} —— 完整推导在那里.
     * 这里只记三件与这条 SQL 本身有关的事.
     *
     * <p><b>一, 没有票的条目排在最后, 而不是被过滤掉.</b> 排序分成两级: 先按
     * "有没有票"分成 0/1 两组, 组内再按加权分倒序. 之所以不写成
     * {@code WHERE rating_count > 0}, 是因为 {@code rating_count} 允许为 NULL
     * (历史行, 以及 Bangumi 偶尔不返回 rating 块的条目), 过滤会把它们**悄悄删掉**
     * —— 而这是排行榜唯一的数据源, 一旦库里多数行都是 NULL, 榜就空了, 接口还照样
     * 返回 200. 分组排序同时保证"没票的不会霸榜"和"榜不会空", 是这两条约束里唯一
     * 都满足的写法. 把 {@code <= 0} 也算成"没有票", 与
     * {@link com.animetracker.util.AnimeFields#rankOf} 把 {@code rank <= 0} 归一成
     * NULL 是同一个口径: <b>Bangumi 的 0 表示"没有这个值", 不是"值为零"</b>.
     *
     * <p><b>一之补, 那个 {@code CASE} 在第二排序键上又写了一遍, 不是重复.</b>
     * 如果第二键直接写加权表达式, 没票的那些行算出来会是 NULL —— 而
     * <b>NULL 在 {@code DESC} 里排哪, H2 与 PostgreSQL 的默认正好相反</b>
     * (H2 把 NULL 当最小值, PG 当最大值; 与
     * {@code AnimeService#getByTags} 那段注释警告的是同一件事). 也就是说"没票的行
     * 之间谁在前"会随数据库而变, 而分页是在这个序上切片的. 写成
     * {@code CASE ... THEN 0 ELSE 加权表达式 END} 之后, 没票的行第二键统一是常量 0
     * (彼此相等, 交给 {@code a.id} 定序), 有票的行则必然大于 0 —— 整条 ORDER BY
     * 里不再出现任何 NULL, 两个库上排出来的序完全一致. 这个坑是
     * {@code AnimeRankingIntegrationTest#rowsWithoutVotesSortLastAndSurvive} 在 H2 上
     * 抓出来的.
     *
     * <p><b>二, {@code * 1.0} 不是多余的.</b> {@code rating_count} 是整型, 整数除法
     * 在 H2 与 PostgreSQL 上都会截断({@code 1/201} 得 0), 那样算出来的权重恒为 0,
     * 整个表达式退化成 {@code rating} 原值 —— 也就是这次要修的那个 bug 原样复活,
     * 而且不报错、不抛异常, 只是榜首又变回那条 1 票 10 分的番. 乘 1.0 把分子提成
     * 浮点, 两个参数也声明成 {@code double}, 是同一个理由. 这条有测试钉着
     * (见 {@code AnimeRankingIntegrationTest} 里"加权序与原始分序不一致"那一组).
     *
     * <p><b>三, 最后按 {@code a.id} 兜底</b>, 理由与
     * {@link #searchByKeywordPattern} 里那条相同: 没有它, 同分的行(最典型的就是
     * 所有"没票"的行, 它们的第二排序键是 NULL)在两次查询之间顺序不定, 而分页是
     * 在这个序上切片的. id 就是 Bangumi 的 subject_id, 天然唯一, 不必再加 tiebreaker.
     */
    @Query("""
            SELECT a FROM Anime a
             ORDER BY
               CASE WHEN a.rating IS NULL OR a.rating <= 0
                      OR a.ratingCount IS NULL OR a.ratingCount <= 0
                    THEN 1 ELSE 0 END ASC,
               CASE WHEN a.rating IS NULL OR a.rating <= 0
                      OR a.ratingCount IS NULL OR a.ratingCount <= 0
                    THEN 0
                    ELSE ((a.ratingCount * 1.0 / (a.ratingCount + :priorVotes)) * a.rating
                          + (:priorVotes * 1.0 / (a.ratingCount + :priorVotes)) * :priorScore)
               END DESC,
               a.id ASC
            """)
    List<Anime> findByWeightedScoreDesc(@Param("priorVotes") double priorVotes,
                                        @Param("priorScore") double priorScore);

    /**
     * 按关键词找番剧: title / title_cn / aliases 三列任一命中, 大小写不敏感.
     *
     * <p>参数是**拼好的 LIKE 模式**(含首尾 %, 特殊字符已用 {@code !} 转义), 不是原始
     * 关键词 —— JPQL 里没有任何字符串函数能把 % 和 _ 转义掉, 只能在 Java 侧拼好再传进来
     * (见 {@link com.animetracker.util.SearchPatterns#contains}). 方法名带 Pattern 就是
     * 为了让这件事在调用处一眼可见, 免得有人直接把用户输入塞进来.
     *
     * <p>为什么 aliases 也参与匹配: Bangumi 的罗马音/英文/其它地区译名都在 infobox 的
     * 「别名」里, 用户搜 "EVA" 时命中的是它而不是日文原名(见 V5 脚本与
     * {@link com.animetracker.util.AnimeAliases}). 少了这一列, 回源拿到的数据落库之后
     * 依然搜不出来 —— 这正是改动前那个"数据在库里、界面是空的"的 bug.
     *
     * <p>为什么 LOWER() 包在**两边**: H2 的 MODE=MySQL 并不会让 LIKE 变成大小写不敏感
     * (那件事由独立的 SET IGNORECASE 控制, 与 MODE 无关), PostgreSQL 更是天生区分大小写.
     * 不包就没有大小写不敏感可言. 包在参数侧而不是在 Java 里 toLowerCase, 是为了让模式串
     * 与列用**同一个库**的规则折叠 —— 两边规则不一致时(土耳其语 İ 那类)会静默漏匹配.
     *
     * <p>为什么 ESCAPE 用 {@code !} 而不是反斜杠: 反斜杠是 HQL 字面量、H2 的 LIKE 默认
     * 转义字符、PG 的 LIKE 默认转义字符三套规则的交汇点, 顺着它走就是顺着 Hibernate 的
     * 版本行为走. 详见 {@link com.animetracker.util.SearchPatterns}.
     *
     * <p>为什么必须有 ORDER BY: 没有它时 H2/PG 返回的是物理行序, 而分页是拿
     * {@code subList} 在**本地累计前缀**上切的 —— 序一变, "第 2 页"里就会混进本该在第 1 页
     * 的行、并且漏掉几条(回源落库对同一批行做 UPDATE, PG 上 UPDATE 可能把行搬到页面末尾).
     * 按 id 排是确定的, 且 id 就是 Bangumi 的 subject_id, 天然唯一, 不需要再加 tiebreaker.
     */
    @Query("""
            SELECT a FROM Anime a
             WHERE LOWER(a.titleCn) LIKE LOWER(:pattern) ESCAPE '!'
                OR LOWER(a.title)   LIKE LOWER(:pattern) ESCAPE '!'
                OR LOWER(a.aliases) LIKE LOWER(:pattern) ESCAPE '!'
             ORDER BY a.id
            """)
    List<Anime> searchByKeywordPattern(String pattern);

    List<Anime> findBySeason(String season);
}

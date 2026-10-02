package com.animetracker.repository;

import com.animetracker.entity.Anime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 番剧表的读路径.
 *
 * <p><b>语句本身都在 {@link AnimeQueries} 里</b>, 这个接口只负责"哪个方法用哪条语句".
 * 排序口径(尤其是 NULL 排哪)在那边只写一遍, 理由见那个类的注释 —— 这里重复一遍
 * 最容易出的事: 同一个口径被抄成三份, 某一份少了半个 CASE, 接口照样返回 200,
 * 只是偶尔排错、分页跟着漏行.
 *
 * <p><b>返回 {@code List<Anime>} 而不是 {@code Page<Anime>} 是刻意的.</b> 返回
 * {@code Page} 会让 Spring Data 顺着方法名再发一条 count 查询, 而我们要的 count
 * 必须由 {@link AnimeQueries#FILTER_WHERE} 同一份 WHERE 拼出来 —— 否则"这一页是谁"
 * 与"一共有多少条"来自两套各自演化的条件, 迟早对不上. 所以取页与计数是两条显式的
 * 方法, 由 service 配对调用.
 *
 * <p>{@code Pageable} 一律传不带 {@code Sort} 的 {@code PageRequest}: 排序写在查询里,
 * 再带一个 Sort 会被拼成第二段 ORDER BY.
 */
public interface AnimeRepository extends JpaRepository<Anime, Integer> {

    /**
     * 排行榜 / 无关键词浏览: 按<b>加权评分</b>倒序取一页.
     *
     * <p>排序表达式与那三件必须留意的事(没票的排最后而不是被过滤掉、{@code * 1.0}
     * 不是多余的、{@code a.id} 兜底)都写在 {@link AnimeQueries#ORDER_WEIGHTED_DESC} 上.
     *
     * <p>传 {@code Pageable.unpaged()} 就是"整张榜", {@code AnimeRankingIntegrationTest}
     * 用的就是那种 —— 它要断言的是相对次序, 但需要看得见整张榜.
     */
    @Query(AnimeQueries.RANKED)
    List<Anime> findRankedByWeightedScore(@Param("priorVotes") double priorVotes,
                                          @Param("priorScore") double priorScore,
                                          Pageable pageable);

    /**
     * 最近更新的前几部: 按播出日倒序, 缺日期的排最后, 且**只取已经播出的**.
     *
     * <p>排序口径见 {@link AnimeQueries#ORDER_DATE_DESC_NULL_LAST},
     * "不晚于 today"见 {@link AnimeQueries#NOT_FUTURE}.
     *
     * <p>为什么要排除未来: 库里混着**还没上映**的条目, 来源是
     * {@code AnimeService#refreshLatestFromApi} 搜的关键词「剧场版」—— Bangumi 上
     * 一大批已定档未开播的电影, 日期是 2027/2028/2029. 按播出日倒序, 这批"最远的
     * 未来"就顶在了「最近更新」的第一屏; 而它们恰好是日期最大的一批, 所以只要有一部
     * 落进库里, 这一格的内容就再也不会变了.
     *
     * <p>顺带解开的死结: {@code AnimeService#needsRefresh} 读的正是这条查询的第一行,
     * 拿它判断"这批数据是不是旧到该回源了". 第一行是 2029 时, 那个判断(是否早于
     * 三个月前)永远为假 —— 回源从此不再触发, 「最近更新」不再更新. 两件事同一个根.
     */
    @Query(AnimeQueries.LATEST)
    List<Anime> findLatest(@Param("today") String today, Pageable pageable);

    /**
     * 年份下拉框的取值: date 的前四位, 已去重.
     *
     * <p>改前这里是"把整张表按日期读回来, 再在 Java 里取 substring + distinct" ——
     * 只为了得到三十来个字符串, 而读到的是几万个实体.
     */
    @Query(AnimeQueries.DISTINCT_YEAR_PREFIXES)
    List<String> findDistinctYearPrefixes();

    // ==================== 筛选: 不带标签 ====================
    //
    // 三个筛选条件都是可选的, 传 null 表示"不限". 年份要先经
    // SearchPatterns.prefix 拼成已转义的 LIKE 模式串再传进来 —— 方法名带 Pattern
    // 就是为了让这件事在调用处一眼可见, 免得有人把用户输入直接塞进来.
    //
    // 季度从"一个等值参数"变成了"一对区间端点"(seasonFrom / seasonTo), 因为
    // 2024-Q4 要展开成 2024-10..2024-12 —— 完整理由见 AnimeQueries.FILTER_SEASON
    // 与 SeasonRange. **两个端点要么同时为 null, 要么同时有值**: 只给 from 不给 to
    // 会让谓词在 SQL 里退化成 ">= from 且 <= NULL"(恒假), 静默筛空.

    @Query(AnimeQueries.FILTERED_RANK)
    List<Anime> findFilteredByRank(@Param("yearPattern") String yearPattern,
                                   @Param("seasonFrom") String seasonFrom,
                                   @Param("seasonTo") String seasonTo,
                                   @Param("status") String status,
                                   Pageable pageable);

    @Query(AnimeQueries.FILTERED_DATE)
    List<Anime> findFilteredByDate(@Param("yearPattern") String yearPattern,
                                   @Param("seasonFrom") String seasonFrom,
                                   @Param("seasonTo") String seasonTo,
                                   @Param("status") String status,
                                   Pageable pageable);

    /** {@code sort=rating} —— 与排行榜同一个加权口径, 不是评分原值 */
    @Query(AnimeQueries.FILTERED_RATING)
    List<Anime> findFilteredByRating(@Param("yearPattern") String yearPattern,
                                     @Param("seasonFrom") String seasonFrom,
                                     @Param("seasonTo") String seasonTo,
                                     @Param("status") String status,
                                     @Param("priorVotes") double priorVotes,
                                     @Param("priorScore") double priorScore,
                                     Pageable pageable);

    @Query(AnimeQueries.COUNT_FILTERED)
    long countFiltered(@Param("yearPattern") String yearPattern,
                       @Param("seasonFrom") String seasonFrom,
                       @Param("seasonTo") String seasonTo,
                       @Param("status") String status);

    // ==================== 筛选: 限定在若干标签下 ====================

    @Query(AnimeQueries.TAGGED_RANK)
    List<Anime> findFilteredByTagRank(@Param("yearPattern") String yearPattern,
                                      @Param("seasonFrom") String seasonFrom,
                                      @Param("seasonTo") String seasonTo,
                                      @Param("status") String status,
                                      @Param("tagIds") Collection<Long> tagIds,
                                      Pageable pageable);

    /**
     * 播出日倒序的标签查询. <b>按标签浏览({@code /api/bangumi/by-tag})走的也是这一条</b>
     * —— 三个筛选条件传 null 即可.
     *
     * <p>不给它单开一个"只差三个 null"的方法, 是因为那等于把
     * {@link AnimeQueries#ORDER_DATE_DESC_NULL_LAST} 那套口径又摆到第二个物理位置上.
     */
    @Query(AnimeQueries.TAGGED_DATE)
    List<Anime> findFilteredByTagDate(@Param("yearPattern") String yearPattern,
                                      @Param("seasonFrom") String seasonFrom,
                                      @Param("seasonTo") String seasonTo,
                                      @Param("status") String status,
                                      @Param("tagIds") Collection<Long> tagIds,
                                      Pageable pageable);

    @Query(AnimeQueries.TAGGED_RATING)
    List<Anime> findFilteredByTagRating(@Param("yearPattern") String yearPattern,
                                        @Param("seasonFrom") String seasonFrom,
                                        @Param("seasonTo") String seasonTo,
                                        @Param("status") String status,
                                        @Param("tagIds") Collection<Long> tagIds,
                                        @Param("priorVotes") double priorVotes,
                                        @Param("priorScore") double priorScore,
                                        Pageable pageable);

    @Query(AnimeQueries.COUNT_TAGGED)
    long countFilteredByTag(@Param("yearPattern") String yearPattern,
                            @Param("seasonFrom") String seasonFrom,
                            @Param("seasonTo") String seasonTo,
                            @Param("status") String status,
                            @Param("tagIds") Collection<Long> tagIds);

    // ==================== 筛选: 四组标签(分类浏览页) ====================
    //
    // 与上面那四条的区别有两处: 标签从"一组"变成"四组"(四组之间是「与」), 以及每组
    // 多了一个 `xxxActive` 布尔。两者都是必须的:
    //
    // - 四个 id 列表**每个都必须非空** —— "这一组没选"由调用方传哨兵 id 表达, 不是
    //   空集合(空 IN 在 H2 上是语法错误);
    // - 但哨兵恰好等于"这一组恒不匹配", 而"没选"要的是"这一组恒真", 所以由
    //   `xxxActive` 把这两件事分开。传 false 时那条子句整体短路。
    //
    // 完整理由见 AnimeQueries.TAG_GROUP_MATCHES。

    @Query(AnimeQueries.TAGGED_GROUP_RANK)
    List<Anime> findFilteredByTagGroupsRank(@Param("yearPattern") String yearPattern,
                                            @Param("seasonFrom") String seasonFrom,
                                            @Param("seasonTo") String seasonTo,
                                            @Param("status") String status,
                                            @Param("genreActive") boolean genreActive,
                                            @Param("genreIds") Collection<Long> genreIds,
                                            @Param("mediumActive") boolean mediumActive,
                                            @Param("mediumIds") Collection<Long> mediumIds,
                                            @Param("sourceActive") boolean sourceActive,
                                            @Param("sourceIds") Collection<Long> sourceIds,
                                            @Param("regionActive") boolean regionActive,
                                            @Param("regionIds") Collection<Long> regionIds,
                                            Pageable pageable);

    @Query(AnimeQueries.TAGGED_GROUP_DATE)
    List<Anime> findFilteredByTagGroupsDate(@Param("yearPattern") String yearPattern,
                                            @Param("seasonFrom") String seasonFrom,
                                            @Param("seasonTo") String seasonTo,
                                            @Param("status") String status,
                                            @Param("genreActive") boolean genreActive,
                                            @Param("genreIds") Collection<Long> genreIds,
                                            @Param("mediumActive") boolean mediumActive,
                                            @Param("mediumIds") Collection<Long> mediumIds,
                                            @Param("sourceActive") boolean sourceActive,
                                            @Param("sourceIds") Collection<Long> sourceIds,
                                            @Param("regionActive") boolean regionActive,
                                            @Param("regionIds") Collection<Long> regionIds,
                                            Pageable pageable);

    @Query(AnimeQueries.TAGGED_GROUP_RATING)
    List<Anime> findFilteredByTagGroupsRating(@Param("yearPattern") String yearPattern,
                                              @Param("seasonFrom") String seasonFrom,
                                              @Param("seasonTo") String seasonTo,
                                              @Param("status") String status,
                                              @Param("genreActive") boolean genreActive,
                                              @Param("genreIds") Collection<Long> genreIds,
                                              @Param("mediumActive") boolean mediumActive,
                                              @Param("mediumIds") Collection<Long> mediumIds,
                                              @Param("sourceActive") boolean sourceActive,
                                              @Param("sourceIds") Collection<Long> sourceIds,
                                              @Param("regionActive") boolean regionActive,
                                              @Param("regionIds") Collection<Long> regionIds,
                                              @Param("priorVotes") double priorVotes,
                                              @Param("priorScore") double priorScore,
                                              Pageable pageable);

    @Query(AnimeQueries.COUNT_TAGGED_GROUP)
    long countFilteredByTagGroups(@Param("yearPattern") String yearPattern,
                                  @Param("seasonFrom") String seasonFrom,
                                  @Param("seasonTo") String seasonTo,
                                  @Param("status") String status,
                                  @Param("genreActive") boolean genreActive,
                                  @Param("genreIds") Collection<Long> genreIds,
                                  @Param("mediumActive") boolean mediumActive,
                                  @Param("mediumIds") Collection<Long> mediumIds,
                                  @Param("sourceActive") boolean sourceActive,
                                  @Param("sourceIds") Collection<Long> sourceIds,
                                  @Param("regionActive") boolean regionActive,
                                  @Param("regionIds") Collection<Long> regionIds);

    // ==================== 关键词搜索 ====================

    /**
     * 按关键词找番剧: title / title_cn / aliases 三列任一命中, 大小写不敏感.
     *
     * <p><b>这一条没有分页, 是刻意的.</b> 搜索是<b>懒回源</b>的: 本地不足时按页去
     * Bangumi 拉, 而"本地现在够不够第 N 页"要拿累计到的行数来判(见
     * {@code AnimeService#searchAnime} 里那段注释). 把切片下推给 SQL 之后, 本地就
     * 只剩"这一页", 那个判据无从算起. 所以搜索保持"整份取回、在内存里按累计前缀切片",
     * 而它每一页最多回源 5 次、每次 20 条, 累计量有上界.
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

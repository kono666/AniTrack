package com.animetracker.repository;

import com.animetracker.entity.Anime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AnimeRepository extends JpaRepository<Anime, Integer> {
    List<Anime> findByOrderByRankAsc();
    List<Anime> findByOrderByRatingDesc();
    List<Anime> findByOrderByDateDesc();

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

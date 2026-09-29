package com.animetracker.service;

import com.animetracker.config.RankingProperties;
import com.animetracker.entity.Anime;
import com.animetracker.repository.AnimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排行榜那条 SQL 排的是**加权评分**, 不是评分原值.
 *
 * <p>为什么这一组必须打真库、不能像 {@code AnimeServicePagingTest} 那样 mock 仓库:
 * 这次改动把排序从 Java 搬进了 SQL(见
 * {@code AnimeRepository#findByWeightedScoreDesc}), 把仓库 mock 掉就等于把被测对象
 * 整个换掉 —— 测试会全绿, 而"排序到底对不对"一个字都没验到. 必须让 H2 真的把那条
 * 表达式执行一遍.
 *
 * <p>另一个极端是断言"整张榜恰好等于某某": 这个测试 JVM 里 {@code CachePreloader}
 * 会在后台真的调 api.bgm.tv 往同一张表插数据(理由同 {@code AnimeFilterIntegrationTest}),
 * 全局断言随时可能被它插进来的行打翻. 所以下面一律只看**自己建的那几行之间的相对次序**.
 *
 * <p>这个测试**验不到**的东西, 一并写在这里免得有人以为它盖住了: 表达式在 PostgreSQL
 * 上的行为. 这条查询里整数除法、CASE、ORDER BY 里的算术表达式三样都有, 而 H2 与 PG
 * 对它们并不保证一致 —— 与实体上那段 NUMERIC/DECIMAL 的注释是同一类风险. PG 那一侧
 * 靠 CI 的 compose 冒烟兜: 它先用 psql 种 25 行、再让 mock agent 调 get_ranking,
 * 表达式一旦在 PG 上炸, 那个断言会以"工具结果里没有番剧行"的样子红.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-ranking;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@ActiveProfiles("dev")
class AnimeRankingIntegrationTest {

    // 92xxxxxx 这一段: 与 AnimeFilterIntegrationTest 的 9xxxxxxx 错开, 也与 CI 预置
    // 数据的 990000xx 错开. Bangumi 不会返回这个区间, 所以这几行既不会被启动预加载器
    // 覆盖, 也不会混进真数据.
    private static final int TINY = 92000001;        // 1 票 10 分 —— 实测「命运之轮」的形状
    private static final int POPULAR = 92000002;     // 20000 票 9 分 —— 真热门番的形状
    private static final int NO_COUNT = 92000003;    // rating_count 为 NULL
    private static final int ZERO_COUNT = 92000004;  // rating_count 为 0
    private static final int MID = 92000005;         // 5000 票 8.5 分
    private static final int TIE_A = 92000006;
    private static final int TIE_B = 92000007;

    private static final List<Integer> MINE =
            List.of(TINY, POPULAR, NO_COUNT, ZERO_COUNT, MID, TIE_A, TIE_B);

    @Autowired
    private AnimeRepository animeRepository;

    @Autowired
    private RankingProperties rankingProperties;

    @BeforeEach
    void clearMyRows() {
        animeRepository.deleteAllById(MINE);
    }

    // ========== 这次修的那件事 ==========

    /**
     * 1 票 10 分排在 20000 票 9 分之后.
     *
     * <p>这条同时钉住了 SQL 里那个 {@code * 1.0}: 少了它, 整数除法会把
     * {@code 1/201} 算成 0, 权重恒为 0, 表达式退化成按 {@code rating} 原值排 ——
     * 于是这里会得到 [TINY, POPULAR], 断言红. 也就是说"权重被算成了 0"和
     * "忘了加权"这两种坏法是同一个断言在守.
     */
    @Test
    @DisplayName("微小样本不再霸榜: 1 票 10 分排在两万票 9 分之后")
    void tinySampleDoesNotTopTheChart() {
        save(TINY, 10.0, 1);
        save(POPULAR, 9.0, 20000);

        // 按评分原值排的话顺序恰好相反 —— 改动前榜首长那样
        assertThat(orderOf(rankedAll(), TINY, POPULAR)).containsExactly(POPULAR, TINY);
    }

    /**
     * 没有票的行排在最后, 而且**还在榜上**.
     *
     * <p>后半句是重点: "过滤掉没票的行"是修同一个问题时最顺手的写法
     * ({@code WHERE rating_count > 0}), 而它会把历史行、以及 Bangumi 没给 rating
     * 块的条目一起**静默删出榜单** —— 接口照样返回 200, 没有任何地方报错, 只是榜
     * 短了一截. 所以这里既断言次序, 也断言条数.
     *
     * <p>末了那条 {@code containsExactly} 还兼职守另一件事: NULL 排在哪儿.
     * 这条用例**第一次跑就是红的** —— 当时的写法里没票的行算出来是 NULL, 而
     * {@code DESC} 下 NULL 的位置 H2 与 PostgreSQL 默认相反, 于是两条没票的行
     * 谁在前是随库变的. 现在两条都走同一个常量排序键, 相对次序由 id 决定,
     * 因此这个断言在两个库上必须给出同一个答案.
     */
    @Test
    @DisplayName("没有票的行排在最后, 但不会被过滤掉")
    void rowsWithoutVotesSortLastAndSurvive() {
        save(NO_COUNT, 9.9, null);    // rating_count 为 NULL(历史行 / 缺 rating 块)
        save(ZERO_COUNT, 9.9, 0);     // rating_count 为 0(Bangumi 的 0 = "没有", 不是"零")
        save(MID, 7.0, 5000);

        List<Integer> order = orderOf(rankedAll(), NO_COUNT, ZERO_COUNT, MID);

        assertThat(order).as("三条都要在榜上, 一条都不能被过滤掉").hasSize(3);
        assertThat(order)
                .as("两条 9.9 分但没票的, 要排在 7.0 分但有 5000 票的那条之后")
                .containsExactly(MID, NO_COUNT, ZERO_COUNT);
    }

    /**
     * 票数远大于 m 的条目基本保持自己的位次 —— 这是"只掐小样本、不打扰热门番"
     * 那条设计的守卫.
     *
     * <p>它卡在两个反面之间: 完全不加权时 TINY 那个 10.0 分永远第一; 而如果把 m
     * 取得过大(比如上百万), 所有条目都会被压向 C, 排序又会退化成按票数排. 这里要求
     * 两万票的排在 5000 票的前面、5000 票的又排在 1 票的前面 —— 两头都被夹住.
     */
    @Test
    @DisplayName("高票条目保持自己的位次, 不被先验拉平")
    void wellVotedEntriesKeepTheirPlace() {
        save(TINY, 10.0, 1);
        save(POPULAR, 9.0, 20000);
        save(MID, 8.5, 5000);

        assertThat(orderOf(rankedAll(), TINY, POPULAR, MID))
                .containsExactly(POPULAR, MID, TINY);
    }

    // ========== 分页依赖的两个性质 ==========

    /**
     * 同分的行次序必须确定, 且两次查询一致.
     *
     * <p>为什么这条要紧: 榜是**先排序再切片**分页的, 序不定的话"第 2 页"里会混进
     * 本该在第 1 页的行、并且漏掉几条. 最典型的同分群体就是"没票"的那一批 ——
     * 它们的第二排序键是 NULL, 没有 {@code a.id} 兜底就完全看库的心情.
     */
    @Test
    @DisplayName("同分时按 id 定序, 两次查询结果一致")
    void tiesAreOrderedDeterministically() {
        save(TIE_B, 8.0, 3000);
        save(TIE_A, 8.0, 3000);   // 与上一条打分、票数完全相同

        assertThat(orderOf(rankedAll(), TIE_A, TIE_B)).containsExactly(TIE_A, TIE_B);
        assertThat(orderOf(rankedAll(), TIE_A, TIE_B))
                .as("第二次查询必须还是同一个序")
                .containsExactly(TIE_A, TIE_B);
    }

    // ========== 帮手 ==========

    /** 当前配置下的整张榜 */
    private List<Anime> rankedAll() {
        return animeRepository.findByWeightedScoreDesc(
                rankingProperties.getPriorVotes(), rankingProperties.getPriorScore());
    }

    /**
     * 把整张榜里属于我的那几行按出现次序摘出来.
     *
     * <p>只做**相对次序**断言, 不做全局断言 —— 理由见类注释里 CachePreloader 那段.
     */
    private static List<Integer> orderOf(List<Anime> ranked, int... ids) {
        Set<Integer> mine = Arrays.stream(ids).boxed().collect(Collectors.toSet());
        return ranked.stream()
                .map(Anime::getId)
                .filter(mine::contains)
                .collect(Collectors.toList());
    }

    private void save(int id, Double rating, Integer ratingCount) {
        animeRepository.saveAndFlush(Anime.builder()
                .id(id)
                .title("rank-test-" + id)
                .rating(rating)
                .ratingCount(ratingCount)
                .build());
    }
}

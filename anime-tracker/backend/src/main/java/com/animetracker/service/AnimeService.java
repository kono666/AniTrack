package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.config.RankingProperties;
import com.animetracker.dto.BangumiDTO.*;
import com.animetracker.entity.Anime;
import com.animetracker.entity.AnimeTag;
import com.animetracker.entity.Episode;
import com.animetracker.entity.Tag;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.AnimeTagRepository;
import com.animetracker.repository.EpisodeRepository;
import com.animetracker.repository.TagRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.animetracker.util.AnimeAliases;
import com.animetracker.util.AnimeFields;
import com.animetracker.util.SearchPatterns;
import com.animetracker.util.TagTranslationUtil;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnimeService {
    // 不依赖 rank 字段(API不返回)，用评分排序

    private static final Logger log = LoggerFactory.getLogger(AnimeService.class);

    /**
     * 按标签浏览一次最多返回多少条.
     *
     * <p>取 50 是为了与搜索/筛选/排行榜那几个接口的每页上限一致 —— 它们共同回答的是
     * "一个请求最多换回多少行". 前端首页的标签浏览是客户端翻页(一次拿全, 自己 slice
     * 24 条一页), 所以这个上限直接决定了它能翻几页; 真要翻得更深, 该做的是给它
     * 加上与筛选页一致的分页(见批次 6), 而不是把这里的数字调大.
     */
    public static final int BY_TAG_LIMIT = 50;

    /**
     * Agent 工具 {@code filter_anime} 一次最多返回多少条.
     *
     * <p>与 {@link #BY_TAG_LIMIT} 同一个口径(50), 理由也一样: 一个工具调用换回多少行
     * 该有个上界. 而它此前**没有**上界 —— 工具描述对外给的是"按条件筛选", 模型拿到的
     * 是全部匹配行; 无参数调用时那就是整张表.
     *
     * <p><b>这是一处行为变化, 要说准.</b> 匹配数超过 50 时, 模型现在拿到的是所选排序下
     * 的前 50 条, 而不是全部; 而 {@code count} 仍然是**真实匹配总数**(语义与改动前一致,
     * 因为改动前 {@code count = list.size()} 恰好就是全集大小). 所以要判断"是不是还有
     * 更多", 看 {@code count > list.size()} 即可 —— 这一点必须写进工具描述, 否则模型
     * 会像当年 {@code by-tag} 那样, 以为拿到的是全集.
     *
     * <p>封顶省掉的是哪一步: 结果最终会被 {@code max-tool-result-chars} 截断, 但那个
     * 截断发生在**全部行都读进来并映射完之后** —— 省不了读库与映射. 封顶省的是这一步.
     */
    public static final int FILTER_TOOL_LIMIT = 50;

    /**
     * 播出日倒序, 缺日期的排最后 —— <b>只在一条回退路径上还在用</b>.
     *
     * <p>它的 SQL 版是 {@code AnimeQueries.ORDER_DATE_DESC_NULL_LAST}, 排序/筛选/
     * 分页/标签浏览四条读路径用的都是那一条. 这里保留 Java 版, 是因为
     * {@code anime_tag} 为空(迁移未完成)时的回退分支必须按 {@code anime.tags}
     * 那一列在内存里筛, 筛完就得在内存里排. 这是一处**已知的重复**: 两种写法必须
     * 保持同一口径, 而它们分叉时不会报错, 只会让"缺日期的排在最后"在其中一条路上
     * 悄悄失效. 记在这里, 别让它变成暗的重复.
     *
     * <p>口径本身在批次 2.1 修过一次: 缺日期的行一度排在最前, 首页"最近更新"
     * 打开就是一屏没有日期的番. 当时那版写的是 {@code nullsLast(...).compare(b, a)}
     * —— 内外两次"反过来"叠在一起, 净效果恰好与意图相反; 也刻意不写成
     * {@code nullsLast().reversed()}, 因为 reversed() 会把 null 的处理一起翻过去,
     * 同一个坑再踩一遍.
     */
    private static final Comparator<Anime> DATE_DESC_UNKNOWN_LAST = (a, b) -> {
        String da = a.getDate();
        String db = b.getDate();
        if (da == null && db == null) return 0;
        if (da == null) return 1;
        if (db == null) return -1;
        return db.compareTo(da);
    };

    private final AnimeRepository animeRepository;
    private final EpisodeRepository episodeRepository;
    private final TagRepository tagRepository;
    private final AnimeTagRepository animeTagRepository;
    private final BangumiApiClient bangumiApiClient;
    private final BangumiApiProperties props;
    private final RankingProperties rankingProperties;
    private final CacheManager cacheManager;

    /**
     * 最近更新的后台回源线程.
     *
     * <p>与 {@code CachePreloader} 同一个理由: 不复用 {@code ForkJoinPool.commonPool}.
     * 这里要连着发四次请求、每次之间睡 500ms(对 Bangumi 的礼貌限速), 而 sleep 中的任务
     * 照样占着一个 worker —— 放在 commonPool 上会把别处的并行流排在后面. 单线程, 而且
     * 池里只有它一个用户.
     *
     * <p>线程设成 daemon: 回源是"有更好、没有也能照常服务"的事, 不该拖住 JVM 退出.
     */
    private final ExecutorService latestRefreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "latest-refresh");
        t.setDaemon(true);
        return t;
    });

    /** 同一时刻只允许一次回源在跑. 否则缓存一过期, 并发的请求会各排一次队 */
    private final AtomicBoolean latestRefreshInFlight = new AtomicBoolean();

    /**
     * 上次**发起**回源的时刻(毫秒), 用来做冷却.
     *
     * <p>没有它会有两条自激回路: ①对端不通时, 每个请求都会再排一次 —— 比改动前更吵,
     * 因为改动前那个(失败的)结果会被 {@code @Cacheable} 缓存住, 后续请求直接命中,
     * 根本不会再试; ②回源成功但拿到的新行仍然"旧", 于是清缓存 → 下次未命中 → 又判定
     * 要回源. 冷却把这两条都剪断.
     */
    private final AtomicLong latestRefreshStartedAt = new AtomicLong();

    /** 两次回源之间至少隔这么久 */
    private static final long LATEST_REFRESH_COOLDOWN_MS = Duration.ofMinutes(10).toMillis();

    /**
     * 回源拿到新行之后要清掉的缓存.
     *
     * <p>为什么必须清: 不清的话, 这次请求缓存住的那份旧数据要等 TTL 到点才换掉, 于是
     * "后台补到的新番"最长一小时后才出现在首页 —— 那就把改动前"用户至少能看见新数据"
     * 这个性质弄丢了(改动前是阻塞 2 秒换一次新鲜, 现在是立刻返回但要看缓存脸色).
     *
     * <p>为什么不含 {@code calendar}: 它整份来自 Bangumi 的每日放送接口, 与本地
     * {@code anime} 表里多了几行没有关系.
     */
    private static final List<String> LATEST_REFRESH_EVICTS = List.of("ranking", "latest", "tags");

    public AnimeService(AnimeRepository animeRepository,
                        EpisodeRepository episodeRepository,
                        TagRepository tagRepository,
                        AnimeTagRepository animeTagRepository,
                        BangumiApiClient bangumiApiClient,
                        BangumiApiProperties props,
                        RankingProperties rankingProperties,
                        CacheManager cacheManager) {
        this.animeRepository = animeRepository;
        this.episodeRepository = episodeRepository;
        this.tagRepository = tagRepository;
        this.animeTagRepository = animeTagRepository;
        this.bangumiApiClient = bangumiApiClient;
        this.props = props;
        this.rankingProperties = rankingProperties;
        this.cacheManager = cacheManager;
    }

    @PreDestroy
    void shutdownLatestRefreshExecutor() {
        // 用 shutdownNow 而不是 shutdown: 这个任务大多数时间在 sleep, 优雅关闭等于
        // 什么都不做. 中断掉它, sleep 会立刻抛出, 任务在几毫秒内结束.
        latestRefreshExecutor.shutdownNow();
    }

    /** {@code sort=date} 的口径标记. 与 Agent 工具 {@code filter_anime} 对外给的枚举值是同一批字符串 */
    private static final String SORT_DATE = "date";

    /** {@code sort=rating} 的口径标记. 它指的是**加权评分**, 与排行榜同一个序, 不是评分原值 */
    private static final String SORT_RATING = "rating";

    /**
     * SQL 层能接受的最大起点.
     *
     * <p>切片下推之后, 起点最终交给 {@code Query.setFirstResult(int)} —— 是个 int.
     * 而 {@code (page-1)*limit} 可以在 int 里溢出成负数(见 {@link #buildSearchResult}
     * 里那段注释记着的老 bug). 溢出之后库收到的是"从负数开始取一页", 两个库的表现
     * 既不统一, 也不报错. 所以在自己的 long 算式里先把它接住, 超了就返回空页.
     */
    private static final long MAX_SQL_OFFSET = Integer.MAX_VALUE;

    /**
     * 排行榜那条查询的一页.
     *
     * <p>抽成一个方法而不是在调用处各写一遍仓库方法, 是因为它有**两个**调用点 ——
     * {@link #getRanking} 的首次查询、以及回源补齐之后的**重查**. 两处必须是同一个序:
     * 只改前一处的话, 表现是"平时是对的, 一旦本地不够触发回源就变回按评分原值排"
     * —— 最难发现的那种, 因为它在数据少的时候不出现.
     *
     * <p>无关键词的浏览分支({@link #searchAnime})走的是**同一个序**, 但页码由调用方
     * 给, 所以走 {@link #browsePage}(那边的起点要能越界, 这里只需要第一页).
     */
    private List<Anime> rankingPage(int limit) {
        return animeRepository.findRankedByWeightedScore(
                rankingProperties.getPriorVotes(), rankingProperties.getPriorScore(),
                PageRequest.of(0, limit));
    }

    // ==================== 搜索 ====================

    /** 回源时每次问 Bangumi 要多少条的下限（沿用改动前的 20） */
    private static final int REMOTE_PAGE_SIZE_FLOOR = 20;

    /**
     * 一次搜索请求最多发几次回源请求.
     *
     * <p>必须有这个上限：page 可以从 URL 手填（Search.vue 支持 {@code ?page=N}），
     * 没有上限时 {@code page=9999} 会让一个公开 GET 打出近万次外部 HTTP 请求 ——
     * 与控制器给 limit 封顶要挡的是同一类事. 取 5 是因为正常翻页（一次往下一页）
     * 每页只需要 1 次回源，5 次足够覆盖"从第 1 页连点几下"的情形.
     */
    private static final int MAX_REMOTE_REQUESTS_PER_SEARCH = 5;

    /**
     * 搜索：先本地，本地不够再按页回源.
     *
     * <p>改动前这里有两个坑，它们是同一个 bug 的两半：
     * <ol>
     *   <li>回源只取第 1 页（{@code searchSubjects(keyword, 1, ...)}），于是搜索结果
     *       翻到第 2 页永远是空的；</li>
     *   <li>回源落库之后**又用同一条 LIKE 重查本地**，而那条 LIKE 只匹配中/日名
     *       —— 用户按别名/罗马音搜到的东西，落了库依然搜不出来，界面一片空白。</li>
     * </ol>
     * 第 2 条的另一半在 {@link AnimeRepository#searchByKeywordPattern}（新增 aliases 列）
     * 与 {@link com.animetracker.util.AnimeAliases}（把 infobox 的别名接住）里修.
     *
     * <p>为什么判据是「{@code (page+1) * limit} 条」而不是改动前的「{@code limit} 条」：
     * 本地是**累计前缀**，第 N 页的切片偏移只有在前 N 页都缓存过时才成立. 而且 total 是在
     * 这里回源时才知道的（Bangumi 返回真实总数），所以必须每页都比当前页多备一页 ——
     * 否则会出现自相矛盾的一幕：第 1 页回源后报 total=200、翻页控件显示 10 页；第 2 页本地
     * 已够 40 条不再回源、于是报 total=40、控件缩成 2 页 —— 用户卡在第 2 页出不去.
     * 多备一页之后每页恰好发 1 次回源，total 稳定为 Bangumi 的真实值.
     */
    public Map<String, Object> searchAnime(String keyword, int page, int limit) {
        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();

        if (!hasKeyword) {
            // 无关键词 = 浏览模式(排行页就是这么打的). 序与 /bangumi/ranking 一致 ——
            // 两处都是"把最好的排前面", 用两个不同的序会让同一批数据在两个页面上
            // 排出两个榜首, 看起来就像其中一个坏了。
            return browsePage(page, limit);
        }

        String kw = keyword.trim();
        String pattern = SearchPatterns.contains(kw);
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);

        List<Anime> local = animeRepository.searchByKeywordPattern(pattern);

        // Bangumi 报的匹配总数，拿不到就是 null（回源失败/无命中），此时退回本地口径
        Integer remoteTotal = null;

        long needed = (long) (safePage + 1) * safeLimit;
        if (local.size() < needed) {
            // 页码与每页条数必须用**同一个** limit：BangumiApiClient 里 offset 与 limit
            // 是共用的（offset = (page-1)*limit），这里给一个值、切片用另一个值的话，
            // 回源拿回的行与本地切片的偏移就对不上. FLOOR 只是"别问得太小"，不影响对齐.
            int pageSize = Math.max(safeLimit, REMOTE_PAGE_SIZE_FLOOR);
            int requests = 0;
            for (int p = 1; p <= safePage + 1 && local.size() < needed
                    && requests < MAX_REMOTE_REQUESTS_PER_SEARCH; p++) {
                // 这一页的额度本地已经有了就别再拉：cacheAll 的每一次 upsert 都是
                // findById + UPDATE + 重写 anime_tag 若干行，重复拉一页等于白写几百行
                if (local.size() >= (long) p * pageSize) {
                    continue;
                }
                SearchResponse resp = bangumiApiClient.searchSubjects(kw, p, pageSize);
                if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
                    // 远端没有更多了：这是翻到头，不是错误
                    break;
                }
                requests++;
                if (resp.getTotal() != null) {
                    remoteTotal = resp.getTotal();
                }
                cacheAll(resp.getData());
                local = animeRepository.searchByKeywordPattern(pattern);
            }
        }

        int total = remoteTotal != null ? Math.max(remoteTotal, local.size()) : local.size();
        return buildSearchResult(local, safePage, safeLimit, total);
    }

    // ==================== 排行 / 最新 / 浏览 ====================
    // 以下全部从本地缓存读取，启动预加载器已拉取 Top 200 到本地

    /**
     * 浏览模式的一页: 加权序, 排序与切片都在 SQL 里.
     *
     * <p>改动前这条分支是"把整张表读回来, 再在内存里 {@code subList} 切片" ——
     * 与排行榜那条读的是同一份整表数据, 只是切片发生在 Java 侧. 回填到近三万条之后,
     * 一个**不带关键词的公开 GET**(浏览页就是这么打的)就会把整张表经 JDBC 传回来、
     * 实例化成三万个实体, 只为渲染其中 20 个.
     *
     * <p>切片下推之后, {@link #buildSearchResult} 那套"越界夹取"就无从谈起了 ——
     * 它夹的是内存列表的下标, 而这里手上只有一页. 所以越界改由 offset 守卫承担:
     * 起点超过 {@link #MAX_SQL_OFFSET}(或超过总行数)时库返回空页, 语义与改前一致
     * (第 5 页在只有 40 行时就是空的), 只是不再需要先读回全部行才知道这件事.
     *
     * <p>{@code total} 报的是<b>全表行数</b>, 与改前一致: 改前那版走
     * {@code buildSearchResult(all, page, limit)} 的三参重载, 上报值就是手上全部行数.
     * 前端按 {@code ceil(total/limit)} 算翻页控件, 所以这个数必须还是全集大小.
     */
    private Map<String, Object> browsePage(int page, int limit) {
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        int total = (int) Math.min(animeRepository.count(), Integer.MAX_VALUE);

        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > MAX_SQL_OFFSET) {
            // 越界与溢出合成一条出口: 两者的结果都是"这一页没有行", 而 total 照报.
            return pageResult(Collections.emptyList(), total, safePage);
        }
        return pageResult(
                animeRepository.findRankedByWeightedScore(
                        rankingProperties.getPriorVotes(), rankingProperties.getPriorScore(),
                        PageRequest.of(safePage - 1, safeLimit)),
                total, safePage);
    }

    /**
     * 排行榜.
     *
     * <p>序是**加权评分**而不是评分原值 —— 否则"1 个人打 10 分"会稳稳压过
     * "两万个人打出 9.1", 而界面上看不出任何异常(分数确实是 10.0). 完整推导见
     * {@link RankingProperties}.
     *
     * <p>下面那次回源补齐之后的**重查**必须走同一个序, 所以两处都调
     * {@link #rankingPage} 而不是各写一遍仓库方法.
     *
     * <p>回源判据从"手上这一批够不够 20 条"换成了 {@code count() < min(limit,20)}:
     * 手上不再有"整张榜", 只有 limit 条封顶的一页, 页长与"库里有多少"不是一回事
     * (改前 {@code local.size()} 恰好等于库存量, 是那次下推之前才成立的巧合).
     */
    @Cacheable(value = "ranking", key = "'rank_' + #limit")
    public List<Anime> getRanking(int limit) {
        // PageRequest.of(0, 0) 会抛, 而 limit 由 Agent 工具与内部调用方给, 绕得过控制器的 @Min
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<Anime> local = rankingPage(limit);
        // 本地不够20条时从 API 补充热门排行
        if (animeRepository.count() < Math.min(limit, 20)) {
            try {
                SearchResponse resp = bangumiApiClient.searchSubjects("", 1, Math.max(limit, 30));
                if (resp != null && resp.getData() != null) {
                    cacheAll(resp.getData());
                    local = rankingPage(limit);
                    log.info("排行榜: API补充后共 {} 条", animeRepository.count());
                }
            } catch (Exception e) {
                log.warn("排行榜API补充失败: {}", e.getMessage());
            }
        }
        return local;
    }

    /**
     * 最近更新: 按播出日倒序取前 {@code limit} 条.
     *
     * <p>序与 {@code sort=date} 的筛选、按标签浏览共用同一套 SQL 口径
     * (见 {@code AnimeQueries.ORDER_DATE_DESC_NULL_LAST}) —— 改动前这三处各自
     * 写了一份排序, 靠注释互相提醒"要和另一处保持一致". 保持一致的正确做法是只有一份.
     *
     * <p><b>回源为什么挪到了后台.</b> 这里原来在请求线程上直接发四次请求、每次之间
     * {@code Thread.sleep(500)} —— 数据陈旧时一个用户的"看一眼首页"要被阻塞两秒以上,
     * 而那两秒里它什么都没等到(它要的那一页数据其实手上就有). 现在立刻用手上这批作答,
     * 回源交给 {@link #latestRefreshExecutor}, 拿到新行之后清掉榜单类缓存
     * (见 {@link #LATEST_REFRESH_EVICTS}), 于是<b>下一个</b>请求就能看到新数据.
     *
     * <p>代价说清楚: 触发回源的那一次请求, 看到的仍然是旧数据(改动前它看到的是新鲜的,
     * 代价是等两秒). 这是一次明确的取舍 —— 首页不再有"点一下卡两秒"的尖峰, 代价是
     * 新数据晚一个请求出现.
     *
     * <p>另外两点: 回源期间不会再排队(见 {@link #latestRefreshInFlight}), 两次回源之间
     * 有冷却(见 {@link #latestRefreshStartedAt}); 两者都不是"优化", 是让异步化不引入
     * 新问题的必要条件 —— 理由各自写在字段上.
     */
    @Cacheable(value = "latest", key = "#limit")
    public List<Anime> getLatest(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<Anime> local = animeRepository.findLatest(PageRequest.of(0, limit));
        if (needsRefresh(local, limit)) {
            scheduleLatestRefresh();
        }
        return local;
    }

    /**
     * 手上这批"最近更新"是不是旧到需要回源.
     *
     * <p>三条判据原样搬自改动前(判据本身不是这一遍要动的东西). 其中"第一行没有日期
     * 就不回源"这条<b>看着可疑</b> —— 按 {@code ORDER_DATE_DESC_NULL_LAST}, 第一行没有
     * 日期意味着整个库都没有日期, 而那种库确实该回源, 现在却永远不会. 没有顺手改,
     * 是因为它改的是"什么时候联网", 与"回源在哪个线程上"是两件事, 混在一起出问题就
     * 分不清是谁的. 记在这里.
     */
    private boolean needsRefresh(List<Anime> local, int limit) {
        if (local.isEmpty()) {
            return true;
        }
        String topDate = local.get(0).getDate();
        if (topDate == null || topDate.length() < 7) {
            return false;
        }
        try {
            YearMonth topYm = YearMonth.parse(topDate.substring(0, 7));
            return topYm.isBefore(YearMonth.now().minusMonths(3));
        } catch (Exception e) {
            // 日期格式不认识(理论上不该有): 退回按库存量判断
            return animeRepository.count() < limit;
        }
    }

    /**
     * 排一次后台回源. 冷却期内、或已经有一次在跑时, 直接返回.
     *
     * <p>顺序是"先看冷却, 再抢 {@code inFlight}"而不是反过来: 两个检查都不改状态时
     * 谁先谁后无所谓, 但冷却那一支是更常见的路径(缓存 TTL 到期后的一串请求都走它),
     * 让它先返回可以少一次 CAS.
     */
    private void scheduleLatestRefresh() {
        long now = System.currentTimeMillis();
        if (now - latestRefreshStartedAt.get() < LATEST_REFRESH_COOLDOWN_MS) {
            return;
        }
        if (!latestRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        latestRefreshStartedAt.set(now);
        latestRefreshExecutor.execute(this::refreshLatestFromApi);
    }

    /**
     * 在 {@code latest-refresh} 线程上跑: 四个关键词各拉一页, 每次之间睡 500ms.
     *
     * <p>那 500ms 是对 Bangumi 的礼貌限速, <b>不是可以省的开销</b> —— 挪到后台省掉的是
     * "让用户等", 不是"少睡一会儿".
     *
     * <p>缓存的清空放在最后, 而不是刚发完第一个请求就清: 清早了会把"当前这次请求即将
     * 写进缓存的那份结果"一起清掉, 于是它写进去、我们清掉、下次请求又写一份同样的旧
     * 数据 —— 白清. 而这一整段至少要跑两秒(四次 sleep), 当前请求早就返回并写完缓存了.
     */
    private void refreshLatestFromApi() {
        try {
            int year = Year.now().getValue();
            for (String kw : new String[]{String.valueOf(year), String.valueOf(year - 1), "新番", "剧场版"}) {
                SearchResponse resp = bangumiApiClient.searchSubjects(kw, 1, 20);
                if (resp != null && resp.getData() != null) {
                    cacheAll(resp.getData());
                }
                Thread.sleep(500);
            }
            for (String name : LATEST_REFRESH_EVICTS) {
                // 不判空: 名单在 CacheConfig 里是写死的, 对不上时 getCache 返回 null,
                // 就该在这里炸出来. 悄悄跳过等于把"缓存没清"变成一件没有症状的事.
                Cache cache = cacheManager.getCache(name);
                if (cache == null) {
                    throw new IllegalStateException("缓存 " + name + " 不在 CacheConfig 的名单里");
                }
                cache.clear();
            }
            log.info("最新: 后台补充完成, 现在共 {} 条", animeRepository.count());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("最新后台补充失败: {}", e.getMessage());
        } finally {
            latestRefreshInFlight.set(false);
        }
    }

    /**
     * 每日放送.
     *
     * <p>这里原本写着"缓存2小时 (番剧排期不会频繁变动)", 而那是**假话**: 当时项目里
     * 没有任何 {@code CacheManager} bean, 走的是 Spring Boot 默认的
     * {@code ConcurrentMapCacheManager} —— 无界、永不失效, 只靠两处
     * {@code allEntries=true} 清空. 接上 Caffeine 之后这句话才成立, 而 2 小时这个数字
     * 现在归 {@code anitrack.cache.calendar.ttl} 管(见 config/CacheProperties),
     * 不写在注释里 —— 写在注释里的数字会与配置漂移.
     */
    @Cacheable(value = "calendar", key = "'today'")
    public List<CalendarDay> getCalendar() {
        List<CalendarDay> days = bangumiApiClient.getCalendar();
        // 异步缓存日历中的动漫
        if (!days.isEmpty()) {
            for (CalendarDay day : days) {
                if (day.getItems() != null) {
                    for (CalendarItem item : day.getItems()) {
                        try {
                            upsertCalendarItem(item);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return days;
    }

    // ==================== 详情 / 剧集 ====================

    /**
     * 详情. 顺带负责给存量行补别名.
     *
     * <p>为什么"本地已有这行"还要回源一次: aliases 是后加的列(V5), 存量约 470 行的它都是
     * NULL, 而补数据的策略是懒加载 —— 其中一条路就是"用户打开了它的详情页". 改动前这里对
     * 本地已有的行直接早返回, 于是那条路是**死的**: 那些行永远不会再被回源, 别名永远补不上.
     * 加的条件是"缺别名才回源", 所以每个 subject 至多多这一次请求 —— 补上之后 aliases 非空,
     * 下次直接命中早返回.
     */
    @Transactional
    public Anime getAnimeDetail(Integer subjectId) {
        Optional<Anime> cached = animeRepository.findById(subjectId);
        if (cached.isPresent() && cached.get().getAliases() != null) {
            return cached.get();
        }
        // 本地没有，或者本地这行还没拿到过别名，调 API.
        // 走 upsertAnime 而不是自己 save: 顺手把标签也写进 anime_tag
        // (以前这条路径只存主表, 于是"点开过的番剧"在标签索引里是缺的).
        SubjectDTO dto = bangumiApiClient.getSubjectDetail(subjectId);
        if (dto != null) {
            return upsertAnime(dto);
        }
        // 回源失败时退回本地已有的那行, 而不是 null: 改动前"本地没有+回源失败"才回 null,
        // 现在多出来的这一档是"本地有、只是没别名", 那种情况下把已有的行丢掉是纯粹的倒退
        // (控制器对 null 回 404, 对一个存在的行回详情).
        return cached.orElse(null);
    }

    @Transactional
    public List<Episode> getEpisodes(Integer subjectId) {
        Optional<Anime> animeOpt = animeRepository.findById(subjectId);
        if (animeOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Anime anime = animeOpt.get();

        List<Episode> cached = episodeRepository.findByAnimeOrderByEpisodeNumAsc(anime);
        if (!cached.isEmpty()) {
            return cached;
        }

        List<EpisodeDTO> dtos = bangumiApiClient.getEpisodes(subjectId);
        if (!dtos.isEmpty()) {
            List<Episode> episodes = dtos.stream()
                    .map(d -> toEpisodeEntity(d, anime))
                    .collect(Collectors.toList());
            episodeRepository.saveAll(episodes);
            return episodes;
        }
        return Collections.emptyList();
    }

    // ==================== 筛选 / 标签 ====================

    /**
     * 筛选页的下拉框取值: 年份列表 + 状态列表.
     *
     * <p>改动前这里为了让 date 去重, 把**整张表按日期排序读回来**, 再在 Java 里
     * 取前四位、去重、倒序 —— 只为了得到三十来个字符串. 近三万行时这是一次
     * 毫无必要的整表传输 + 实例化. 现在只回一列投影, 去重交给库.
     *
     * <p>倒序仍在 Java 侧: 三十来个值的排序开销可以忽略, 而在
     * {@code SELECT DISTINCT} 下排序表达式必须出现在选择列表里, 那是个只为省这点
     * 开销而引入的方言风险(见 {@code AnimeQueries.DISTINCT_YEAR_PREFIXES})。
     */
    public Map<String, Object> getFilterMeta() {
        Map<String, Object> meta = new HashMap<>();
        List<String> years = animeRepository.findDistinctYearPrefixes().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        meta.put("years", years);
        meta.put("statuses", List.of(
                Map.of("value", "finished", "label", "已完结"),
                Map.of("value", "airing", "label", "放送中")
        ));
        return meta;
    }

    /**
     * 筛选的**完整**结果(**不截断**).
     *
     * <p><b>它不在任何请求路径上了.</b> 唯一的对外调用方曾经是 Agent 工具
     * {@code filter_anime}, 而那个工具现在走 {@link #getFilteredPage} 并封顶
     * {@link #FILTER_TOOL_LIMIT} 条 —— 因为"匹配多少就返回多少"意味着无参数调用会把
     * 整个匹配集合装进内存再交给模型. 现在只剩 {@code AnimeFilterIntegrationTest}
     * 用它, 而那条用例的理由是具体的: {@code sortsByRankWithUnrankedLast} 要验的是
     * "没名次的那条排最后<b>并且看得见</b>", 一截断它就落在截断线之外, 恰恰验不到.
     *
     * <p>它与 {@link #getFilteredPage} 不是两套筛选实现: 查询是同一条
     * (见 {@link #fetchFiltered}), 差别只在传下去的 {@code Pageable} 是
     * {@code unpaged()} 还是一页.
     */
    public List<Anime> getFiltered(String year, String season, String status, String tag, String sort) {
        List<Long> tagIds = tagIdsOf(tag);
        // 给了标签名但库里一个都没有: 不筛标签会变成"整个库都算命中", 那是这个分支
        // 能犯的最坏的一种错 —— 所以由空集合明确地表达"没有匹配".
        if (tagIds != null && tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        return fetchFiltered(SearchPatterns.prefix(year), emptyToNull(season), emptyToNull(status),
                tagIds, sort, Pageable.unpaged());
    }

    /**
     * 分页版的筛选, 返回结构与搜索接口一致: {@code {list, total, page}}.
     *
     * <p>为什么筛选也要分页: 它此前一次返回**全部**匹配行 —— 无参数时就是整张表
     * (线上 470 行, 前端 Search.vue 读的是 {@code res.data.data.list}, 会一次全渲染).
     * 数据再长下去, 一个请求就能让两边各自扛一份任意大的结果集, 与 1.3 里
     * 给 limit 封顶要挡的是同一件事.
     *
     * <p>顺序是**先筛后排再切页**, 而且这三步现在全在 SQL 里 —— 改动前它们虽然
     * 也是这个顺序, 但发生在 Java 侧的一整个列表上. 顺序本身是这个接口的语义:
     * 先切页会把"第几页"切到未筛选的集合上, 于是 total 变成页大小、后面的页少几条.
     *
     * <p>越界的页码: 起点超出总行数(或超出 SQL 能表达的 int 范围)时返回空页、
     * {@code total} 照报真实值 —— 与改动前 {@link #buildSearchResult} 的越界夹取
     * 是同一个对外行为, 只是改由 {@link #MAX_SQL_OFFSET} 守卫承担.
     */
    public Map<String, Object> getFilteredPage(String year, String season, String status,
                                               String tag, String sort, int page, int limit) {
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);

        String yearPattern = SearchPatterns.prefix(year);
        String seasonKey = emptyToNull(season);
        String statusKey = emptyToNull(status);
        List<Long> tagIds = tagIdsOf(tag);
        if (tagIds != null && tagIds.isEmpty()) {
            return pageResult(Collections.emptyList(), 0, safePage);
        }

        // count 先算: 越界页也要报真实 total, 否则前端按 total 算出来的翻页控件会
        // 凭空少掉几页(用户点不回去).
        long matched = tagIds == null
                ? animeRepository.countFiltered(yearPattern, seasonKey, statusKey)
                : animeRepository.countFilteredByTag(yearPattern, seasonKey, statusKey, tagIds);
        int total = (int) Math.min(matched, Integer.MAX_VALUE);

        long offset = (long) (safePage - 1) * safeLimit;
        if (offset >= total || offset > MAX_SQL_OFFSET) {
            return pageResult(Collections.emptyList(), total, safePage);
        }
        return pageResult(
                fetchFiltered(yearPattern, seasonKey, statusKey, tagIds, sort,
                        PageRequest.of(safePage - 1, safeLimit)),
                total, safePage);
    }

    /**
     * 筛选的取页: 六个分支 = 有没有标签 × 三种排序.
     *
     * <p>写成六个一步到位的分支而不是"先拼 WHERE 再拼 ORDER BY"的字符串加工,
     * 是因为语句必须是编译期常量才能进 {@code @Query} —— 而这条约束换来的是
     * 语句里没有任何动态拼接的部分, 条件值全部是 JDBC 绑定参数.
     *
     * <p>{@code sort} 只认 {@link #SORT_DATE} 与 {@link #SORT_RATING}, 其余(含
     * rank)一律走名次升序. <b>{@code rating} 曾经是个假选项</b>: 工具对外给的是
     * {@code rating|date|rank}, 而这里以前只区分 {@code "date"} 与"其它", 传
     * {@code rating} 会掉进 rank 分支 —— 模型要"评分最高的", 拿回按名次排的,
     * 而且看不出错(两批都是"看起来排在前面"). 现在它是加权评分, 与排行榜同一个口径.
     */
    private List<Anime> fetchFiltered(String yearPattern, String season, String status,
                                      List<Long> tagIds, String sort, Pageable pageable) {
        double priorVotes = rankingProperties.getPriorVotes();
        double priorScore = rankingProperties.getPriorScore();
        if (tagIds == null) {
            if (SORT_DATE.equals(sort)) {
                return animeRepository.findFilteredByDate(yearPattern, season, status, pageable);
            }
            if (SORT_RATING.equals(sort)) {
                return animeRepository.findFilteredByRating(
                        yearPattern, season, status, priorVotes, priorScore, pageable);
            }
            return animeRepository.findFilteredByRank(yearPattern, season, status, pageable);
        }
        if (SORT_DATE.equals(sort)) {
            return animeRepository.findFilteredByTagDate(yearPattern, season, status, tagIds, pageable);
        }
        if (SORT_RATING.equals(sort)) {
            return animeRepository.findFilteredByTagRating(
                    yearPattern, season, status, tagIds, priorVotes, priorScore, pageable);
        }
        return animeRepository.findFilteredByTagRank(yearPattern, season, status, tagIds, pageable);
    }

    /**
     * 标签名 → tag id.
     *
     * <p>传进来的名字会先做中→英翻译再一起查({@link TagTranslationUtil#reverseTranslateAll}):
     * 那几个名字本来就是同一个概念的几种写法("百合" / "Yuri"), 叫法是哪个都该命中.
     *
     * @return {@code null} 表示"没有标签这个条件"; **空集合**表示"给了标签名, 但库里
     *         一个都没解析出来" —— 这两件事的后续处理正好相反(前者完全不筛标签,
     *         后者必然空结果), 所以用 null 与空集合区分, 而不是都返回空集合.
     */
    private List<Long> tagIdsOf(String tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(tag);
        names.addAll(TagTranslationUtil.reverseTranslateAll(tag));
        return tagRepository.findByNameIn(names).stream().map(Tag::getId).collect(Collectors.toList());
    }

    /** 空串与 null 在这里是同一件事("不限"), 与改动前那些 {@code isEmpty()} 判据一致 */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    @Cacheable(value = "tags", key = "'all'")
    public List<Map<String, Object>> getAllTags() {
        // 新表有数据 → 走聚合查询
        if (animeTagRepository.existsByAnimeIdNotNull()) {
            List<Object[]> raw = tagRepository.findAllWithCount();
            return raw.stream()
                    .filter(row -> !((String) row[0]).matches("\\d{4}"))
                    .limit(30)
                    .map(row -> Map.of("name", row[0], "count", row[1]))
                    .collect(Collectors.toList());
        }
        // 新表空(迁移未完成) → 回退旧方法兜底
        List<Anime> all = animeRepository.findAll();
        Map<String, Integer> tagCount = new LinkedHashMap<>();
        for (Anime a : all) {
            if (a.getTags() != null) {
                for (String t : a.getTags().split(",")) {
                    String name = t.trim();
                    if (!name.isEmpty() && !name.matches("\\d{4}")) {
                        tagCount.merge(name, 1, Integer::sum);
                    }
                }
            }
        }
        return tagCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .map(e -> Map.of("name", (Object) e.getKey(), "count", (Object) e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 完整结果(**不截断**), 给拿到之后还要自己再筛的调用方用 ——
     * 实际上现在只剩 {@link #getFiltered}(标签那一半)与测试.
     */
    public List<Anime> getByTags(Set<String> tagNames) {
        return fetchByTags(tagNames, Pageable.unpaged());
    }

    /**
     * 按标签取番剧, 至多 {@code limit} 条(按播出日倒序取前 limit 条).
     *
     * <p>给直接对外返回的调用方用: 公开接口 {@code /api/bangumi/by-tag} 与
     * Agent 工具 {@code get_by_tag}. 这两个地方的调用方都不需要"这个标签下的全部",
     * 而"全部"是多大由数据决定 —— 标签越通用越大, 于是它同时是响应体上限和
     * 一次查询要装进内存的行数. 不封顶的话, 一个没有参数的公开 GET
     * 就能让服务端和客户端各扛一份任意大的结果集, 与 1.3 给 limit 封顶挡的是同一件事.
     *
     * <p>截断发生在**排序之后**, 所以留下的是最近的 {@code limit} 部, 而不是
     * "随便 limit 部". 这一步曾经是"先把该标签下全部行读出来排好序再切" —— 排序口径
     * 在 H2 与 PostgreSQL 上的 NULL 位置不一致(批次 2.1 修的就是这个分叉), 当时
     * 为了只留一份排序而宁可多读. 现在那道口径有了 SQL 版本, 于是切片也一起下推:
     * 读进来的就只剩这一页.
     *
     * <p>{@code limit <= 0} 当作"要 0 条"返回空列表, 不是"不封顶": 前者是调用方
     * 要的语义, 后者会让一个手滑传进来的 0 变成没有上限的查询.
     */
    public List<Anime> getByTags(Set<String> tagNames, int limit) {
        if (limit <= 0) return Collections.emptyList();
        return fetchByTags(tagNames, PageRequest.of(0, limit));
    }

    /**
     * 按标签名取番剧, 按播出日倒序, 由 {@code pageable} 决定取多少.
     *
     * <p>改前这里是两次**整表进内存**: {@code tagRepository.findAll()} 找标签名,
     * 再 {@code animeTagRepository.findAll()} 找关联, 然后在内存里 filter. 而现成的
     * {@link AnimeTagRepository#findAnimeIdsByTagId} 一直躺在那里没被用过; 旁边那句
     * "使用 anime_tag JOIN 查询, 走索引"的注释, 描述的正是这段代码从来没做过的事.
     *
     * <p>现在两步都走索引: 标签名 → tag id(uk_tag_name), tag id → 番剧行
     * ({@code EXISTS} 半连接, 走 idx_animetag_tag). 查询次数与**行数**无关,
     * 只与标签名个数有关 —— 而标签名个数由调用方给的那几个字符串决定(见
     * {@link TagTranslationUtil#reverseTranslateAll}: 一个中文名加它的英文写法,
     * 通常 1~3 个).
     *
     * <p>多个标签名之间是**并集**: 只要挂在其中任意一个标签下就算命中. 这不是
     * 随便定的 —— 传进来的那几个名字本来就是同一个概念的几种写法("百合" / "Yuri"),
     * 取交集的话它们几乎不可能同时挂在一部番上, 结果会永远是空.
     * ({@code EXISTS} 恰好天然是并集语义, 而且同一部番只出一行, 不会因为同时挂在
     * 两个名字下而在结果里出现两次 —— 改前那版要靠 {@code LinkedHashSet} 手动去重.)
     *
     * <p>查询走的是筛选页"按标签 + 按播出日"那一条(三个筛选条件传 null): 不另写一个
     * "只差三个 null"的方法, 否则"按播出日倒序、缺日期排最后"那套口径就有了第二个
     * 物理位置.
     *
     * <p>{@code anime_tag} 为空时的回退分支仍然保留整表读 + 内存过滤 —— 它只在
     * 迁移未完成时可达, 而那条路上排序用的是 Java 比较器
     * ({@link #DATE_DESC_UNKNOWN_LAST}), 与上面这条 SQL 口径是同一件事的两种写法.
     * 交叉引用记在这里, 免得它变成一处看不见的重复.
     */
    private List<Anime> fetchByTags(Set<String> tagNames, Pageable pageable) {
        if (tagNames.isEmpty()) return Collections.emptyList();

        if (animeTagRepository.existsByAnimeIdNotNull()) {
            List<Long> tagIds = tagRepository.findByNameIn(tagNames).stream()
                    .map(Tag::getId).collect(Collectors.toList());
            if (tagIds.isEmpty()) return Collections.emptyList();
            return fetchFiltered(null, null, null, tagIds, SORT_DATE, pageable);
        }

        // 新表空(迁移未完成) → 回退旧方法. 这条路上仍然是整表读 + 内存过滤,
        // 但它是"关联表里一行都没有"时的兜底, 而且改前所有请求走的都是这一档.
        // 分页在这里只能在 Java 侧切: 关联信息还在 anime.tags 那一列里, SQL 没法筛.
        List<Anime> matched = animeRepository.findAll().stream()
                .filter(a -> a.getTags() != null
                        && Arrays.stream(a.getTags().split(","))
                                 .map(String::trim)
                                 .anyMatch(tagNames::contains))
                .sorted(DATE_DESC_UNKNOWN_LAST)
                .collect(Collectors.toList());
        if (pageable.isUnpaged() || matched.size() <= pageable.getPageSize()) {
            return matched;
        }
        return new ArrayList<>(matched.subList(0, pageable.getPageSize()));
    }

    // ==================== 内部方法 ====================

    /** 保存番剧实体并同步标签到 anime_tag 关联表 */
    @Transactional
    public void saveAnimeWithTags(Anime anime) {
        anime.setCacheUpdatedAt(LocalDateTime.now());
        animeRepository.save(anime);

        // 清除旧标签关联
        animeTagRepository.deleteByAnimeId(anime.getId());

        // 写入新标签
        if (anime.getTags() != null && !anime.getTags().isBlank()) {
            for (String t : anime.getTags().split(",")) {
                String name = t.trim();
                if (name.isEmpty() || name.matches("\\d{4}")) continue;
                Tag tag = tagRepository.findByName(name)
                        .orElseGet(() -> tagRepository.save(Tag.builder().name(name).build()));
                animeTagRepository.save(AnimeTag.builder().animeId(anime.getId()).tag(tag).build());
            }
        }
    }

    private void cacheAll(List<SubjectDTO> dtos) {
        for (SubjectDTO dto : dtos) {
            if (dto.getId() == null) continue;
            upsertAnime(dto);
        }
    }

    /**
     * 把一个 Bangumi 条目写进本地库: 有就刷新, 没有就新建.
     *
     * <p>这是**唯一**一处 Anime ←→ DTO 的映射. 在这之前它有四份副本
     * ({@code toAnimeEntity}、{@code CachePreloader.toAnime}、
     * {@code DataRefreshService.toAnime} 和 {@code mergeUpdate}), 后果不是"代码重复"
     * 这么抽象 —— season/status/rank 三个字段就是因为要改四处而一处都没改,
     * 470 行数据全是 NULL, 而按这三个字段筛选的接口一直对外开着.
     * 收敛成一处之后, 再加字段只有这一个地方要动.
     *
     * <p>更新走的是"先 findById 拿到受管实体再改", 不是"造一个带 id 的游离实体
     * 丢给 save": 后者在 id 非空时走 merge, 会把实体上**没有赋值**的字段
     * 一并写成 null(merge 拷贝全部映射字段, 包括 null), 于是一次按关键词的搜索
     * 同步就能把之前辛苦填上的 season/status 抹掉. 这类问题不会报错, 只会
     * 让字段过一阵子又变回 NULL.
     */
    @Transactional
    public Anime upsertAnime(SubjectDTO dto) {
        Anime a = animeRepository.findById(dto.getId())
                .orElse(Anime.builder().id(dto.getId()).build());
        applySubject(a, dto);
        saveAnimeWithTags(a);
        return a;
    }

    /**
     * 把 DTO 上的字段拷到实体上. 每个字段都"有值才覆盖" ——
     * Bangumi 的搜索结果与详情接口返回的字段并不一致(搜索不带部分字段),
     * 无条件覆盖会在每次同步时把之前拿到的值清成 null.
     *
     * <p>末尾三个是算出来的字段, 它们不来自 DTO 的任何直接字段:
     * season 从 date 推, status 从 date + 总集数 + 今天推, rank 从 rating.rank 取
     * (0 = 未上榜, 转成 null). 推导口径见 {@link AnimeFields}.
     */
    private void applySubject(Anime a, SubjectDTO dto) {
        if (dto.getName() != null) a.setTitle(dto.getName());
        if (dto.getNameCn() != null) a.setTitleCn(dto.getNameCn());
        if (dto.getSummary() != null) a.setSummary(dto.getSummary());
        if (dto.getImages() != null) {
            String cover = dto.getImages().getLarge() != null
                    ? dto.getImages().getLarge()
                    : dto.getImages().getCommon();
            if (cover != null) a.setCoverUrl(cover);
        }
        if (dto.getDate() != null) a.setDate(dto.getDate());
        if (dto.getPlatform() != null) a.setPlatform(dto.getPlatform());
        if (dto.getTotalEpisodes() != null) a.setTotalEpisodes(dto.getTotalEpisodes());
        if (dto.getRating() != null) {
            if (dto.getRating().getScore() != null) a.setRating(dto.getRating().getScore());
            if (dto.getRating().getTotal() != null) a.setRatingCount(dto.getRating().getTotal());
            a.setRank(AnimeFields.rankOf(dto.getRating().getRank()));
        }
        if (dto.getTags() != null) {
            a.setTags(dto.getTags().stream()
                    .map(TagDTO::getName)
                    .collect(Collectors.joining(",")));
        }
        // infobox 的别名. 抽不出别名时**不覆盖** —— 搜索接口不是每个条目都带 infobox,
        // 无条件写会把详情接口先前拿到的别名抹成 null. 这条"有值才覆盖"的规矩与上面每个
        // 字段是同一个理由, 只是别名更容易踩: 它在两个接口上的有无差异比其它字段更大.
        String aliases = AnimeAliases.join(dto.getInfobox());
        if (aliases != null) {
            a.setAliases(aliases);
        }
        applyDerivedFields(a, LocalDate.now());
    }

    /**
     * 重算 season/status. rank 不在这里 —— 它不是算出来的, 只能从 Bangumi 拿.
     *
     * <p>抽出来是为了让"补齐存量数据"和"同步新数据"走同一套口径:
     * 两边各写一份的话, 补出来的值和之后同步进去的值会慢慢对不上.
     */
    private void applyDerivedFields(Anime a, LocalDate today) {
        a.setSeason(AnimeFields.seasonOf(a.getDate()));
        a.setStatus(AnimeFields.statusOf(a.getDate(), a.getTotalEpisodes(), today));
    }

    /**
     * 日历条目落库. 日历是"当前在播"的**权威**清单, 所以它比推导更可信:
     * 长连载(总集数未知)靠 date + 集数估出来会是"早已完结", 而它出现在日历里
     * 就说明还在播 —— 这里把 status 直接定成 airing.
     *
     * <p>同样修掉了另外两个被丢掉的字段: {@code air_date} 和 {@code rank}.
     * 这两个字段以前既没读也没写, 结果是所有从日历来的番剧 date 都是 NULL
     * (实测线上 470 行里有 145 行), 而 date 为空意味着 season 和 status
     * 都推不出来 —— 一个字段没写, 连带两个字段一起废掉.
     *
     * <p>已存在的行也会被刷新(以前是 {@code if (!existsById)} 直接跳过):
     * 跳过的代价是老数据永远停在"什么都没有"的状态, 只能等它碰巧被别的同步路径
     * 再捞一次.
     */
    @Transactional
    public Anime upsertCalendarItem(CalendarItem item) {
        if (item == null || item.getId() == null) {
            return null;
        }
        Anime a = animeRepository.findById(item.getId())
                .orElse(Anime.builder().id(item.getId()).build());

        if (item.getName() != null) a.setTitle(item.getName());
        if (item.getNameCn() != null) a.setTitleCn(item.getNameCn());
        if (item.getImages() != null) {
            String cover = item.getImages().getLarge() != null
                    ? item.getImages().getLarge()
                    : item.getImages().getCommon();
            if (cover != null) a.setCoverUrl(cover);
        }
        if (item.getRating() != null) {
            if (item.getRating().getScore() != null) a.setRating(item.getRating().getScore());
            if (item.getRating().getTotal() != null) a.setRatingCount(item.getRating().getTotal());
        }
        if (item.getAirDate() != null) a.setDate(item.getAirDate());
        // 只在日历真的给了这个字段时才动 rank: 日历条目有时不带 rank,
        // 那种情况是"不知道", 不是"没有排名" —— 无条件写会把上一次从详情接口
        // 拿到的名次抹成 null.
        if (item.getRank() != null) a.setRank(AnimeFields.rankOf(item.getRank()));

        applyDerivedFields(a, LocalDate.now());
        a.setStatus(AnimeFields.STATUS_AIRING);   // 在日历里 = 正在播, 覆盖推导结果

        saveAnimeWithTags(a);
        return a;
    }

    /**
     * 给存量数据补齐 season/status.
     *
     * <p>为什么需要它: 推导只在同步路径上跑, 而库里的老数据不会自己再被同步一次
     * —— 470 行里 325 行有 date, 光靠"下次同步时会填上"是等不到的.
     *
     * <p>**只读本地列, 不联外网**: season 和 status 都能从已有的 date / 总集数
     * 算出来. rank 算不出来(那是 Bangumi 的榜单名次), 只能靠同步时从
     * {@code rating.rank} 带回来 —— 所以这个方法不碰 rank, 不假装能补.
     *
     * <p>每次启动重算全部行, 而不是只补 NULL 的那些: status 会随时间变化
     * ("放送中"过几个月就该变成"已完结"), 只补 NULL 的话这些值会永远停在
     * 第一次算出来的那一刻. 重算是幂等的, 且只在值真的变了的时候才会发 UPDATE
     * (走受管实体的脏检查).
     *
     * @return 实际发生变化的行数
     */
    @Transactional
    public int backfillDerivedFields() {
        List<Anime> all = animeRepository.findAll();
        LocalDate today = LocalDate.now();
        int changed = 0;
        for (Anime a : all) {
            String season = AnimeFields.seasonOf(a.getDate());
            String status = AnimeFields.statusOf(a.getDate(), a.getTotalEpisodes(), today);
            if (Objects.equals(season, a.getSeason()) && Objects.equals(status, a.getStatus())) {
                continue;
            }
            a.setSeason(season);
            a.setStatus(status);
            changed++;
        }
        log.info("补齐推导字段: 扫描 {} 行, 变更 {} 行", all.size(), changed);
        return changed;
    }

    private Episode toEpisodeEntity(EpisodeDTO dto, Anime anime) {
        Episode ep = Episode.builder()
                .id(dto.getId())
                .anime(anime)
                .episodeNum(dto.getEp() != null ? dto.getEp().intValue() : 0)
                .title(dto.getName())
                .airdate(dto.getAirdate())
                .duration(dto.getDuration())
                .cacheUpdatedAt(LocalDateTime.now())
                .build();
        if (dto.getNameCn() != null && !dto.getNameCn().isEmpty()) {
            ep.setTitle(dto.getNameCn());
        }
        return ep;
    }

    /**
     * 组装 {@code {list, total, page}} —— 给**已经在 SQL 里切好页**的那几条读路径用.
     *
     * <p>与 {@link #buildSearchResult} 的区别就一件事: 它不切片. 那边手上的列表是
     * 全部匹配行, 切片是它的一部分职责; 这边手上只有一页, 切片已经由库做完,
     * 越界也已经在调用处拦掉(见 {@link #MAX_SQL_OFFSET}). 留着两个方法而不是让一个
     * 方法"看情况切", 是为了让"这一页还需要切吗"这件事在调用处就看得见.
     *
     * @param total 上报的匹配总数, 来自与取页**同一份 WHERE** 的 count 查询 ——
     *              这正是仓储那边不用 {@code Page<Anime>}(会有第二条自动拼出来的
     *              count)而坚持让 service 配对调用的原因.
     */
    private static Map<String, Object> pageResult(List<Anime> list, int total, int page) {
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        // 上报值不得小于手上真实有的行数: count 与取页是两次查询, 期间有写入的话
        // 这一页可能比 count 报的还长. 报小的会让用户看不到自己已经看到的那些行.
        data.put("total", Math.max(total, list.size()));
        data.put("page", page);
        return data;
    }

    /**
     * @param reportedTotal 上报给调用方的「共找到多少条」, 与下面切片的边界**不是**一回事.
     *
     *  <p>为什么要把这两个数拆开: 搜索是懒回源的, 本地只有"已经拉过的那几页", 而 Bangumi
     *  知道真实总数. 前端 Search.vue 的注释写着它就是这么理解 total 的
     *  ("它回的 total 是**全部**匹配数, 不是这一页的条数"), 翻页控件也按
     *  {@code ceil(total/20)} 决定要不要出现. 所以 total 得报真实值, 否则搜别名的第一步
     *  就只报 20 条 → 控件不渲染 → 第 2 页永远点不到.
     *
     *  <p>但切片边界只能用手上真实有的行数: 拿远端总数当边界的话, 第 5 页在本地只有 40 行
     *  时会 {@code subList(80, 100)} 直接抛 IndexOutOfBounds, 把一次搜索打成 500.
     *  两者拆开之后, 边界内的页照常返回, 超出本地已有范围的页返回空列表(优雅降级).
     */
    private Map<String, Object> buildSearchResult(List<Anime> list, int page, int limit, int reportedTotal) {
        int size = list.size();
        // 上报值不得小于手上真实有的行数: 回源失败时 reportedTotal 缺省就是 size,
        // 而远端总数偶尔会比本地匹配数小(两次查询之间数据变了), 那种情况下报小的会让
        // 用户看不到自己已经看到的那些页.
        int total = Math.max(reportedTotal, size);

        // 越界的分页参数在这里夹回合法区间, 目的是不让它走到 subList 去抛越界.
        // 控制器那层已经有 @Min/@Max, 拦的是网页来的请求; 这里管的是绕过控制器的
        // 调用方(Agent 工具、内部直接调用). 对它们来说, 一个越界的分页参数应该
        // 退化成「第一页」, 而不是把整条调用链炸成 500.
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        // 起点用 long 算: page 只封了下界, page=Integer.MAX_VALUE 时
        // (page-1)*limit 会溢出成负数, 于是又绕回 subList(负, 正) 的那个越界.
        long startL = (long) (safePage - 1) * safeLimit;
        int start = startL >= size ? size : (int) startL;
        int end = (int) Math.min(startL + safeLimit, size);
        List<Anime> pageList = start < size ? list.subList(start, end) : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", pageList);
        data.put("total", total);
        // 回报夹过之后的值: 调用方拿到的这一页确实来自 safePage 页
        data.put("page", safePage);
        return data;
    }
}

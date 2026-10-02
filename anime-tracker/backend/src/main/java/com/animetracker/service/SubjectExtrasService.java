package com.animetracker.service;

import com.animetracker.config.SubjectExtrasProperties;
import com.animetracker.dto.BangumiDTO.CharacterDTO;
import com.animetracker.dto.BangumiDTO.PersonDTO;
import com.animetracker.dto.BangumiDTO.RelatedSubjectDTO;
import com.animetracker.entity.SubjectCharacter;
import com.animetracker.entity.SubjectCharacterActor;
import com.animetracker.entity.SubjectExtras;
import com.animetracker.entity.SubjectRelation;
import com.animetracker.entity.SubjectStaff;
import com.animetracker.repository.SubjectCharacterActorRepository;
import com.animetracker.repository.SubjectCharacterRepository;
import com.animetracker.repository.SubjectExtrasRepository;
import com.animetracker.repository.SubjectRelationRepository;
import com.animetracker.repository.SubjectStaffRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 附属数据(角色 / 制作人员 / 关联条目)的读路径: 先看本地, 旧了才回源.
 *
 * <h2>为什么这个类是「按需懒回源」而不是一个定时任务</h2>
 *
 * <p>站内有两万九千多个条目, 而真正被打开过的是极少数。为全部条目预先抓一遍附属数据,
 * 等于用两万九千次上游请求去覆盖一个只有几百次会被读到的需求。所以这里与剧集那一侧
 * 同一个策略: 有人打开详情页, 我们才去取; 取回来的东西留下来给下一个人用。
 *
 * <h2>⚠️ 这个类上刻意没有 {@code @Transactional} —— 与 getAnimeDetail/getEpisodes 的偏离</h2>
 *
 * <p>{@code AnimeService.getAnimeDetail} 与 {@code getEpisodes} 都是 {@code @Transactional}
 * 的, 而它们<b>在事务里发 HTTP</b>。代价是公开端点上一次请求会占住一个数据库连接最长
 * 30–60 秒(读超时 20 秒见 {@code WebConfig}, 加上连接与解析): 上游一慢, 连接池先被
 * 占满, 而池子里的连接是**全站共用**的 —— 一个第三方服务的抖动会顺着这条路径放大成
 * 整个站点不可用。
 *
 * <p>所以这里把两件事分开: <b>HTTP 在事务外</b>(本类, 无事务), 落库由
 * {@link SubjectExtrasWriter} 在自己的事务里做。这也是为什么那个类必须是一个
 * <b>独立的 bean</b> —— 见它类注释里的那段。
 *
 * <p>取舍与残留风险, 说清楚:
 * <ul>
 *   <li><b>没有一致性快照。</b> 读到的四张表的行可能来自不同时刻的两次回源 —— 但它们是
 *       三块**互相独立**的数据(角色/人员/关联), 页面上也是三块, 不存在"角色画了、
 *       声优对不上"的窗口(那两批同属一个事务);</li>
 *   <li><b>单飞只在本进程有效。</b> 多实例部署时, 两个实例可能同时回源同一个条目,
 *       然后各自覆盖同一份上游数据。结果是多打一次上游, 不会写坏任何东西 ——
 *       因为写的内容是**上游给的**, 不是由本地状态算出来的(这也正是 delete-then-insert
 *       敢用的前提)。真要做跨实例的单飞, 那要引入分布式锁, 而这里不值得。</li>
 * </ul>
 *
 * <h2>为什么没有加第五个 Caffeine 缓存名</h2>
 *
 * <p>这一层看起来正是缓存该管的形状, 但 {@code CacheConfig} 的名单(ranking/latest/tags/
 * calendar)被 {@code CacheConfigTest} 的 {@code containsExactlyInAnyOrder} 加另外八个手工
 * {@code new ConcurrentMapCacheManager("ranking","latest","tags","calendar")} 的测试类
 * 一起钉着, 加一个名字要连改九处。而这里**已经有**一个更合适的缓存层: 数据库里的
 * marker。它有两个内存缓存给不了的性质 —— 重启不丢, 以及"取过没有"这件事在多实例之间
 * 是共享的。所以这一版不加缓存。</p>
 */
@Service
public class SubjectExtrasService {

    private final BangumiApiClient apiClient;
    private final SubjectExtrasMapper mapper;
    private final SubjectExtrasWriter writer;
    private final SubjectExtrasRepository extrasRepository;
    private final SubjectCharacterRepository characterRepository;
    private final SubjectCharacterActorRepository actorRepository;
    private final SubjectStaffRepository staffRepository;
    private final SubjectRelationRepository relationRepository;
    private final SubjectExtrasProperties props;

    /**
     * 正在回源的那些「条目 + 块」.
     *
     * <p><b>为什么是 Set&lt;String&gt; 而不是 Map&lt;String, AtomicBoolean&gt;.</b>
     * 两者的差别在<b>会留多少东西</b>: 一个条目在飞完之后条目就该消失, 而
     * {@code Map<String, AtomicBoolean>} 的写法天然会为每个**访问过的**条目留下一行
     * (值为 false 的那些没人清), 于是这张表随着站点被浏览的条目数无限长。
     * Set 的条目数与"此刻真的在飞的请求数"同阶, 天然有界。
     *
     * <p>键是 {@code 条目id:块名} —— 加块名是因为三块各自独立, 同一页的三个请求会同时
     * 到达, 只按条目 id 去重会让后到的两块白白读一次空库。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final SectionOps<CharacterDTO, CharacterBlock> characters;
    private final SectionOps<PersonDTO, List<SubjectStaff>> staff;
    private final SectionOps<RelatedSubjectDTO, List<SubjectRelation>> relations;

    public SubjectExtrasService(BangumiApiClient apiClient,
                                SubjectExtrasMapper mapper,
                                SubjectExtrasWriter writer,
                                SubjectExtrasRepository extrasRepository,
                                SubjectCharacterRepository characterRepository,
                                SubjectCharacterActorRepository actorRepository,
                                SubjectStaffRepository staffRepository,
                                SubjectRelationRepository relationRepository,
                                SubjectExtrasProperties props) {
        this.apiClient = apiClient;
        this.mapper = mapper;
        this.writer = writer;
        this.extrasRepository = extrasRepository;
        this.characterRepository = characterRepository;
        this.actorRepository = actorRepository;
        this.staffRepository = staffRepository;
        this.relationRepository = relationRepository;
        this.props = props;

        // 三块之间**只有**下面这几行不同 —— 取数、判新旧、单飞、落库那条生命周期
        // 在 load() 里只有一份。三个几乎相同的 35 行方法抄三遍, 是那种"改了其中两个、
        // 第三个还留着老行为"的经典来源, 而这里的每一处都是静默失效的。
        this.characters = new SectionOps<>(
                SubjectExtrasProperties.Section.CHARACTERS,
                SubjectExtras::getCharactersFetchedAt,
                apiClient::getCharacters,
                this::readCharacters,
                (id, dtos) -> writer.replaceCharacters(id, mapper.toCharacters(id, dtos)),
                block -> block.characters().isEmpty());

        this.staff = new SectionOps<>(
                SubjectExtrasProperties.Section.STAFF,
                SubjectExtras::getStaffFetchedAt,
                apiClient::getPersons,
                staffRepository::findBySubjectIdOrderBySortOrderAsc,
                (id, dtos) -> writer.replaceStaff(id, mapper.toStaff(id, dtos)),
                List::isEmpty);

        this.relations = new SectionOps<>(
                SubjectExtrasProperties.Section.RELATIONS,
                SubjectExtras::getRelationsFetchedAt,
                apiClient::getRelatedSubjects,
                relationRepository::findBySubjectIdOrderBySortOrderAsc,
                (id, dtos) -> writer.replaceRelations(id, mapper.toRelations(id, dtos)),
                List::isEmpty);
    }

    /** 角色 + 声优 */
    public SectionResult<CharacterBlock> getCharacters(Integer subjectId) {
        return load(subjectId, characters);
    }

    /** 制作人员 */
    public SectionResult<List<SubjectStaff>> getStaff(Integer subjectId) {
        return load(subjectId, staff);
    }

    /** 关联条目 */
    public SectionResult<List<SubjectRelation>> getRelations(Integer subjectId) {
        return load(subjectId, relations);
    }

    /**
     * 一块附属数据读出来的结果 —— 装数据, 外加"这次到底成没成".
     *
     * <p><b>{@code data} 为空有两种完全不同的原因</b>, 而它们的响应体长得一样:
     * <ul>
     *   <li><b>"这里确实没有东西"</b> —— 上游明确回了空列表(实测 subject 21 的
     *       /characters 就是), 或者上游对这个条目回 404(它不存在)。这是一个确定的答案,
     *       界面上应当<b>静默隐藏那一整块</b> —— 一部没有角色数据的番, 底下挂一行
     *       "没有角色"只是在提示用户这里本该有东西;</li>
     *   <li><b>"这次没取到"</b> —— 网络故障、上游 5xx。它是<b>暂时的</b>, 界面上应当
     *       显示"加载失败 · 重试"。</li>
     * </ul>
     *
     * <p>把两者混成"200 + 空数组", 出错的后果是: 上游抖一下, 用户看到的是那一块
     * <b>无声无息地消失</b> —— 他既不知道发生了什么, 也没有任何可以点的东西去重试;
     * 而这一页的其余部分完全正常, 所以他多半会以为"这部番就是没收录角色"。
     * 与"把没取到渲染成数据就是这样"是同一类错误, 本仓在 {@code getCalendar} 缺
     * {@code unless} 那处记过一次。
     *
     * @param data   给前端的东西(可能是空的)
     * @param failed true 仅当<b>这次回源失败了、而且库里没有任何能顶上来的东西</b>。
     *               库里还有上一版数据时它是 false —— 陈旧的数据比一个错误提示有用,
     *               而"上游挂了"不该让用户连已经看到过的那几个角色都看不见。
     */
    public record SectionResult<R>(R data, boolean failed) {

        static <R> SectionResult<R> ok(R data) {
            return new SectionResult<>(data, false);
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 一块附属数据的完整生命周期: 看本地 → (旧了)回源 → 落库 → 给出最新的.
     *
     * <p>四步的顺序是有讲究的:
     * <ol>
     *   <li><b>先读 marker 再决定要不要回源</b>, 而不是"先看内容表有没有行"。
     *       后者在"上游明确说这里没有东西"(404, 或一个真的没有角色的条目)上会每次都重取
     *       —— 因为空表与从没取过看起来一模一样 —— 于是**一个上游已经回答过的问题被无限
     *       重复地问**。而所有"有角色"的用例在这个写法下都是绿的, 所以它只能靠
     *       "完整取回但为空"那条用例打红。</li>
     *   <li><b>不在飞才回源。</b> 已经在飞时<b>不阻塞、不等</b>, 直接给出库里现有的
     *       (冷启动时就是空)。与 {@code calendarCacheInFlight} 同一个选择: 宁可这一次
     *       少画一块, 也不占着请求线程去等一个第三方。前端对"这一块暂时是空的"与
     *       "这一块加载失败"是分开渲染的, 所以这个选择在界面上不会说谎。</li>
     *   <li><b>没取全就不落库</b>(marker 保持原样)。把"没取到"写成"取过了"会让一次瞬时
     *       故障固化成一个产品状态, 直到 TTL 到期 —— 这正是 {@code getCalendar} 缺
     *       {@code unless} 那个 bug 的形状, 只不过那一次固化的是两个小时, 这一次是一天。</li>
     *   <li><b>落库之后再从库里读一遍</b>, 而不是把上游给的 DTO 直接转出去。这一条不是
     *       绕远路: 它让"这次响应"与"下次响应"走<b>同一条读路径</b> —— 中间那层
     *       截断、选图、排序因此只可能有一份实现, 也就不可能出现"第一次打开是好的、
     *       刷新之后少了一截"这种只在第二次才显形的 bug。</li>
     * </ol>
     */
    private <D, R> SectionResult<R> load(Integer subjectId, SectionOps<D, R> ops) {
        LocalDateTime fetchedAt = markerOf(subjectId, ops);

        if (isFresh(fetchedAt, props.ttlFor(ops.section()))) {
            return SectionResult.ok(ops.read().apply(subjectId));
        }

        String flightKey = subjectId + ":" + ops.section();
        if (!inFlight.add(flightKey)) {
            // 别人正在取这一块。**不等** —— 见上面第 2 条。这次给的是库里现有的,
            // 而且这**不是**失败: 报 502 会让这一块显示"加载失败 · 重试", 但
            // 其实几毫秒后数据就来了, 用户点重试反而是多余的。刷新一次就有了。
            return SectionResult.ok(ops.read().apply(subjectId));
        }
        try {
            BangumiApiClient.SubjectCollectionFetch<D> fetch = ops.fetch().apply(subjectId);
            if (fetch.complete()) {
                ops.replace().accept(subjectId, fetch.items());
            }
            R data = ops.read().apply(subjectId);
            // 只有"没取全"**且**"库里也没东西能顶上"才算失败
            boolean failed = !fetch.complete() && ops.isEmpty().test(data);
            return new SectionResult<>(data, failed);
        } finally {
            // 必须在 finally 里: 中途抛异常时不清掉键, 这一块就**永远**不会再回源了
            // (此后每次请求都以为自己不是那个"在飞"的, 于是每次都直接读空库)。
            inFlight.remove(flightKey);
        }
    }

    /**
     * 某一块的 marker 列.
     *
     * <p>读不到那一行(从没取过)与读到了一个全 NULL 的行, 对调用方是同一件事 ——
     * 都返回 null。所以这里不区分它们, 也就不必先造一个空行。
     */
    private <D, R> LocalDateTime markerOf(Integer subjectId, SectionOps<D, R> ops) {
        return extrasRepository.findById(subjectId)
                .map(ops.marker())
                .orElse(null);
    }

    /**
     * 这个时刻还算不算新。
     *
     * <p>{@code null} 一律算旧 —— 那是"从没取过"或者"上次没取全"。
     * 判据写成"晚于 now − ttl"而不是"不超过 ttl 小时前": 后者要处理
     * "时刻在未来"这种不该出现但真出现时方向相反的情况。
     */
    private static boolean isFresh(LocalDateTime fetchedAt, Duration ttl) {
        return fetchedAt != null && fetchedAt.isAfter(LocalDateTime.now().minus(ttl));
    }

    /** 从两张表读出一块, 并把声优按角色分好组 */
    private CharacterBlock readCharacters(Integer subjectId) {
        List<SubjectCharacter> chars = characterRepository.findBySubjectIdOrderBySortOrderAsc(subjectId);
        Map<Integer, List<SubjectCharacterActor>> byCharacter = new LinkedHashMap<>();
        for (SubjectCharacterActor actor : actorRepository.findBySubjectIdOrderBySortOrderAsc(subjectId)) {
            byCharacter.computeIfAbsent(actor.getCharacterId(), k -> new ArrayList<>()).add(actor);
        }
        return new CharacterBlock(chars, byCharacter);
    }

    /**
     * 角色那一块的读取结果.
     *
     * <p>为什么不是"就返回角色的列表, 声优让调用方自己再查一次": 两批行由**同一个事务**
     * 一起写, 所以它们要么都是这一版的、要么都是上一版的; 而分两次去查就把这个保证
     * 交给调用方去维护了 —— 多一个调用点就多一次"只查了其中一批"的机会。
     */
    public record CharacterBlock(List<SubjectCharacter> characters,
                                 Map<Integer, List<SubjectCharacterActor>> actorsByCharacter) {

        /** 某个角色的声优, 没有就返回空列表(界面上那一行不渲染) */
        public List<SubjectCharacterActor> actorsOf(Integer characterId) {
            return actorsByCharacter.getOrDefault(characterId, Collections.emptyList());
        }
    }

    /**
     * 一块附属数据的"取哪、判新旧、怎么读、怎么落"四件事.
     *
     * <p>五个字段都是在构造 {@link SubjectExtrasService} 时绑好的方法引用, 所以这个 record
     * 没有状态、也不参与任何测试替身的构造。
     *
     * @param section    哪一块 —— 同时决定配置里用哪个 TTL、以及在飞集合的键
     * @param marker     从账本那一行上读这一块的时间戳
     * @param fetch      回源
     * @param read       从库里读出来
     * @param replace    落库(走 {@link SubjectExtrasWriter}, 跨 bean 调用)
     * @param isEmpty    "读出来这东西算不算空的" —— 角色那一块的 {@link CharacterBlock}
     *                   不是列表, 所以要由每一块自己说, 不能在这层用 {@code List.isEmpty()}
     */
    private record SectionOps<D, R>(
            SubjectExtrasProperties.Section section,
            Function<SubjectExtras, LocalDateTime> marker,
            Function<Integer, BangumiApiClient.SubjectCollectionFetch<D>> fetch,
            Function<Integer, R> read,
            BiConsumer<Integer, List<D>> replace,
            Predicate<R> isEmpty) {}
}

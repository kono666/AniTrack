package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.dto.BangumiDTO.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class BangumiApiClient {

    private static final Logger log = LoggerFactory.getLogger(BangumiApiClient.class);
    private final RestTemplate restTemplate;
    private final BangumiApiProperties props;

    public BangumiApiClient(RestTemplate restTemplate, BangumiApiProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /** 搜索番剧 */
    public SearchResponse searchSubjects(String keyword, int page, int limit) {
        return searchSubjects(keyword, page, limit, "rank");
    }

    /** 搜索番剧 (指定排序: rank / date) */
    public SearchResponse searchSubjects(String keyword, int page, int limit, String sort) {
        SearchRequest req = new SearchRequest();
        req.setKeyword(keyword != null ? keyword : "");
        req.setSort(sort != null ? sort : "rank");
        req.setTypeFilter(2); // type=2 只搜动画
        req.setOffset((page - 1) * limit);
        req.setLimit(limit);

        try {
            return post("/v0/search/subjects", req,
                    new ParameterizedTypeReference<SearchResponse>() {});
        } catch (Exception e) {
            log.warn("Bangumi search failed for '{}': {}", keyword, e.getMessage());
            return emptySearch();
        }
    }

    /**
     * 浏览全部条目: {@code GET /v0/subjects?type=2}, 按 id 升序, 靠 offset 深翻页.
     *
     * <p>为什么不能拿 {@link #searchSubjects} 顶替: 那是 {@code POST /v0/search/subjects},
     * 和这里的 {@code GET /v0/subjects} 是**两个不同的 offset 口径**. 全量回填要翻到第
     * 29,378 条, 而交接文档里那句「API 只能拿到约 25,000/29,378, 缺尾部」正是拿搜索那一侧
     * 的观感去推浏览这一侧 —— 实测浏览接口的规则是 **offset ≤ total**:
     * {@code offset=29378}(正好等于 total) 返回 200 + 空页, {@code 29379} 才 400.
     * 所以全量走这一个, 它一条都不少.
     *
     * <p>失败时返回 {@code null}, 而不是像 {@link #searchSubjects} 那样返回一个空的
     * {@code SearchResponse}. 这个区分是这里唯一重要的东西: 回填循环把「空的 data」读作
     * "翻到底了", 把「null」读作"这次请求没成". 两者混成一种的话, 一次网络抖动会让回填
     * **安静地提前收工**, 而它的结果看起来和"跑完了"一模一样 —— 一个少了一半数据的库,
     * 没有任何地方会报错. 搜索那边不需要这个区分, 因为它的调用方要的是"搜不到就显示没有".
     */
    public SearchResponse browseSubjects(int offset, int limit) {
        try {
            return get("/v0/subjects?type=2&limit=" + limit + "&offset=" + offset,
                    new ParameterizedTypeReference<SearchResponse>() {});
        } catch (Exception e) {
            log.warn("Bangumi browse 失败 (offset={}): {}", offset, e.getMessage());
            return null;
        }
    }

    /** 获取条目详情 */
    public SubjectDTO getSubjectDetail(Integer subjectId) {
        try {
            return get("/v0/subjects/" + subjectId,
                    new ParameterizedTypeReference<SubjectDTO>() {});
        } catch (Exception e) {
            log.warn("Bangumi subject {} fetch failed: {}", subjectId, e.getMessage());
            return null;
        }
    }

    /**
     * 剧集一页取多少.
     *
     * <p>它不是"服务端上限", 只是改动前那条 URL 上写死的值 —— 沿用它是为了让翻页的请求
     * 形状与改动前一致, 而不是因为它有什么特别.
     */
    private static final int EPISODE_PAGE_SIZE = 100;

    /**
     * 翻页硬上限.
     *
     * <p>500 集的番要 5 页; 给到 50 页是"两万五千集"的余量, 正常条目永远撞不到. 它挡的
     * 不是长番, 是 {@code total} 万一报错时把请求打到天上去.
     */
    private static final int EPISODE_MAX_PAGES = 50;

    /**
     * 一次剧集回源的结果.
     *
     * <p>{@code complete=false} 表示<b>没翻完</b> —— 某一页取失败, 或者撞上了翻页上限.
     * 这个标志是给调用方用的, 而且是这一版新增它的唯一理由: 没翻完的那批<b>不能</b>当成
     * 一次完整的缓存写进库. 写下去就等于把"取了一半"固化成"已经取过", 而那正是下面这个
     * bug 的形状 —— 一旦没人能区分"完整"与"截断", 截断就会一直留着.
     */
    public record EpisodeFetch(List<EpisodeDTO> items, boolean complete) {}

    /**
     * 获取剧集列表(本篇).
     *
     * <p><b>改动前这里只取一页.</b> URL 上写着 {@code limit=100}, 没有 offset 也没有循环 ——
     * 于是任何超过 100 集的番都只拿得到前 100 集. 而接口返回的 {@code total} 一直明明白白
     * 写着真实集数(实测: 火影忍者疾风传 subject 2782, {@code total=500}, 拿回 100 条,
     * sort 221..320). 前端没有截断(它就是 {@code v-for} 全画出来), 所以"只显示部分集数"
     * 是这一处造成的.
     *
     * <p>按 {@code total} 翻页, 而不是"把 limit 调大到 1000": 实测 {@code limit=1000} 确实
     * 一次给全 500 条, 但那是<b>没写进文档</b>的服务端行为, 哪天收紧了就是静默少一截 ——
     * 和这个 bug 一模一样的失效方式, 只是从"总是少"变成"某天开始少". 按 total 翻页则不管
     * 服务端每页给多少都收得齐.
     *
     * <p>offset 按<b>实际拿到的条数</b>推进({@code all.size()}), 不按请求的页大小: 万一
     * 服务端把每页压得比请求的小, 按请求量推进会<b>跳过</b>中间那些集 —— 那是比取不全更
     * 糟的失效, 因为收回来的是一个有洞的列表, 而且看上去是完整的.
     */
    public EpisodeFetch getEpisodes(Integer subjectId) {
        List<EpisodeDTO> all = new ArrayList<>();
        boolean complete = false;

        for (int page = 0; page < EPISODE_MAX_PAGES; page++) {
            EpisodeListResponse resp;
            try {
                String url = "/v0/episodes?subject_id=" + subjectId
                        + "&type=0"                          // 只取本篇(SP/OP/ED 不算"集数")
                        + "&limit=" + EPISODE_PAGE_SIZE
                        + "&offset=" + all.size();
                resp = get(url, new ParameterizedTypeReference<EpisodeListResponse>() {});
            } catch (Exception e) {
                // 已经拿到的照常返回, 但 complete 保持 false —— 由调用方决定要不要落库
                log.warn("Bangumi episodes for {} 第 {} 页取失败: {}", subjectId, page, e.getMessage());
                break;
            }

            List<EpisodeDTO> data = resp != null ? resp.getData() : null;
            if (data == null || data.isEmpty()) {
                complete = true;    // 翻到空页 = 走到底了
                break;
            }
            all.addAll(data);

            Integer total = resp.getTotal();
            if (total != null && all.size() >= total) {
                complete = true;    // 收齐了
                break;
            }
        }
        return new EpisodeFetch(all, complete);
    }

    // ==================== 附属数据: 角色 / 制作人员 / 关联条目 ====================

    /**
     * 一次附属数据回源的结果. 形状与 {@link EpisodeFetch} 同源, 理由也同源.
     *
     * <p>{@code complete=false} 表示<b>这次没成</b>(网络故障、上游 5xx、解析失败) ——
     * 调用方不能把它写成"已经取过"。这与"上游明确说这里没有东西"是两件完全不同的事,
     * 而它们的响应体长得一模一样(都是空列表)。
     *
     * <p><b>为什么现在只有"整次成败"这一档, 却还要留 complete.</b> 这三个接口是
     * <b>单次返回整个集合</b>的(实测 subject 8 的 /characters 一次 128 行, 没有
     * offset/limit), 所以今天不存在"翻了一半"。留着它是因为响应体是<b>裸数组</b>、
     * 没有 {@code total} —— 上游哪天开始截断, 我们从响应里<b>无从发现</b>。真到那天,
     * 补的判据落在这个字段上, 而不是在三个调用点各改一遍。
     */
    public record SubjectCollectionFetch<T>(List<T> items, boolean complete) {}

    /** 角色(含声优) */
    public SubjectCollectionFetch<CharacterDTO> getCharacters(Integer subjectId) {
        return fetchCollection("/v0/subjects/" + subjectId + "/characters",
                new ParameterizedTypeReference<List<CharacterDTO>>() {});
    }

    /** 制作人员 */
    public SubjectCollectionFetch<PersonDTO> getPersons(Integer subjectId) {
        return fetchCollection("/v0/subjects/" + subjectId + "/persons",
                new ParameterizedTypeReference<List<PersonDTO>>() {});
    }

    /** 关联条目(前传/续集/剧场版/游戏…) */
    public SubjectCollectionFetch<RelatedSubjectDTO> getRelatedSubjects(Integer subjectId) {
        return fetchCollection("/v0/subjects/" + subjectId + "/subjects",
                new ParameterizedTypeReference<List<RelatedSubjectDTO>>() {});
    }

    /**
     * 取一个"整集合"接口, 并把<b>「这里没有东西」与「这次没取到」分开</b>。
     *
     * <p>这个区分是这一个方法存在的全部理由, 而且它有一个很具体的后果:
     * 两者都会得到"空列表"这个调用方最想要的东西, 于是把它们混成一种, 一次瞬时故障
     * 就会被写成"这个条目确实没有角色" —— 而 marker 一落, 这个结论就固化了,
     * 直到 TTL 到期。库里从此躺着一条"问过上游了, 没有", 而事实是"没问到"。
     *
     * <p>实测: 不存在的 subject 回 <b>HTTP 404</b>(body 是
     * {@code {"title":"Not Found",...}}), 所以 404 是**上游给出的确定答案** ——
     * {@code complete=true, items=[]}。其余任何异常都是"这次没成"。
     *
     * <p>返回的列表保证非 null: 调用方直接 for-each, 不必各自判一次。
     */
    private <T> SubjectCollectionFetch<T> fetchCollection(
            String path, ParameterizedTypeReference<List<T>> typeRef) {
        try {
            List<T> items = get(path, typeRef);
            return new SubjectCollectionFetch<>(
                    items != null ? items : Collections.emptyList(), true);
        } catch (HttpClientErrorException.NotFound e) {
            // 404 是答案本身, 不是故障 —— 见方法注释
            log.debug("Bangumi {} 上游回 404: 这个条目确实没有这一块", path);
            return new SubjectCollectionFetch<>(Collections.emptyList(), true);
        } catch (Exception e) {
            log.warn("Bangumi {} 取失败: {}", path, e.getMessage());
            return new SubjectCollectionFetch<>(Collections.emptyList(), false);
        }
    }

    /** 获取每日放送日历 */
    public List<CalendarDay> getCalendar() {
        try {
            CalendarDay[] days = restTemplate.getForObject(
                    props.getBaseUrl() + "/calendar", CalendarDay[].class);
            return days != null ? Arrays.asList(days) : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Bangumi calendar fetch failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== 内部 HTTP 方法 ==========

    private <T> T get(String path, ParameterizedTypeReference<T> typeRef) {
        HttpEntity<Void> entity = new HttpEntity<>(headers());
        ResponseEntity<T> resp = restTemplate.exchange(
                props.getBaseUrl() + path, HttpMethod.GET, entity, typeRef);
        return resp.getBody();
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<T> typeRef) {
        HttpEntity<Object> entity = new HttpEntity<>(body, headers());
        ResponseEntity<T> resp = restTemplate.exchange(
                props.getBaseUrl() + path, HttpMethod.POST, entity, typeRef);
        return resp.getBody();
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent", props.getUserAgent());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private SearchResponse emptySearch() {
        SearchResponse r = new SearchResponse();
        r.setData(Collections.emptyList());
        r.setTotal(0);
        return r;
    }
}

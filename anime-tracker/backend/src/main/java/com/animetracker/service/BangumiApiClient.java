package com.animetracker.service;

import com.animetracker.config.BangumiApiProperties;
import com.animetracker.dto.BangumiDTO.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
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

    /** 获取剧集列表 */
    public List<EpisodeDTO> getEpisodes(Integer subjectId) {
        try {
            String url = "/v0/episodes?subject_id=" + subjectId
                    + "&type=0"  // 只取本篇
                    + "&limit=100";
            EpisodeListResponse resp = get(url,
                    new ParameterizedTypeReference<EpisodeListResponse>() {});
            return resp != null && resp.getData() != null ? resp.getData() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Bangumi episodes for {} fetch failed: {}", subjectId, e.getMessage());
            return Collections.emptyList();
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

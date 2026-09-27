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

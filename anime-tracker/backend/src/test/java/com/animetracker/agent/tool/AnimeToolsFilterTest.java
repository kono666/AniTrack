package com.animetracker.agent.tool;

import com.animetracker.agent.llm.ToolCall;
import com.animetracker.entity.Anime;
import com.animetracker.service.AnimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code filter_anime} 的封顶, 以及它对外怎么交代这件事.
 *
 * <p><b>为什么这件事值得单独验.</b> 这个工具此前没有上界 —— 匹配多少就返回多少,
 * 无参数调用时那就是整张表; 而它返回的东西要整个交给模型. 现在它走分页版并封顶
 * {@link AnimeService#FILTER_TOOL_LIMIT} 条, 但 {@code count} 仍然是<b>真实匹配总数</b>:
 * 这两个数字合起来才能回答"是不是还有更多". 少交代任何一半都会让模型误判 ——
 * 它看到 50 条与看到"共 1200 条、给你 50 条", 后面的追问方式完全不同.
 *
 * <p>另一件同等重要的是<b>工具描述</b>: 契约改了而描述没改, 模型会按旧契约理解结果.
 * 这正是 {@code by-tag} 当年踩过的坑(见 {@code BangumiController} 里那段 javadoc:
 * 契约一变, 前端就静默渲染成空), 所以下面的第二条用例断言描述里真的写了上限与
 * {@code count} 的含义, 而不是只断言代码里那个常量.
 */
class AnimeToolsFilterTest {

    private final AnimeService animeService = mock(AnimeService.class);
    private final AnimeTools tools = new AnimeTools(animeService);

    @Test
    @DisplayName("list 封顶 50, count 是真实匹配总数")
    void capsTheListButReportsTheRealTotal() throws Exception {
        when(animeService.getFilteredPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page(AnimeService.FILTER_TOOL_LIMIT, 1200));

        Map<String, Object> out = runFilter(new LinkedHashMap<>());

        assertThat((List<?>) out.get("list")).hasSize(AnimeService.FILTER_TOOL_LIMIT);
        assertThat(out.get("count"))
                .as("count 若也变成 50, 模型就永远不知道还有更多")
                .isEqualTo(1200);
    }

    /**
     * 参数是靠捕获验的, 不是靠"mock 回什么就断言什么".
     *
     * <p>只断言返回结构的话, 一个"漏传封顶、仍然读全部"的实现也能全绿 ——
     * mock 会忠实地把喂进去的东西吐回来. 所以要盯的是交给仓储的那个窗口.
     */
    @Test
    @DisplayName("确实走的是分页版: 第 1 页, 页长就是那个上限")
    void asksTheRepositoryForExactlyOneCappedPage() throws Exception {
        when(animeService.getFilteredPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page(3, 3));

        runFilter(new LinkedHashMap<>());

        ArgumentCaptor<Integer> pageNo = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> pageSize = ArgumentCaptor.forClass(Integer.class);
        verify(animeService).getFilteredPage(
                any(), any(), any(), any(), any(), pageNo.capture(), pageSize.capture());
        assertThat(pageNo.getValue()).as("工具没有页码, 永远是第 1 页").isEqualTo(1);
        assertThat(pageSize.getValue()).isEqualTo(AnimeService.FILTER_TOOL_LIMIT);
    }

    @Test
    @DisplayName("参数原样传下去: 空白当没传, sort 缺省是 rating")
    void passesFiltersThroughAndBlanksBecomeNull() throws Exception {
        when(animeService.getFilteredPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page(0, 0));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("year", "2024");
        args.put("season", "   ");
        runFilter(args);

        verify(animeService).getFilteredPage(
                "2024", null, null, null, "rating", 1, AnimeService.FILTER_TOOL_LIMIT);
    }

    @Test
    @DisplayName("工具描述里写明上限与 count 的含义 —— 不写模型就会把 list 当全集")
    void descriptionStatesTheCapAndWhatCountMeans() {
        String description = filterTool().getSpec().getDescription();

        assertThat(description)
                .as("上限的数字要写给模型看")
                .contains(String.valueOf(AnimeService.FILTER_TOOL_LIMIT));
        assertThat(description)
                .as("只说上限不说 count, 模型仍然不知道结果被截过")
                .contains("count");
    }

    // ========== 夹具 ==========

    private ToolDefinition filterTool() {
        return tools.tools().stream()
                .filter(t -> "filter_anime".equals(t.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("filter_anime 不在工具清单里"));
    }

    private Map<String, Object> runFilter(Map<String, Object> arguments) throws Exception {
        ToolCall call = new ToolCall("call-1", "filter_anime", arguments);
        return cast(filterTool().getExecutor().execute(call, null));
    }

    /** 与 service 的返回结构一致: {@code {list, total, page}} */
    private static Map<String, Object> page(int listSize, int total) {
        List<Anime> list = new ArrayList<>();
        for (int i = 0; i < listSize; i++) {
            list.add(Anime.builder().id(95000000 + i).title("番" + i).build());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", total);
        out.put("page", 1);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object raw) {
        return (Map<String, Object>) raw;
    }
}

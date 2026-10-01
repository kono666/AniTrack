package com.animetracker.agent.tool;

import com.animetracker.agent.llm.ToolCall;
import com.animetracker.dto.RequestDTO.TrackRequest;
import com.animetracker.entity.AnimeTracking;
import com.animetracker.entity.User;
import com.animetracker.service.StatsService;
import com.animetracker.service.TrackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手那条路上「加/改追番」的参数语义.
 *
 * <p>这个类此前一条用例都没有, 而网页接口与工具是**两条各自独立的入参通道** ——
 * 网页那边改成局部更新(缺席 = 保持原值)之后, 这一侧必须跟着放开: 否则同一个
 * 「把进度改成 5」的请求, 走网页能成、走助手会报参数错误.
 *
 * <p>更要紧的是那个语义本身. status 改前在 schema 里是必填, 于是模型每做一次
 * 「只改进度」都被迫顺带替你决定一次状态 —— 它只能按自己的理解挑一个(常挑
 * watching), 用户看到的是"我就改了个进度, 它怎么自己变成在看了". 所以:
 * 缺席必须原样传成 null, 不能被某个"看起来合理"的默认值顶替.
 */
class TrackingToolsTest {

    private static final User USER = User.builder()
            .id(1L).username("alice").role("USER").status("ACTIVE").build();

    private TrackService trackService;
    private TrackingTools tools;

    @BeforeEach
    void setUp() {
        trackService = mock(TrackService.class);
        tools = new TrackingTools(trackService, mock(StatsService.class));
        // 服务层那套局部更新由 TrackServiceTest 与 TrackCheckIntegrationTest 钉着,
        // 这里只关心工具往下发的那个请求长什么样, 所以回一个能读字段的实体就够
        when(trackService.saveTracking(any(), any())).thenAnswer(inv -> {
            TrackRequest r = inv.getArgument(1);
            return AnimeTracking.builder()
                    .id(7L).subjectId(r.getSubjectId())
                    .status(r.getStatus()).progress(r.getProgress()).build();
        });
    }

    private ToolDefinition tool(String name) {
        return tools.tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有这个工具: " + name));
    }

    /** 参数**按键给**, 而不是先造一个装满默认值的 map —— 「这个键在不在」正是被测的东西 */
    private static ToolCall call(Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new ToolCall("call-1", "add_or_update_tracking", args);
    }

    private TrackRequest captured() throws Exception {
        ArgumentCaptor<TrackRequest> captor = ArgumentCaptor.forClass(TrackRequest.class);
        verify(trackService).saveTracking(any(), captor.capture());
        return captor.getValue();
    }

    private Object run(ToolCall call) throws Exception {
        return tool("add_or_update_tracking").getExecutor().execute(call, USER);
    }

    @Test
    @DisplayName("只说进度时 status 原样缺席 —— 缺席的意思是「保持原值」, 不是让助手替你挑一个")
    void omitsStatusWhenTheModelDoesNotProvideOne() throws Exception {
        run(call("subjectId", 100, "progress", 5));

        TrackRequest req = captured();
        assertThat(req.getStatus())
                .as("改前这里是必填, 助手只能自己挑一个状态塞进来(通常是 watching)")
                .isNull();
        assertThat(req.getProgress()).isEqualTo(5);
    }

    @Test
    @DisplayName("不传进度时是 null, 不是 0 —— 传 0 等于每次改状态都顺手把进度清零")
    void omitsProgressWhenTheModelDoesNotProvideOne() throws Exception {
        run(call("subjectId", 100, "status", "on_hold"));

        TrackRequest req = captured();
        assertThat(req.getProgress()).isNull();
        assertThat(req.getScore()).isNull();
        assertThat(req.getNotes()).isNull();
    }

    @Test
    @DisplayName("空串不算「有个值」: 传空串等于没传, 绝不把 \"\" 发下去挨 DTO 的 @Pattern")
    void treatsABlankStatusAsAbsent() throws Exception {
        run(call("subjectId", 100, "status", ""));

        assertThat(captured().getStatus())
                .as("发下去就是 400, 而模型看到的会是一句与它无关的参数错误")
                .isNull();
    }

    @Test
    @DisplayName("传了状态就照传")
    void passesTheStatusThroughWhenProvided() throws Exception {
        run(call("subjectId", 100, "status", "on_hold"));

        assertThat(captured().getStatus()).isEqualTo("on_hold");
    }

    @Test
    @DisplayName("白名单没有因为 status 变可选而消失")
    void rejectsStatusesOutsideTheWhitelist() {
        assertThatThrownBy(() -> run(call("subjectId", 100, "status", "finished")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finished");
    }

    @Test
    @DisplayName("发给模型的必填清单里只有 subjectId")
    void onlySubjectIdIsRequired() {
        Map<String, Object> schema = tool("add_or_update_tracking").getSpec().getInputSchema();

        // 改前 status 也在这份清单里. 它决定了模型会不会自作主张补一个状态 ——
        // 写在这里比写在 executor 里更靠前: 必填意味着模型"必须"给一个值
        assertThat(schema.get("required")).isEqualTo(List.of("subjectId"));
    }

    @Test
    @DisplayName("返回值带上落库后的状态与进度, 供模型接着往下说")
    @SuppressWarnings("unchecked")
    void returnsTheSavedRow() throws Exception {
        Map<String, Object> out = (Map<String, Object>) run(
                call("subjectId", 100, "status", "watching", "progress", 5));

        assertThat(out)
                .containsEntry("subjectId", 100)
                .containsEntry("status", "watching")
                .containsEntry("progress", 5);
    }
}

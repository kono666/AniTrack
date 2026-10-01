package com.animetracker.controller;

import com.animetracker.entity.AdminActionLog;
import com.animetracker.repository.AdminActionLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「动作与记账**同一个事务**」这句话在真库上的证伪点。
 *
 * <p><b>为什么必须另起一个类、还要把仓储换成 mock。</b> {@code AdminServiceTest} 里
 * {@code IsolatedInsert} 是直接 new 出来的, 注解在那条路径上不生效, 所以那一层最多只能
 * 验到"记账发生在回调里"这个形状。而这里要问的是另一件事: 记账失败时, 那次
 * <b>已经写进去的用户状态改动会不会跟着回滚</b>。要制造一次"记账失败"，唯一的办法
 * 就是让仓储抛异常 —— 而把仓储换成 mock 会污染同上下文里的其它用例, 所以单独一个类。
 *
 * <p>这一条同时也是四个动作各自那句注释的落点: 拆成两个事务的话, 「人封了、账没记」
 * 和「账记了、人没封」都能发生, 而且<b>两种都不报错</b> —— 账本一旦漏记就再也补不回来。
 *
 * <p>两个用例必须成对看: 只有"抛异常时回滚"那一条的话, 一个根本不执行这个动作的
 * 实现也能过(没写就没得回滚)。对照那条证明了这条链平时是真的会改库的。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-admin-action-rollback;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminActionLogRollbackTest {

    private static final String TARGET = "al_rollback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 换掉账本的写入者: 这个类要制造的正是"记账这一步失败" */
    @MockBean
    private AdminActionLogRepository adminActionLogRepository;

    private static String adminToken;
    private long targetId;

    @BeforeEach
    void seedAndLogin() throws Exception {
        if (adminToken == null) {
            String body = mockMvc.perform(post("/api/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            adminToken = objectMapper.readTree(body).path("data").path("token").asText();
        }

        jdbc.update("DELETE FROM \"user\" WHERE username = ?", TARGET);
        jdbc.update("INSERT INTO \"user\" (username, password, email, role, status, created_at) "
                        + "VALUES (?, ?, ?, 'USER', 'ACTIVE', ?)",
                TARGET, "$2a$10$thisIsNotAValidHashButTheColumnOnlyNeedsChars",
                TARGET + "@example.com", Timestamp.valueOf(LocalDateTime.now()));
        targetId = jdbc.queryForObject("SELECT id FROM \"user\" WHERE username = ?", Long.class, TARGET);
    }

    private String statusInDb() {
        return jdbc.queryForObject(
                "SELECT status FROM \"user\" WHERE id = ?", String.class, targetId);
    }

    private void toggle() throws Exception {
        mockMvc.perform(put("/api/admin/users/" + targetId + "/toggle")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));
    }

    /** 对照: 记账正常时, 这个动作是真的会改库的 —— 否则下面那条断言是空过 */
    @Test
    @DisplayName("对照: 记账正常时用户被禁用, 改动落在库里")
    void theActionActuallyWritesWhenTheLedgerIsFine() throws Exception {
        toggle();

        assertThat(statusInDb()).isEqualTo("DISABLED");
    }

    /**
     * 记账抛异常 → 整个动作跟着失败, <b>库里什么都没变</b>。
     *
     * <p>拆成两个事务的话, 用户那一次 {@code save} 会自己提交, 于是库里留下一个
     * {@code DISABLED} 的用户, 而账本里没有对应的那一行 —— 一次无痕封禁。
     */
    @Test
    @DisplayName("记账失败时动作一起回滚: 用户状态还是 ACTIVE, 动作没有静默生效")
    void whenTheLedgerWriteFailsTheActionRollsBack() throws Exception {
        when(adminActionLogRepository.save(any(AdminActionLog.class)))
                .thenThrow(new IllegalStateException("账本写不进去"));

        toggle();

        assertThat(statusInDb())
                .as("记账失败却把封禁提交了, 就是一次谁也查不到的封禁")
                .isEqualTo("ACTIVE");
    }
}

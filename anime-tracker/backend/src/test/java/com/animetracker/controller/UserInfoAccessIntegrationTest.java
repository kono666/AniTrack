package com.animetracker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 查看用户信息这个接口的三种结果各有各的含义: 401 / 403 / 200.
 *
 * <p>这组用例的价值在于把三个数字钉死在各自的原因上 —— 它们很容易被后来的改动
 * 混成一个:
 *
 * <ul>
 *   <li><b>401</b> 未登录. 这里要说的是一件容易搞反的事: 控制器里那句
 *       {@code currentUser == null} 判断**轮不到它执行** —— /api/user/info/**
 *       不在 SecurityConfig 的免登录名单里, 匿名请求在过滤器链上就被拦下了,
 *       根本进不到控制器. 也就是说 401 是过滤器链给的, 控制器里那个分支是纯粹的
 *       兜底(真走到那里才说明有别的东西把它放行了, 那是另一个问题).
 *       值不值得钉: 如果哪天有人把 /api/user/** 加进免登录名单, 这个接口会从 401
 *       变成 403「无权查看」—— 对外看起来像是权限问题, 实际是没登录, 排查方向完全不同.</li>
 *   <li><b>403</b> 登录了, 但看的是别人的信息(且不是管理员). 这是真的越权尝试.</li>
 *   <li><b>200</b> 自己的信息, 或管理员看任何人的.</li>
 * </ul>
 *
 * <p>用 MockMvc: 验的是过滤器链与控制器返回值, 不涉及异步或连接器行为.
 * 内存库 + dev profile + 关掉预加载(理由同 AdminReviewDeleteIntegrationTest).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-user-info-access;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "anitrack.preload.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UserInfoAccessIntegrationTest {

    private static final String INFO = "/api/user/info/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 登录并返回整个 data 节点 —— 用例既要用 token, 也要用里面的 id. */
    private JsonNode login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode register(String username) throws Exception {
        String password = "passw0rd123";
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return login(username, password);
    }

    @Test
    @DisplayName("未登录看用户信息 -> 401(过滤器链拦下的), 而不是 403")
    void anonymousGetsUnauthorizedNotForbidden() throws Exception {
        mockMvc.perform(get(INFO + 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("普通用户看自己的信息 -> 200")
    void userCanReadOwnInfo() throws Exception {
        JsonNode me = register("infoprobe1");

        mockMvc.perform(get(INFO + me.path("id").asLong())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + me.path("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(me.path("id").asLong()))
                .andExpect(jsonPath("$.data.username").value("infoprobe1"));
    }

    @Test
    @DisplayName("普通用户看别人的信息 -> 403(登录了, 但没有权限)")
    void userCannotReadSomeoneElseInfo() throws Exception {
        JsonNode me = register("infoprobe2");
        JsonNode admin = login("admin", "admin123");

        mockMvc.perform(get(INFO + admin.path("id").asLong())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + me.path("token").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权查看该用户信息"));
    }

    @Test
    @DisplayName("管理员看别人的信息 -> 200")
    void adminCanReadAnyone() throws Exception {
        JsonNode someone = register("infoprobe3");
        JsonNode admin = login("admin", "admin123");

        mockMvc.perform(get(INFO + someone.path("id").asLong())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.path("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("infoprobe3"));
    }
}

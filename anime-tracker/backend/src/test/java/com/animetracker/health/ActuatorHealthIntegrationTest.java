package com.animetracker.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 探活端点的端到端测试（走真实的 Spring Security 过滤器链）。
 *
 * <p>这个类之所以值得存在，是因为 health 端点的价值全在「它在谁手里」和「它敢不敢说 DOWN」上：
 * <ul>
 *   <li>Docker HEALTHCHECK、CI 冒烟脚本、nginx 探针手里都不可能持有 JWT，
 *       所以它必须免登录 —— 这一点光看配置文件看不出来，得真发一个不带 Authorization 的请求；</li>
 *   <li>如果它在依赖坏掉时仍然回 UP，那探活就成了摆设，所以这里连组件状态一起断言（开发环境开着详情）。</li>
 * </ul>
 *
 * <p>用的是全新的内存库，并且**没有**覆写 {@code ddl-auto}：
 * 这样启动时 Flyway 会在一个空库上跑完 V1、V2，Hibernate 随后校验实体与表对得上。
 * 也就是说，这个测试顺带证明的是一条真实路径 —— 空库 + 迁移脚本 + validate 能正常启动，
 * 而 /actuator/health 的 UP 恰好是这条路径跑通的产物。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:anitrack-health;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ActuatorHealthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("不带任何凭证就能探活 —— 否则 Docker / CI / nginx 都问不了「服务起来了没」")
    void healthIsReachableWithoutLogin() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("开发环境能看到各组件状态，数据库这一项直接反映库通不通")
    void healthShowsComponentDetailsInDev() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                // 有这一项才说明「详情确实开着」—— 只有 status 的话，数据库挂了也分不清
                .andExpect(jsonPath("$.components.db.details").exists());
    }

    /**
     * liveness 与 readiness 的分工, 在响应里是看得见的.
     *
     * <p>liveness 里**不该有 db**: 它要回答的是「进程还活着吗」, 而且必须快、
     * 必须有界 —— 实测过数据库不可用时, 带 db 的检查要等 Hikari 的连接超时,
     * 整整 30 秒才回 503. 拿它当容器探针, 会先撞上探针超时,
     * 于是「超时」和「依赖不可用」两种状态混成一种, 排障时分不清谁是谁.
     *
     * <p>readiness 里**必须有 db**: 依赖连不上时, 该做的是别再给它发流量,
     * 而不是重启它 —— 重启后端解决不了数据库的问题, 只会变成重启循环.
     */
    @Test
    @DisplayName("liveness 只问进程（不含 db），readiness 才看依赖")
    void livenessAndReadinessHaveDifferentScopes() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.ping").exists())
                .andExpect(jsonPath("$.components.db").doesNotExist());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    /**
     * health 是放行的，别的 actuator 端点不是。
     *
     * <p>现在只暴露了 health 一个端点（application.yml 里的 exposure 清单），
     * 所以这两个请求即使通过授权也只会得到 404。这里断言 401 的意义在于：
     * 请求是被安全层拦下的，而不是「反正没这个端点」——
     * 将来谁把清单放宽成 {@code *}，这两个端点不会因此变成匿名可读。
     *
     * <p>401 而不是 403，是因为匿名请求走的是 authenticationEntryPoint，
     * 回的是项目统一的 JSON 结构（与 /api/admin/** 一致）。
     */
    @Test
    @DisplayName("除 health 外的 actuator 端点不接受匿名访问")
    void otherActuatorEndpointsAreNotPublic() throws Exception {
        for (String path : new String[]{"/actuator/beans", "/actuator/env", "/actuator/configprops"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("请先登录"));
        }
    }
}

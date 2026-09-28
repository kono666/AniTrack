package com.animetracker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 里不许再出现默认 profile —— 这条守的是「配置的形状」, 不是行为.
 *
 * <p>为什么值得单独一条用例: 把 spring.profiles.active: dev 加回主配置块, 是个
 * 一行、看起来纯属方便、而且**全量用例照样全绿**的改动. 现有用例清一色带
 * @ActiveProfiles, 谁也不会去看这个文件有没有默认值; 真出事的场景(部署漏配环境变量)
 * 又不在测试里. 而后果不对称: 服务照常启动、功能全部正常, 只有两点变了 —— 用的是
 * H2 + admin/admin123 + swagger, 以及 JwtUtil 的熔断因为 dev 是 active 而被放行
 * (它只在非 dev 时拒绝那个公开的兜底密钥, 见 JwtUtil.validateSecret 与
 * JwtUtilTest.shouldRejectDevFallbackSecretWhenNoProfileActive).
 *
 * <p>也就是说: 这一行加上去, 就同时把「4.5 去掉硬编码」与「公开密钥的熔断」一起关掉了,
 * 且没有任何编译期或测试期的信号. 所以这里直接读文件. 不启动 Spring 上下文是有意的:
 * 要验的就是文件本身, 起了上下文反而验不到(测试自己的 @ActiveProfiles 会盖掉它).
 */
class DefaultProfileIsNotHardcodedTest {

    private static final String ACTIVE_PROFILE_KEY = "spring.profiles.active";
    private static final String ON_PROFILE_KEY = "spring.config.activate.on-profile";

    /** 按多文档 YAML 逐块解析: 一个 --- 分隔的块对应一个 PropertySource. */
    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
    }

    @Test
    @DisplayName("没有任何一个配置块设定 spring.profiles.active")
    void noDocumentActivatesAProfile() throws IOException {
        List<PropertySource<?>> docs = documents();
        assertThat(docs)
                .as("application.yml 应当是多块文档(基础块 + dev + postgres); "
                        + "解析出 %d 块, 是不是文件结构变了?", docs.size())
                .hasSizeGreaterThanOrEqualTo(3);

        for (PropertySource<?> doc : docs) {
            assertThat(doc.containsProperty(ACTIVE_PROFILE_KEY))
                    .as("配置块 [%s] 又设上了 %s —— 这会让「漏配 profile」不再报错, "
                            + "同时放行公开的开发密钥. 本地要 dev 请显式传 "
                            + "-Dspring-boot.run.profiles=dev (start-dev.bat 已经带好了).",
                            doc.getName(), ACTIVE_PROFILE_KEY)
                    .isFalse();
        }
    }

    /**
     * 上面那条只证明了「没有默认值」, 而没有证明「profile 还在」——
     * 把整个 dev 段删掉同样能让它变绿, 但那不是修法, 是把开发环境弄没了.
     */
    @Test
    @DisplayName("dev 与 postgres 两个 profile 段都还在, 只是不再有默认值")
    void bothProfilesStillExist() throws IOException {
        List<Object> declared = documents().stream()
                .filter(doc -> doc.containsProperty(ON_PROFILE_KEY))
                .map(doc -> doc.getProperty(ON_PROFILE_KEY))
                .toList();

        assertThat(declared)
                .as("去掉默认值之后, 两个 profile 段必须仍然各自声明自己的 on-profile")
                .containsExactlyInAnyOrder("dev", "postgres");
    }
}

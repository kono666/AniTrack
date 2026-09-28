package com.animetracker.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住 Actuator 的两个口径：只暴露 health，以及生产环境不回详情。
 *
 * <p>为什么不靠上面那个集成测试来守：集成测试只能跑一个 profile（本机没有 PostgreSQL，
 * postgres profile 起不来），而「生产环境隐藏详情」恰恰只体现在 postgres 段里 ——
 * 那正是最不该出错、又最不容易在本地跑到的一处。所以这里退一步，直接读 application.yml，
 * 按文档逐段合并后再断言，等价于「用读者的身份核一遍配置」。
 *
 * <p>另外这类改动有一个特点：写错（或忘了写）不会报任何错，只会静默生效。
 * 详情里的组件名属于内部结构信息，暴露清单放宽则会连带放出 env、beans 这类会打印
 * 配置与环境变量的端点。两件事都值得一条测试钉住。
 *
 * <p>注意：读的是源码目录而不是 classpath，理由同 MigrationScriptPairTest ——
 * classpath 上可能是上一次构建的残留。如果这两个键被挪到了别的配置文件里，
 * 这个测试会红，届时同步改这里即可。
 */
class ActuatorConfigTest {

    /** 统一配置文件（Maven 跑测试时的工作目录就是模块根，也就是 backend/）。 */
    private static final Path CONFIG = Path.of("src", "main", "resources", "application.yml");

    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final String SHOW_DETAILS = "management.endpoint.health.show-details";

    @Test
    @DisplayName("每个环境都只暴露 health 一个端点")
    void onlyHealthIsExposed() {
        for (String profile : List.of("dev", "postgres")) {
            assertThat(read(profile, EXPOSURE))
                    .as("management.endpoints.web.exposure.include (%s) —— 除 health 外的 actuator 端点"
                            + "会打印内部结构（env 甚至包含环境变量），不该跟着构建产物上公网；"
                            + "要临时排查请在本地改，不要提交", profile)
                    .isEqualTo("health");
        }
    }

    @Test
    @DisplayName("详情开关：开发打开、生产关闭，且两边都显式写出")
    void healthDetailsAreOpenInDevAndClosedInProd() {
        // dev 打开是为了排障：数据库挂掉时 /actuator/health 直接指出是哪个组件不健康
        assertThat(read("dev", SHOW_DETAILS))
                .as("dev 的 %s 应当是 always，否则本地排障时只能看到一个笼统的 DOWN", SHOW_DETAILS)
                .isEqualTo("always");

        // postgres 关闭，且必须是显式写出来的
        //
        // 只断言「不是 always」不够: 不写这一项时默认值恰好也是 never, 看起来也对，
        // 但那是「碰巧对」—— 默认值将来变了不会报错，只会静默开始回详情。
        // 所以这里连「有没有写」一起断言。
        assertThat(profileHasOwnKey("postgres", SHOW_DETAILS))
                .as("postgres 段里必须显式写出 %s（不写会靠默认值, 默认值是 never 只是巧合）", SHOW_DETAILS)
                .isTrue();
        assertThat(read("postgres", SHOW_DETAILS))
                .as("postgres 的 %s 必须是 never —— 探活只需要一个 UP/DOWN", SHOW_DETAILS)
                .isEqualTo("never");
    }

    /**
     * liveness 与 readiness 的成员必须各就各位.
     *
     * <p>这两个组最容易出的错是「有人图省事把 db 加进 liveness」——那样容器探针
     * 会跟着数据库一起变慢 (实测 30 秒), 而探针必须快且有界. 反过来,
     * readiness 少了 db 就等于「数据库挂了照样往里发流量」. 两个方向都值得钉住.
     */
    @Test
    @DisplayName("liveness 只含 ping；readiness 必须含 db")
    void probeGroupsHaveTheRightMembers() {
        assertThat(read("dev", "management.endpoint.health.group.liveness.include"))
                .as("容器探针用的 liveness 不该包含 db: 数据库不可用时它会等 Hikari 的连接超时, "
                        + "而探针必须快且有界; 依赖不可用该由 readiness 表达")
                .isEqualTo("ping");

        assertThat((String) read("postgres", "management.endpoint.health.group.readiness.include"))
                .as("readiness 必须包含 db: 它才是「现在能不能把流量给它」的依据")
                .contains("db");
    }

    // ---------- YAML 读取 ----------

    /**
     * 取某个 profile 生效后的配置值。
     *
     * <p>合并规则简化成「文档在文件里出现的顺序，后面的覆盖前面的」：
     * 与 Spring Boot 的多文档处理一致，对本文件里这几个键足够用 ——
     * 它们都是标量，不涉及列表是覆盖还是追加那种微妙差别。
     */
    private static Object read(String profile, String dottedKey) {
        Object fromCommon = dig(mergeOfCommonDocs(), dottedKey);
        Object fromProfile = dig(profileDoc(profile), dottedKey);
        return fromProfile != null ? fromProfile : fromCommon;
    }

    /** 该键是否被写在 profile 段自己的文档里（而不是继承来的） */
    private static boolean profileHasOwnKey(String profile, String dottedKey) {
        return dig(profileDoc(profile), dottedKey) != null;
    }

    private static Map<String, Object> mergeOfCommonDocs() {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        for (Map<String, Object> doc : documents()) {
            if (dig(doc, "spring.config.activate.on-profile") == null) {
                merged.putAll(doc);
            }
        }
        return merged;
    }

    private static Map<String, Object> profileDoc(String profile) {
        for (Map<String, Object> doc : documents()) {
            if (profile.equals(dig(doc, "spring.config.activate.on-profile"))) {
                return doc;
            }
        }
        throw new IllegalStateException("application.yml 里找不到 on-profile: " + profile + " 的文档段");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (InputStream in = Files.newInputStream(CONFIG)) {
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map) {
                    docs.add((Map<String, Object>) doc);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + CONFIG.toAbsolutePath() + " 失败", e);
        }
        assertThat(docs).as("%s 里没解析出任何配置段", CONFIG).isNotEmpty();
        return docs;
    }

    /** 按 spring 风格的 "a.b.c" 逐层取值，中途缺失就返回 null */
    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> doc, String dottedKey) {
        Object current = doc;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}

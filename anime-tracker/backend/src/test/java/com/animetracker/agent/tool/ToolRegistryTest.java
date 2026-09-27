package com.animetracker.agent.tool;

import com.animetracker.agent.tool.ToolDefinition.Access;
import com.animetracker.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具注册表测试.
 *
 * 它是权限的第一道闸门: 送给模型的工具清单就是在这里按身份裁出来的.
 * 裁错了, 模型就会看到一个它不该看到的工具.
 */
class ToolRegistryTest {

    private static final User NORMAL = User.builder().id(1L).username("u").role("USER").build();
    private static final User ADMIN = User.builder().id(2L).username("a").role("ADMIN").build();

    @Test
    @DisplayName("按身份裁剪工具清单")
    void filtersByAccess() {
        ToolRegistry registry = new ToolRegistry(List.of(provider(
                def("search_anime", Access.PUBLIC),
                def("list_my_tracking", Access.USER),
                def("platform_dashboard", Access.ADMIN))));

        assertThat(specNames(registry.specsFor(null))).containsExactly("search_anime");
        assertThat(specNames(registry.specsFor(NORMAL)))
                .containsExactlyInAnyOrder("search_anime", "list_my_tracking");
        assertThat(specNames(registry.specsFor(ADMIN))).hasSize(3);
    }

    @Test
    @DisplayName("角色大小写不影响管理员判定")
    void adminCheckIsCaseInsensitive() {
        ToolDefinition adminTool = def("platform_dashboard", Access.ADMIN);
        assertThat(ToolRegistry.allowed(adminTool, User.builder().role("admin").build())).isTrue();
        assertThat(ToolRegistry.allowed(adminTool, User.builder().role("Admin").build())).isTrue();
        assertThat(ToolRegistry.allowed(adminTool, NORMAL)).isFalse();
        assertThat(ToolRegistry.allowed(adminTool, null)).isFalse();
    }

    @Test
    @DisplayName("需要登录的工具对匿名访客一律不可见")
    void userToolsHiddenFromAnonymous() {
        ToolDefinition userTool = def("write_review", Access.USER);
        assertThat(ToolRegistry.allowed(userTool, null)).isFalse();
        assertThat(ToolRegistry.allowed(userTool, NORMAL)).isTrue();
    }

    @Test
    @DisplayName("重名工具直接让启动失败, 而不是带着不确定行为上线")
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                provider(def("search_anime", Access.PUBLIC)),
                provider(def("search_anime", Access.ADMIN)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("search_anime");
    }

    @Test
    @DisplayName("查找是精确匹配: 拼错的名字绝不能误命中另一个工具")
    void lookupIsExactMatch() {
        ToolRegistry registry = new ToolRegistry(List.of(provider(
                def("search_anime", Access.PUBLIC),
                def("search_animes", Access.PUBLIC))));

        assertThat(registry.find("search_anime").name()).isEqualTo("search_anime");
        assertThat(registry.find("search_anime_")).isNull();
        assertThat(registry.find("SEARCH_ANIME")).isNull();
        assertThat(registry.find("search")).isNull();
        assertThat(registry.find(null)).isNull();
    }

    @Test
    @DisplayName("工具定义非法时在构建阶段就报错")
    void validatesToolDefinitions() {
        // 工具名要能直接作为 JSON 的字段名, 大写和空格都不行
        assertThatThrownBy(() -> ToolDefinition.builder()
                .name("Bad Name").description("d").access(Access.PUBLIC)
                .executor((c, u) -> null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具名");

        // 没有说明的工具模型就无从判断何时该用, 这种工具等于没注册
        assertThatThrownBy(() -> ToolDefinition.builder()
                .name("ok").description("  ").access(Access.PUBLIC)
                .executor((c, u) -> null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具说明");

        assertThatThrownBy(() -> ToolDefinition.builder()
                .name("ok").description("d").access(Access.PUBLIC).build())
                .isInstanceOf(NullPointerException.class);
    }

    private static ToolProvider provider(ToolDefinition... defs) {
        return () -> List.of(defs);
    }

    private static List<String> specNames(List<com.animetracker.agent.llm.ToolSpec> specs) {
        return specs.stream().map(com.animetracker.agent.llm.ToolSpec::getName).toList();
    }

    private static ToolDefinition def(String name, Access access) {
        return ToolDefinition.builder()
                .name(name)
                .description("测试工具 " + name)
                .access(access)
                .executor((call, user) -> null)
                .build();
    }
}

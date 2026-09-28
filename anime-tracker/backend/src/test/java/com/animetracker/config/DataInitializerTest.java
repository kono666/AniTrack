package com.animetracker.config;

import com.animetracker.entity.User;
import com.animetracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号引导测试.
 *
 * 这个类跑在每个应用的启动路径上, 写错的话后果不是「某个功能不好用」,
 * 而是「生产环境凭空多出一个密码公开的管理员」或者「重启一次就登不进去」.
 * 所以几件容易被忽略的事各留一条测试钉住:
 *   - 没有凭据时宁可拒绝启动, 也不能退回默认密码
 *   - 已有管理员时彻底不动手 (重启幂等, 撤掉环境变量也不会拦启动)
 *   - 判断依据是「有没有管理员」而不是「有没有叫 admin 的用户」
 *   - 演示账号只在开关打开时创建
 */
class DataInitializerTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private BootstrapProperties props;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        // 用真实的编码器而不是 mock: 想验证的是「密码确实被哈希后入库」,
        // 把编码器换成 mock 就等于把这个断言也一起 mock 掉了.
        passwordEncoder = new BCryptPasswordEncoder();
        props = new BootstrapProperties();
    }

    /** 「全新的空库」场景 */
    private void freshDatabase() {
        when(userRepository.countByRole("ADMIN")).thenReturn(0L);
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
    }

    private DataInitializer initializer() {
        return new DataInitializer(userRepository, passwordEncoder, props);
    }

    @Test
    @DisplayName("空库时按配置的凭据创建管理员, 且密码哈希入库")
    void createsAdminFromConfig() {
        freshDatabase();
        props.setAdminUsername("root");
        props.setAdminPassword("a-very-strong-password");
        props.setAdminEmail("ops@example.com");

        initializer().run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getUsername()).isEqualTo("root");
        assertThat(saved.getRole()).isEqualTo("ADMIN");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getEmail()).isEqualTo("ops@example.com");

        assertThat(saved.getPassword()).isNotEqualTo("a-very-strong-password");
        assertThat(passwordEncoder.matches("a-very-strong-password", saved.getPassword())).isTrue();
    }

    @Test
    @DisplayName("没有密码时拒绝启动, 而不是退回一个默认密码")
    void refusesToStartWithoutPassword() {
        freshDatabase();
        props.setAdminPassword("");

        assertThatThrownBy(() -> initializer().run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未提供初始管理员凭据")
                .hasMessageContaining("ADMIN_PASSWORD");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("用户名只有空白同样拒绝启动")
    void refusesToStartWithBlankUsername() {
        freshDatabase();
        props.setAdminUsername("   ");
        props.setAdminPassword("some-password");

        assertThatThrownBy(() -> initializer().run())
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("已有管理员时彻底不动手: 重启不重建账号, 撤掉密码变量也不拦启动")
    void doesNothingWhenAdminAlreadyExists() {
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);
        // 生产环境常见情况: 第一次部署配了密码, 之后就把环境变量撤了
        props.setAdminPassword("");

        assertThatCode(() -> initializer().run()).doesNotThrowAnyException();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("管理员被改名后不会凭空再建一个")
    void doesNotRecreateAdminAfterRename() {
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);
        props.setAdminUsername("admin");
        props.setAdminPassword("");

        initializer().run();

        // 关键: 判断依据是「有没有管理员」, 所以不会去按用户名找 admin.
        // 若按用户名判断, 改名后这里就会再造一个多余的管理员账号.
        verify(userRepository, never()).existsByUsername("admin");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("演示账号开关关闭时不创建 (生产环境走的路径)")
    void skipsDemoUserWhenDisabled() {
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);
        props.setDemoUserEnabled(false);

        initializer().run();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("演示账号开关打开时创建 test, 角色为普通用户")
    void createsDemoUserWhenEnabled() {
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);
        when(userRepository.existsByUsername("test")).thenReturn(false);
        props.setDemoUserEnabled(true);

        initializer().run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getUsername()).isEqualTo("test");
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("演示账号已存在时不重复创建 (重启幂等)")
    void demoUserCreationIsIdempotent() {
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);
        when(userRepository.existsByUsername("test")).thenReturn(true);
        props.setDemoUserEnabled(true);

        initializer().run();

        verify(userRepository, never()).save(any());
    }
}

package com.animetracker.config;

import com.animetracker.entity.User;
import com.animetracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时的账号引导.
 *
 * 这里只负责一件事: 让一个空数据库变成「能登进去」的状态.
 * 一旦库里已经存在管理员, 它就完全不再插手 —— 重启多少次都不会
 * 重置或重建账号, 也不会因为没配密码而拦下启动.
 *
 * 改成从配置读取而不是写死密码, 理由很直接:
 * 写死的密码等于一个公开后门, 而它在 README 里被当成便利功能宣传过.
 * 方便 clone 下来就能跑的诉求是真实的, 但满足它的方式应该是
 * 「开发环境由配置提供兜底值」, 而不是「代码里永远有一个 admin123」.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** 开发用演示账号, 密码公开写在 README 里, 只能存在于本地 */
    private static final String DEMO_USERNAME = "test";
    private static final String DEMO_PASSWORD = "test123";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties props;

    public DataInitializer(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           BootstrapProperties props) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        bootstrapAdmin();
        bootstrapDemoUser();
    }

    /**
     * 库里没有任何管理员时, 用配置提供的凭据创建第一个.
     *
     * 判断依据是「有没有管理员」而不是「有没有叫 admin 的用户」——
     * 后者会在管理员被改名后误判, 又凭空造出一个多余的管理员账号.
     */
    private void bootstrapAdmin() {
        if (userRepository.countByRole(ROLE_ADMIN) > 0) {
            // 最常见的路径: 已经有管理员了, 什么都不做.
            // 这里刻意用 debug: 每次启动都刷一行 INFO 没有信息量.
            log.debug("已存在管理员账号, 跳过初始化");
            return;
        }

        String username = props.getAdminUsername();
        String password = props.getAdminPassword();

        if (isBlank(username) || isBlank(password)) {
            // 关键取舍: 没有凭据就让启动失败, 而不是退回一个默认密码.
            // 退回默认密码的后果是服务正常运行、没人察觉, 但任何人都能登进来.
            throw new IllegalStateException(
                    "系统还没有任何管理员账号, 但未提供初始管理员凭据, 应用拒绝启动。\n"
                            + "  原因: 库里一个管理员都没有, 需要一个入口账号才能管理系统。\n"
                            + "  解决: 设置环境变量\n"
                            + "        ADMIN_USERNAME=<管理员用户名>\n"
                            + "        ADMIN_PASSWORD=<一个足够强的密码>\n"
                            + "  说明: 只在「库里还没有管理员」时才需要提供, 之后重启不再要求。\n"
                            + "        不提供默认密码是刻意的 —— 默认密码等于一个公开后门。");
        }

        User admin = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role(ROLE_ADMIN)
                .status(STATUS_ACTIVE)
                .build();
        if (!isBlank(props.getAdminEmail())) {
            admin.setEmail(props.getAdminEmail());
        }
        userRepository.save(admin);

        // 只记录账号名, 不记录密码: 日志会被采集、转发、长期留存,
        // 把凭据写进去等于把它复制到很多没人看管的地方.
        log.warn("已创建初始管理员账号: {} (密码来自环境变量, 不打印到日志)", username);
        log.warn("请尽快登录并修改初始密码");
    }

    /** 开发用演示账号. 生产环境通过 app.bootstrap.demo-user-enabled=false 关掉 */
    private void bootstrapDemoUser() {
        if (!props.isDemoUserEnabled()) {
            return;
        }
        if (userRepository.existsByUsername(DEMO_USERNAME)) {
            return;
        }
        userRepository.save(User.builder()
                .username(DEMO_USERNAME)
                .password(passwordEncoder.encode(DEMO_PASSWORD))
                .role(ROLE_USER)
                .status(STATUS_ACTIVE)
                .build());
        log.warn("已创建开发环境演示账号: {} (仅用于本地演示, 生产环境不会创建)", DEMO_USERNAME);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

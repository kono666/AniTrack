package com.animetracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排除 UserDetailsServiceAutoConfiguration 是刻意的.
 *
 * 这条自动配置在没有自定义 UserDetailsService / AuthenticationProvider 时会
 * 凭空造一个内存用户, 并把随机密码打印到日志:
 *   Using generated security password: <uuid>
 *
 * 本项目的认证完全由 JwtAuthFilter 承担, HttpSecurity 上既没有 formLogin 也没有
 * httpBasic, 所以那个内存用户没有任何入口, 无害. 但日志里出现"生成的密码"这件事本身
 * 是个坏信号: 任何接手代码的人或安全审计看到它, 都得停下来确认是不是留了后门;
 * 而日志又会被采集和长期留存 —— 与 DataInitializer 里"不把密码写进日志"的取舍自相矛盾.
 * 既然它提供的 bean 一个都没被用到, 直接关掉, 让启动日志里不再有这一行.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableCaching
@EnableScheduling
public class AnimeTrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnimeTrackerApplication.class, args);
    }
}

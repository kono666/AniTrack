package com.animetracker.config;

import com.animetracker.entity.User;
import com.animetracker.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // 初始化管理员
        if (!userRepository.existsByUsername("admin")) {
            userRepository.save(User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .email("admin@anitrack.com")
                    .role("ADMIN")
                    .status("ACTIVE")
                    .build());
            System.out.println("✓ 管理员账号已创建: admin / admin123");
        }

        // 初始化测试用户
        if (!userRepository.existsByUsername("test")) {
            userRepository.save(User.builder()
                    .username("test")
                    .password(passwordEncoder.encode("test123"))
                    .email("test@anitrack.com")
                    .role("USER")
                    .status("ACTIVE")
                    .build());
            System.out.println("✓ 测试用户已创建: test / test123");
        }
    }
}

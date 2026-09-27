package com.animetracker.service;

import com.animetracker.config.JwtUtil;
import com.animetracker.dto.RequestDTO.*;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 注册 */
    public Map<String, Object> register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw BusinessException.badRequest("用户名已存在");
        }
        User user = User.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .email(req.getEmail())
                .build();
        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        return data;
    }

    /** 登录 */
    public Map<String, Object> login(LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> BusinessException.badRequest("用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw BusinessException.badRequest("用户名或密码错误");
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw BusinessException.forbidden("账号已被禁用，请联系管理员");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("avatar", user.getAvatar());
        data.put("role", user.getRole());
        data.put("status", user.getStatus());
        return data;
    }

    /** 获取用户信息 */
    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
    }
}

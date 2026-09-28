package com.animetracker.service;

import com.animetracker.entity.Anime;
import com.animetracker.entity.User;
import com.animetracker.entity.Review;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.AnimeRepository;
import com.animetracker.repository.UserRepository;
import com.animetracker.repository.ReviewRepository;
import com.animetracker.repository.TrackingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminService {

    /**
     * 允许被写入 user.role 的值. 与鉴权侧认的字面量必须一致.
     *
     * <p>用 LinkedHashSet 而不是 Set.of: 这个集合会被拼进给用户看的提示语, 而
     * Set.of 的迭代顺序**每次启动都不一样**(不可变集合带了随机化的哈希盐),
     * 于是同一段代码在两次启动里会提示出「ADMIN / USER」和「USER / ADMIN」两种
     * 说法. 不是功能错误, 但会让报错截图和代码对不上, 也让「消息里有什么」这件事
     * 无法被测试钉住. 保持声明顺序即可.
     */
    private static final Set<String> ROLES =
            Collections.unmodifiableSet(new LinkedHashSet<>(List.of("USER", "ADMIN")));

    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final TrackingRepository trackingRepository;
    private final AnimeRepository animeRepository;

    public AdminService(UserRepository userRepository,
                        ReviewRepository reviewRepository,
                        TrackingRepository trackingRepository,
                        AnimeRepository animeRepository) {
        this.userRepository = userRepository;
        this.reviewRepository = reviewRepository;
        this.trackingRepository = trackingRepository;
        this.animeRepository = animeRepository;
    }

    /** 校验管理员身份 */
    public void checkAdmin(User user) {
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw BusinessException.forbidden("无管理员权限");
        }
    }

    // ========== 用户管理 ==========

    /** 获取所有用户列表 */
    public List<Map<String, Object>> getUserList() {
        List<User> users = userRepository.findByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("email", u.getEmail());
            map.put("role", u.getRole());
            map.put("status", u.getStatus());
            map.put("createdAt", u.getCreatedAt());
            // 给管理端看的锁定状态. 用 isLocked() 而不是「lockedUntil 非空」:
            // 过期的锁定时间戳仍然留在字段里, 按非空判断会把早已自动解锁的账号
            // 显示成「已锁定」, 管理员就会去点一个没有意义的解锁按钮.
            map.put("locked", u.isLocked());
            map.put("lockedUntil", u.isLocked() ? u.getLockedUntil() : null);
            result.add(map);
        }
        return result;
    }

    /** 禁用/启用用户 */
    public void toggleUserStatus(Long targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if ("ADMIN".equals(user.getRole())) {
            throw BusinessException.badRequest("不能操作管理员账号");
        }
        user.setStatus("ACTIVE".equals(user.getStatus()) ? "DISABLED" : "ACTIVE");
        userRepository.save(user);
    }

    /**
     * 解除登录失败锁定.
     *
     * 存在的理由: 限时锁定虽然有「等 15 分钟自动解锁」这条出口, 但用户自己
     * 没有任何办法知道这一点, 也不知道等了多久. 而且如果有人恶意连试 5 次
     * 把某个账号锁上, 那 15 分钟里这个用户是完全无法自助恢复的.
     * 管理员需要一个能立刻解开的手动出口.
     */
    public void unlockUser(Long targetUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
    }

    /**
     * 修改用户角色.
     *
     * <p>以前这个方法只有「查出来、set、存回去」三步, 角色值原样落库. 三个口子
     * 合起来能把人锁在门外:
     *
     * <ul>
     *   <li>角色值不校验 —— 传个 "ADMIN " / "admin" / "SUPER" 都能存进去.
     *       而鉴权那一侧认的是字面量 "ADMIN", 于是库里出现一个「看名字像管理员、
     *       实际什么权限都没有」的账号, 没有任何地方会报错;</li>
     *   <li>能改自己 —— 管理员手滑把自己降成 USER, 当场失去管理端, 而且没有
     *       任何自助恢复的入口(改角色这个动作本身就要管理员权限);</li>
     *   <li>能把最后一个管理员降级 —— 结果同上, 只是需要别人来点这一下:
     *       整个系统再没有任何账号进得了管理端.</li>
     * </ul>
     *
     * <p>角色用白名单而不是黑名单: 这一列直接决定能拿到哪些接口, 黑名单漏一个值就是
     * 一个越权口子, 白名单漏了顶多是「某个合法值暂时用不了」, 失败方向是安全的.
     *
     * <p>残留的窗口: 「数到还剩一个管理员」和「写下去」之间不是原子的, 两个管理员
     * 同时降级对方时理论上都能通过检查. 要彻底堵死得靠数据库层的约束或加锁,
     * 而这里要防的是手滑和误操作, 不是两个管理员合谋把自己锁死, 所以没上那一层.
     */
    public void setUserRole(User actor, Long targetUserId, String role) {
        if (role == null || !ROLES.contains(role)) {
            throw BusinessException.badRequest(
                    "角色只能是 " + String.join(" / ", ROLES) + " 之一");
        }

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));

        if (actor != null && actor.getId().equals(target.getId())) {
            throw BusinessException.badRequest("不能修改自己的角色");
        }

        // 不变式: 这次操作之后, 系统里至少还得剩下一个管理员
        if ("ADMIN".equals(target.getRole()) && !"ADMIN".equals(role)
                && userRepository.countByRole("ADMIN") <= 1) {
            throw BusinessException.badRequest("这是最后一个管理员, 不能降级");
        }

        target.setRole(role);
        userRepository.save(target);
    }

    // ========== 评论管理 ==========

    /**
     * 获取所有评论列表.
     *
     * <p>用 JOIN FETCH 一次把作者带回来: {@code Review.user} 是 LAZY 的, 而每一行
     * 都要读作者名和 id —— 不 fetch 就是「有几条评论就查几次用户」. 评论越多越慢,
     * 而这是管理端首页, 恰好是评论最多的那类库最常被打开.
     */
    public List<Map<String, Object>> getAllReviews() {
        List<Review> reviews = reviewRepository.findAllWithUser(Pageable.unpaged());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Review r : reviews) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("subjectId", r.getSubjectId());
            map.put("username", r.getUser().getUsername());
            map.put("userId", r.getUser().getId());
            map.put("rating", r.getRating());
            map.put("content", r.getContent());
            map.put("createdAt", r.getCreatedAt());
            result.add(map);
        }
        return result;
    }

    /** 管理员删除任意评论 */
    public void deleteAnyReview(Long reviewId) {
        reviewRepository.deleteById(reviewId);
    }

    // ========== 数据统计 ==========

    /** 系统仪表盘数据 */
    public Map<String, Object> getDashboard() {
        Map<String, Object> data = new HashMap<>();
        data.put("totalUsers", userRepository.count());
        data.put("adminUsers", userRepository.countByRole("ADMIN"));
        data.put("activeUsers", userRepository.countByStatus("ACTIVE"));
        data.put("disabledUsers", userRepository.countByStatus("DISABLED"));
        data.put("totalReviews", reviewRepository.count());
        data.put("totalTrackings", trackingRepository.count());
        return data;
    }

    /**
     * 全站热度榜: 追番人数最多的前 N 部作品.
     *
     * 用数据库层 GROUP BY 聚合, 而不是逐个番剧去数 —— 后者是典型的 N+1 查询,
     * 番剧一多就会把数据库打满.
     */
    public List<Map<String, Object>> getAnimeHeatRanking(int topN) {
        List<Object[]> rows = trackingRepository.findSubjectTrackingCounts(PageRequest.of(0, topN));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> subjectIds = rows.stream()
                .map(r -> (Integer) r[0])
                .collect(Collectors.toList());

        Map<Integer, Anime> byId = animeRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(Anime::getId, a -> a, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Integer subjectId = (Integer) row[0];
            long count = ((Number) row[1]).longValue();
            Anime anime = byId.get(subjectId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subjectId", subjectId);
            item.put("name", displayName(anime));
            item.put("trackingCount", count);
            item.put("rating", anime != null ? anime.getRating() : null);
            // 这个榜目前只被 AI 助手消费, 它要把榜单渲染成卡片, 没有封面就只剩一排文字
            item.put("cover", anime != null ? anime.getCoverUrl() : null);
            result.add(item);
        }
        return result;
    }

    /** 优先用中文名, 缺失时回退日文原名 */
    private String displayName(Anime anime) {
        if (anime == null) {
            return "未知作品";
        }
        if (anime.getTitleCn() != null && !anime.getTitleCn().isBlank()) {
            return anime.getTitleCn();
        }
        return anime.getTitle();
    }
}

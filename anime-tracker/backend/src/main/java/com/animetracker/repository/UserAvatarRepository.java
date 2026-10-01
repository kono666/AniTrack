package com.animetracker.repository;

import com.animetracker.entity.UserAvatar;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 头像的读与写。
 *
 * <p>没有任何自定义查询, 这是**刻意的** —— 这张表的访问形状只有两种, 两种都被
 * {@link JpaRepository} 覆盖:
 *
 * <ul>
 *   <li>按主键取一行({@code findById})→ 回图那个端点;</li>
 *   <li>按主键写/删一行({@code save} / {@code deleteById})→ 上传与删除。</li>
 * </ul>
 *
 * <p>三个端点都是"操作自己"的, 用户 id 从登录态来, 所以这里没有、也不该有
 * 「按用户名查头像」之类的口子 —— 那只会多一条要单独审的越权路径。公开读的那个端点
 * ({@code GET /api/user/{id}/avatar})也是按主键取, 而且**只能读到图片**:
 * 它回的是字节与 Content-Type, 没有任何用户资料。头像本身是公开展示的东西
 * (评论区要给未登录访客显示), 这一点是设计如此, 不是漏配。
 */
public interface UserAvatarRepository extends JpaRepository<UserAvatar, Long> {
}

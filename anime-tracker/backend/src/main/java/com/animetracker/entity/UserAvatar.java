package com.animetracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 一个用户的头像图片本身。
 *
 * <p><b>这张表与 {@code user.avatar} 不是同一个事实的两份副本</b>, 这是读这个类时最
 * 容易搞混的一点:
 *
 * <ul>
 *   <li>{@code user_avatar.bytes} 存的是**图片内容**;</li>
 *   <li>{@code user.avatar} 存的是**指向它的地址**({@code /api/user/{id}/avatar})。</li>
 * </ul>
 *
 * 后者这么写是为了不动读路径 —— 全仓有 6 处读 {@code user.avatar}(评论列表、回复、
 * 通知、个人页…), 它们原本就只看这一个字符串、前端拿不到就退回首字母。把 URL 写进
 * 那一列之后, 这 6 处**一处都不用改**。要守的纪律只有一条: <b>这个 URL 只允许有一个
 * 定义处</b>({@link com.animetracker.service.AvatarService#urlOf}), 别在别处手拼 ——
 * 改路由时就要配一条数据迁移, 这是把地址存进库的唯一代价。
 *
 * <p><b>{@code bytes} 必须是裸 {@code byte[]}, 绝不能加 {@code @Lob}。</b>
 * {@code @Lob} 会让 Hibernate 按 BLOB 映射(H2 侧与 V12 建出来的 VARBINARY 对不上),
 * 在 PostgreSQL 上更糟 —— 它映射成 {@code oid}, 那是另一种完全不同的类型。
 * 两个方向都会被 {@code ddl-auto: validate} 在启动时拦下来。
 *
 * <p><b>主键就是 {@code user_id}, 没有自增 id。</b> 一人一张, 于是 {@code save} 天然
 * 是 upsert(有则覆盖、无则新建), 上传路径不需要「先查再决定 insert 还是 update」。
 *
 * <p>删除靠库级的 {@code ON DELETE CASCADE}(V12): 用户没了, 头像就是一条谁也读不到
 * 的孤儿行。这也是全 schema 唯一一个对 {@code "user"} 挂级联的外键, 理由写在 V12 头部
 * —— 与 V7/V8「user_id 不级联」那条规矩不冲突(那条防的是"级联删子行而计数器不跟着减",
 * 这里没有计数器)。
 */
@Entity
@Table(name = "user_avatar")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAvatar {

    /** 与 {@code user.id} 同值, 也是这张表的主键 —— 见类注释 */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * 图片的真实 MIME 类型, 只可能是 {@code image/png} 或 {@code image/jpeg}。
     *
     * <p><b>它是从字节里判出来的, 不是请求里带的那个字符串</b>(见
     * {@link com.animetracker.service.AvatarService})。请求头里的 Content-Type 是调用方
     * 自己写的, 一个把 .exe 改名成 .png 的文件照样能声明成 {@code image/png};
     * 而这一列最终会变成响应头回给浏览器 —— 存错了就不只是"显示不出来", 而是让浏览器
     * 按声明去解析一段任意字节。
     */
    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    /**
     * 图片字节。见类注释: 裸 {@code byte[]}, 不要加 {@code @Lob}。
     *
     * <p>{@code @ToString.Exclude} 不是洁癖: 少了它, 任何一处「不小心打了个日志」
     * ({@code log.debug("...{}", avatar)} 或异常里带上实体)都会把上限 512KB 的二进制
     * 逐字节打进日志。而日志是唯一一种"写进去就收不回来"的输出。
     */
    @ToString.Exclude
    @Column(name = "bytes", nullable = false)
    private byte[] bytes;

    /**
     * 最后一次上传/覆盖的时刻。
     *
     * <p>它有两个用途, 都不是"给人看的": 读路径拿它当 <b>ETag</b> 与
     * {@code Cache-Control: max-age} 的取值来源, 前端也用它拼 {@code ?v=} 让浏览器
     * 重新取图。所以覆盖上传时**必须**重新赋值 —— 少了它, 用户换了头像而浏览器一直
     * 拿缓存里的旧图, 那是这个功能最容易出现也最难解释的一种"没生效"。
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

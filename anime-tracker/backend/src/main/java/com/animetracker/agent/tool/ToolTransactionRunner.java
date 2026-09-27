package com.animetracker.agent.tool;

import com.animetracker.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把「一次工具执行」包进一个独立事务.
 *
 * <b>为什么必须有它</b>
 *
 * Agent 循环跑在 SSE 的异步线程上, 而 JPA 的 EntityManager 是绑定线程的.
 * 项目里没关 open-in-view, 请求线程上的懒加载一直能正常工作, 所以此前没人察觉;
 * 一旦换到异步线程, 那里没有 EntityManager, 所有懒加载都会抛 LazyInitializationException.
 * 在这里开一个 REQUIRES_NEW 事务, 工具内部就获得了自己的持久化上下文,
 * 关联对象照常按需加载.
 *
 * <b>为什么不干脆把整个 Agent 循环包进一个事务</b>
 *
 * 那样事务会横跨多次大模型网络调用(每轮几十秒), 整个过程中一直占着一条数据库连接.
 * 并发几个用户就能把连接池耗光. 按「每次工具调用」切分, 连接只在真正读写数据库的
 * 那几十毫秒里被占用, 等待模型的时间不占资源.
 *
 * <b>顺带解决的问题</b>
 *
 * 传进来的 User 来自请求线程, 已经脱离持久化上下文. 这里把它 merge 成当前事务管理的实例,
 * 之后用它做查询条件、挂到新建的追番/评论记录上都不会出问题.
 */
@Component
public class ToolTransactionRunner {

    @PersistenceContext
    private EntityManager entityManager;

    /** 允许抛受检异常, 避免在 Lambda 里被迫写 try/catch */
    @FunctionalInterface
    public interface ToolWork {
        Object run(User managedUser) throws Exception;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object run(User detachedUser, ToolWork work) throws Exception {
        User managed = detachedUser == null ? null : entityManager.merge(detachedUser);
        return work.run(managed);
    }
}

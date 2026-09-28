package com.animetracker.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 把一次「可能撞唯一约束的插入」放进独立事务, 好让调用方安全地捕获冲突.
 *
 * <b>为什么不能直接在 service 里 try/catch</b>
 *
 * 唯一约束冲突会把**当前事务**标记成 rollback-only. 如果这次插入跑在别人的事务里,
 * 冲突就把那个事务一起毒掉了: 调用方即使当场捕获了异常, 它所在的事务在提交时
 * 照样抛 UnexpectedRollbackException, 后面所有补救(重查、改写)全部作废.
 *
 * 这不是假想的场景 —— Agent 的工具调用正是这样: AgentOrchestrator 用
 * {@link com.animetracker.agent.tool.ToolTransactionRunner} 给每次工具执行开了一个
 * REQUIRES_NEW 事务, 追番/评论工具就泡在里面. 不隔离的话, 网页接口能自愈,
 * 换 Agent 走一遍就变成「执行失败」.
 *
 * <b>为什么是 REQUIRES_NEW</b>
 *
 * 它在两种情形下都对:
 * <ul>
 *   <li>没有外层事务(网页请求就走这条): 等价于「开一个新事务」, 行为与不用它一样;</li>
 *   <li>有外层事务(Agent 工具): 挂起外层, 用自己那个事务去做插入. 失败只回滚自己,
 *       外层毫发无损, 调用方捕获异常后能继续在外层事务里重查和改写.</li>
 * </ul>
 * 换成 NESTED(savepoint)反而两头不讨好: 有外层事务时它确实能局部回滚, 但没外层事务时
 * 它就退化成 REQUIRED, 那种情况下 catch 又会被毒化 —— 两种情形只能顾一头.
 *
 * <b>代价</b>
 *
 * 有外层事务时会短暂同时占用两条数据库连接(外层挂起但没释放). 这里只在「查不到、
 * 真要插入」这一个分支上用它, 并发量又低, 不值得为省一条连接把正确性让出去.
 */
@Component
public class IsolatedInsert {

    /**
     * 在独立事务里执行插入.
     *
     * 注意入参只是「干活的那段代码」, 不接收也不返回托管实体 —— 这个事务结束后
     * 里面的实体就脱离持久化上下文了, 调用方只能拿它的普通字段, 不能碰懒加载关联.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T attempt(Supplier<T> insert) {
        return insert.get();
    }
}

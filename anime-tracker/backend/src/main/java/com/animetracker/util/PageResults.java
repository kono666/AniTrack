package com.animetracker.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分页响应的 {@code {list, total, page}} 信封 —— 读路径只此一份.
 *
 * <p><b>为什么搬出来成了公共的.</b> 这三行本来长在
 * {@code AnimeService.pageResult(..)} 里, 是私有的. 用户管理那一页要分页之后,
 * 同一个信封就有了第二个消费者: 抄一份是四行, 看着很划算, 但下面那条不变量
 * 一旦在某一侧漂掉, <b>没有任何测试会抓得到</b> —— 它不是一个会抛异常的错误,
 * 只是偶尔少报几行. 所以宁可多这一层间接.
 *
 * <p><b>这个信封的约定</b>: 三个键嵌在 {@code ApiResponse} 的 {@code data} 里,
 * 与 {@code ReviewService}、{@code AnimeService} 的读路径一致. {@code ApiResponse}
 * 自己那三个同名的 {@code page}/{@code pageSize}/{@code total} 字段是另一回事 ——
 * 它们从来没有调用方, 别把这两个形状混起来.
 */
public final class PageResults {

    private PageResults() {
    }

    /**
     * SQL 层能接受的最大起点.
     *
     * <p>切片下推之后, 起点最终交给 {@code Query.setFirstResult(int)} —— 是个 int.
     * 而 {@code (page-1)*limit} 可以在 int 里溢出成负数({@code AnimeService} 里那段
     * 注释记着的老 bug). 溢出之后库收到的是"从负数开始取一页", 两个库的表现既不统一,
     * 也不报错. 所以在自己的 long 算式里先把它接住, 超了就返回空页.
     *
     * <p>调用方的形状固定是
     * {@code if (offset >= total || offset > PageResults.MAX_SQL_OFFSET)} ——
     * 两个条件合成一条出口, 因为两者的结果都是"这一页没有行".
     */
    public static final long MAX_SQL_OFFSET = Integer.MAX_VALUE;

    /**
     * 组装一页.
     *
     * @param list  这一页的行, 已经由数据库切好
     * @param total 与取页<b>同一份 WHERE</b> 算出来的匹配总数
     * @param page  回显的页码(调用方夹取过的那个)
     */
    public static Map<String, Object> of(List<?> list, int total, int page) {
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        // 上报值不得小于手上真实有的行数: count 与取页是两次查询, 期间有写入的话
        // 这一页可能比 count 报的还长. 报小的会让用户看不到自己已经看到的那些行.
        data.put("total", Math.max(total, list.size()));
        data.put("page", page);
        return data;
    }
}

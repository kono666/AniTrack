package com.animetracker.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「季度」筛选条件 → {@code anime.season} 上的一段闭区间.
 *
 * <p><b>为什么需要它.</b> {@code anime.season} 存的是<b>真实月份</b>
 * ({@link AnimeFields#seasonOf} 的结果, 形如 {@code 2024-10}), 而筛选的谓词一直是
 * 精确等值. 于是「2024 年秋季」这个再自然不过的条件<b>无法表达</b>: 发
 * {@code 2024-10} 只能拿到十月那 147 部, 十一月(58)与十二月(89)静默漏掉 ——
 * 接口照常返回 200, 总数看着也挺像回事, 没有任何迹象说明少了两个月.
 *
 * <p>要修就必须能在 SQL 上表达「十月到十二月」, 也就是把输入的 {@code 2024-Q4}
 * 展开成 {@code 2024-10 .. 2024-12}. 展开放在 Java 侧而不是让 SQL 去算: JPQL 里
 * 没有能安全拼月份的函数, 而且一旦让 SQL 参与解析, 「同一份口径」就有了两个物理
 * 位置 —— 这个项目在 {@code AnimeQueries} 的类注释里已经为这类事付过一次代价.
 *
 * <p><b>为什么是范围而不是月份列表.</b> 展开成 {@code (2024-10, 2024-11, 2024-12)}
 * 再传给 {@code IN}, 语义一样, 但「不限季度」时 {@code IN} 要绑一个 null 集合,
 * 那在 JPQL 上属于 Hibernate 的实现细节而不是语言保证({@code TAG_GROUP_MATCHES}
 * 那条常量记着同一课). 范围的两个端点都是普通绑定参数, 未选中时传 null 即可,
 * 而且 {@code from == to} 时它**正好退化成原来的精确等值** —— 一个形状同时覆盖
 * 「月」与「季」, 不需要为旧写法分叉.
 *
 * <p><b>为什么非法值不报错.</b> {@code 2024-Q5} / {@code 2024-q4} / 一段乱码
 * 都落进「其它值」那一支, 退化成精确等值, 结果是筛空. 这是这个参数**一直以来的
 * 行为**(改动前任何非 {@code yyyy-MM} 的值都是筛空), 保持它意味着这次改动不会
 * 把任何一个既有调用方从「拿到空结果」变成「拿到 400」. AI 工具的对外契约也是
 * 这么写的: 它只说 {@code season} 是什么格式, 没说过格式不对会报错.
 */
public record SeasonRange(String from, String to) {

    /**
     * {@code yyyy-Qn}, 大小写敏感、补零四位.
     *
     * <p>刻意不收 {@code 2024-q4} / {@code 24-Q4}: 「认不出的值一律精确等值」
     * 这条规则只有在"能认的集合"很小且明确时才安全 —— 一旦开始放宽大小写、
     * 补零, 下一个问题就是 {@code 2024-Q04} 算不算, 而每个放宽点都是一处
     * 与前端不一致的机会.
     */
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$");

    /**
     * 解析一个季度条件.
     *
     * @param raw 线路上的原值; {@code null} / 空白返回 {@code null}, 表示「不限季度」
     *            —— 与 {@code FILTER_SEASON} 里那个 {@code :seasonFrom IS NULL} 守卫
     *            成对, 是"没有条件"的唯一表达
     * @return {@code yyyy-Qn} 展开后的首末月; 其它任何非空值退化成
     *         {@code from == to == 原值} 的精确等值
     */
    public static SeasonRange parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Matcher m = QUARTER.matcher(value);
        if (!m.matches()) {
            return new SeasonRange(value, value);
        }
        int year = Integer.parseInt(m.group(1));
        // 季度 1/2/3/4 → 起始月 1/4/7/10. Q4 的末月是 12 而不是次年的 1: 这里是
        // 同一年的字符串比较, 跨年会在字典序上排到最前, 把整个 Q4 变成空集.
        int startMonth = (Integer.parseInt(m.group(2)) - 1) * 3 + 1;
        return new SeasonRange(
                String.format("%04d-%02d", year, startMonth),
                String.format("%04d-%02d", year, startMonth + 2));
    }

    /**
     * 这个区间是不是「一个月」—— 即它来自 {@code yyyy-MM} 而不是 {@code yyyy-Qn}.
     *
     * <p>给测试与日志用(断言"老写法的行为一字未变"时要能一眼看出走的是哪一支),
     * 查询本身不依赖它: 两种情形在 SQL 上是同一条范围谓词.
     */
    public boolean isSingleMonth() {
        return from.equals(to);
    }
}

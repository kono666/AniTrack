package com.animetracker.util;

/**
 * 把用户输入的关键词变成 LIKE 的模式串.
 *
 * <p>为什么必须要有这么个东西: {@code %} 与 {@code _} 是 LIKE 的通配符, 而它们
 * 直接从搜索框进来. 改动前搜一个 {@code %} 会命中**全部**行(SQL 变成
 * {@code LIKE '%%%'}), 搜 {@code a_c} 会连 {@code abc} 一起命中. 用户以为在搜一个字面量,
 * 接口在按通配符解释 —— 这是"不报错、结果是错的"那一类, 只看返回值看不出问题.
 *
 * <p>为什么转义字符选 {@code !} 而不是反斜杠: 反斜杠同时是 HQL 字符串字面量、H2 的
 * LIKE 默认转义字符、PostgreSQL 的 LIKE 默认转义字符三套规则的交汇点, 而且 Hibernate 6
 * 自己还会往 SQL 里塞 {@code escape ''}(HHH-15745 那阵子它在反斜杠上出过异常).
 * {@code !} 在 HQL 字面量、H2、PG 里都不特殊; 一旦显式写了 {@code ESCAPE '!'},
 * 它就会**取代**两个库默认的反斜杠(PG 手册原话: 选了别的转义字符之后, 反斜杠对 LIKE
 * 不再特殊), 于是连反斜杠本身都不用转义. 这是唯一一处不需要额外解释的选择.
 *
 * <p>不在这里做大小写折叠: 折叠交给 SQL 的 {@code LOWER()}(见
 * {@code AnimeRepository.searchByKeywordPattern}), 让模式串与列用**同一个数据库**的
 * 规则折叠 —— 在 Java 里 toLowerCase 再交给库比较, 两边规则不一致时(土耳其语 İ 那类)
 * 会静默漏匹配.
 */
public final class SearchPatterns {

    /**
     * 转义字符. 必须与 {@code AnimeRepository} 里 JPQL 的 {@code ESCAPE '!'} 保持一致 ——
     * 改一处忘另一处的话, 转义会静默失效(模式串里带着 ! 而 SQL 不认识它), 表现就是
     * 搜 {@code 100%} 又变回命中全部.
     */
    public static final char ESCAPE = '!';

    private SearchPatterns() {
    }

    /**
     * 关键词 → 包含匹配的模式串.
     *
     * <p>例: {@code "EVA" → "%EVA%"}; {@code "100%"} → {@code "%100!%%"};
     * {@code "a_b"} → {@code "%a!_b%"}; {@code "a!b"} → {@code "%a!!b%"}.
     * 入参为 null 时返回 null(调用方负责保证有关键词才走这条路).
     */
    public static String contains(String keyword) {
        if (keyword == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(keyword.length() + 8).append('%');
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            // 转义字符自己也要转义: 否则 "a!b" 里的 "!b" 会被当成"转义后的 b", 于是
            // 用户搜 "a!b" 实际匹配的是 "ab" —— 同一类静默错, 只是更难想到.
            if (c == ESCAPE || c == '%' || c == '_') {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }
}

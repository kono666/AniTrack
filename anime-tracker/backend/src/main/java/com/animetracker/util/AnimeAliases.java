package com.animetracker.util;

import com.animetracker.dto.BangumiDTO.InfoboxItem;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bangumi infobox 里的「别名」→ 一行逗号分隔的字符串(anime.aliases).
 *
 * <p>为什么需要它: 搜索此前只匹配 title(日文原名)与 title_cn(中文名). 于是
 * "EVA"、"Neon Genesis Evangelion"、"Shin Seiki Evangerion" 这类罗马音/英文/
 * 其它地区译名一个都搜不到 —— 而它们一直躺在 infobox 里
 * ({@code {"key":"别名","value":[{"v":"EVA"},...]}}), 只是 {@code SubjectDTO}
 * 没声明 infobox 这个字段, 整块被 Jackson 丢掉了. 表现是: 接口回 0 条 → 回源 Bangumi
 * (它是按别名匹配的, 能搜到) → 落库 → **又用同一条只匹配中/日名的 LIKE 查本地** →
 * 还是 0 条. 数据落了库, 界面上却是空的.
 *
 * <p>口径: **只取 key 为「别名」的那一项**, 只取数组元素里的 {@code v};
 * {@code k}(地区/版本注记, 形如"中国大陆公映版译名")不带进来 —— 它是对这条别名出处的
 * 说明, 不是别名本身, 带进来只会让"搜中国大陆公映版译名"这类词意外命中一堆条目.
 * 去重按出现顺序(LinkedHashSet), 空白项丢弃.
 *
 * <p>纯函数: 不碰数据库、不看时钟, 所以 infobox 的每一种形状都能用单测钉死 ——
 * 而它的形状确实不止一种(见 {@link #collect}).
 */
public final class AnimeAliases {

    /** infobox 里别名的 key. 是 Bangumi 的中文 key, 不是本地化过的展示文案. */
    public static final String INFOBOX_KEY = "别名";

    /**
     * 存进 anime.aliases 的最大长度.
     *
     * <p>必须与 {@code db/migration/{h2,postgres}/V5__add_anime_aliases.sql} 的列宽、
     * 以及实体上的 {@code @Column(length = 1000)} 三处一致. 这不是洁癖: 别名一多,
     * 超长的那次 INSERT 会直接失败, 而它发生在"搜索回源落库"这条路上 —— 结果是
     * **把一次搜索打成 500**, 比搜不到更糟. 列宽与 Java 侧截断两道防线都要有.
     */
    public static final int MAX_STORED_LENGTH = 1000;

    private AnimeAliases() {
    }

    /**
     * infobox → {@code "别名1,别名2,..."}; 抽不出任何别名时返回 null.
     *
     * <p>返回 null 而不是空串, 是为了让调用方能区分"这个条目没有别名"和"拿到了空别名",
     * 从而沿用 {@code AnimeService.applySubject} 里"有值才覆盖"的规矩 ——
     * 无条件写空串会把详情接口辛苦拿到的别名抹掉(搜索接口不是每个条目都带 infobox).
     */
    public static String join(List<InfoboxItem> infobox) {
        if (infobox == null || infobox.isEmpty()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (InfoboxItem item : infobox) {
            if (item == null || !INFOBOX_KEY.equals(item.getKey())) {
                continue;
            }
            collect(item.getValue(), values);
        }
        if (values.isEmpty()) {
            return null;
        }
        String joined = String.join(",", values);
        return joined.length() <= MAX_STORED_LENGTH ? joined : joined.substring(0, MAX_STORED_LENGTH);
    }

    /**
     * 从 infobox 一项的 value 里把别名抠出来.
     *
     * <p>为什么写得这么啰嗦: 同一个 infobox 里, value 的形状**不固定**.
     * 实测({@code GET https://api.bgm.tv/v0/subjects/265}):
     * {@code "话数"} 的值是字符串 {@code "26"}, 而 {@code "别名"} 的值是对象数组
     * {@code [{"v":"EVA"},{"k":"中国大陆公映版译名","v":"天鹰战士"}]}. 官方文档没写这件事,
     * 只按一种形状反序列化的话, 另一种会静默变成空.
     */
    private static void collect(JsonNode value, Set<String> out) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            for (JsonNode node : value) {
                collect(node, out);
            }
            return;
        }
        // 对象取 v; 纯字符串(个别条目的别名就是这么给的, 例如 "别名": "EVA")直接用
        String raw = value.isObject() ? value.path("v").asText("") : value.asText("");
        String trimmed = raw.trim();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }
}

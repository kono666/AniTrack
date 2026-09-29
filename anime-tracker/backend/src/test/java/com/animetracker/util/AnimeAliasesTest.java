package com.animetracker.util;

import com.animetracker.dto.BangumiDTO.InfoboxItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bangumi infobox 的「别名」怎么被抠出来.
 *
 * <p>为什么这个纯函数值得单独一组用例: infobox 的 value 形状**不固定**, 而官方文档没写
 * 这件事. 实测({@code GET https://api.bgm.tv/v0/subjects/265})里 {@code "话数"} 的值是
 * 字符串 {@code "26"}, {@code "别名"} 的值是对象数组
 * {@code [{"v":"EVA"},{"k":"中国大陆公映版译名","v":"天鹰战士"}]}. 只按一种形状写,
 * 另一种会静默变成空 —— 而"别名是空的"表现就是搜索又搜不到了, 与这个类要修的那个 bug
 * 长得一模一样, 分不清是没修好还是又坏了.
 *
 * <p>JSON 走真实的 ObjectMapper 而不是手搓 JsonNode: 要验的恰恰是"Jackson 交到手上的形状",
 * 自己造一个理想的 JsonNode 就把这一层绕过去了.
 */
class AnimeAliasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 造一个 infobox 项. json 是 value 的原文, 保持它在响应里的样子 */
    private static InfoboxItem item(String key, String json) {
        InfoboxItem it = new InfoboxItem();
        it.setKey(key);
        try {
            it.setValue(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试用的 JSON 写错了: " + json, e);
        }
        return it;
    }

    private static List<InfoboxItem> infoboxOf(InfoboxItem... items) {
        return new ArrayList<>(List.of(items));
    }

    // ========== 正向 ==========

    /**
     * 现实里最常见的那一种: 对象数组, 部分元素带 k(地区/版本注记).
     *
     * <p>断言里有两条是重点: ①{@code k} 不进结果 —— 它是"这条别名是什么来头"的说明,
     * 混进来会让搜"中国大陆公映版译名"这种词意外命中; ②重复的 EVA 只留一个.
     */
    @Test
    @DisplayName("对象数组: 只取 v, k 不要, 重复的按出现顺序去重")
    void takesOnlyVAndDeduplicates() {
        List<InfoboxItem> infobox = infoboxOf(
                item("中文名", "\"新世纪福音战士\""),
                item("别名", """
                        [{"v":"EVA"},
                         {"v":"Neon Genesis Evangelion"},
                         {"k":"中国大陆公映版译名","v":"天鹰战士"},
                         {"v":"EVA"}]
                        """),
                item("话数", "\"26\""));

        assertThat(AnimeAliases.join(infobox))
                .isEqualTo("EVA,Neon Genesis Evangelion,天鹰战士");
    }

    @Test
    @DisplayName("纯字符串数组的别名也接得住")
    void takesPlainStringArray() {
        assertThat(AnimeAliases.join(infoboxOf(item("别名", "[\"EVA\",\"NGE\"]"))))
                .isEqualTo("EVA,NGE");
    }

    @Test
    @DisplayName("value 直接是字符串的别名也接得住")
    void takesPlainString() {
        assertThat(AnimeAliases.join(infoboxOf(item("别名", "\"EVA\"")))).isEqualTo("EVA");
    }

    @Test
    @DisplayName("空白项丢弃, 值两端的空白修掉")
    void trimsAndDropsBlanks() {
        assertThat(AnimeAliases.join(infoboxOf(item("别名", "[{\"v\":\"  \"},{\"v\":\" EVA \"},{\"v\":\"\"}]"))))
                .isEqualTo("EVA");
    }

    // ========== 负向: 抽不出来时返回 null, 好让调用方"不覆盖" ==========

    @Test
    @DisplayName("没有「别名」这一项 -> null（不能被别的 key 蒙混过去）")
    void withoutAliasKeyIsNull() {
        assertThat(AnimeAliases.join(infoboxOf(
                item("中文名", "\"新世纪福音战士\""),
                item("话数", "\"26\""),
                item("导演", "[{\"v\":\"庵野秀明\"}]"))))
                .isNull();
    }

    @Test
    @DisplayName("「别名」存在但里面是空的 -> null")
    void emptyAliasIsNull() {
        assertThat(AnimeAliases.join(infoboxOf(item("别名", "[]")))).isNull();
        assertThat(AnimeAliases.join(infoboxOf(item("别名", "null")))).isNull();
        assertThat(AnimeAliases.join(infoboxOf(item("别名", "[{\"v\":\"\"}]")))).isNull();
    }

    @Test
    @DisplayName("infobox 本身是 null 或空 -> null")
    void noInfoboxIsNull() {
        assertThat(AnimeAliases.join(null)).isNull();
        assertThat(AnimeAliases.join(List.of())).isNull();
    }

    /**
     * 超长的别名必须被截断, 长度与 V5 脚本的列宽一致.
     *
     * <p>为什么这条不能省: 不截断的话, 一次"搜索回源落库"会因为别名超出列宽而 INSERT 失败,
     * 异常一路上冒把整个搜索请求打成 500 —— 比搜不到更糟. 截断是第二道防线, 第一道是
     * 实体上的 {@code @Column(length = 1000)}.
     */
    @Test
    @DisplayName("超长截断到 MAX_STORED_LENGTH（与 V5 的列宽一致）")
    void truncatesToColumnWidth() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) json.append(',');
            json.append("{\"v\":\"alias-").append(i).append("\"}");
        }
        json.append(']');

        String joined = AnimeAliases.join(infoboxOf(item("别名", json.toString())));

        assertThat(joined).hasSize(AnimeAliases.MAX_STORED_LENGTH);
        // 截断的是尾部的项, 不是开头 —— 别名的顺序有意义(官方写法通常在前)
        assertThat(joined).startsWith("alias-0,alias-1,");
        assertThat(AnimeAliases.MAX_STORED_LENGTH).isEqualTo(1000);
    }
}

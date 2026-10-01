package com.animetracker.util;

/**
 * 把一段可能很长的正文截成一行放得下的摘要 —— 全站只此一份口径。
 *
 * <p><b>为什么要抽出来。</b> 它原本是 {@code ReviewReplyService} 的私有方法，唯一的
 * 消费者是「谁回复了我」那一页。管理端的操作账本要记「被删掉的那条评论说了什么」，
 * 是同一个需求：正文最长 5000 字，原样记进 detail 就是几十 KB 一行。
 *
 * <p>抄一份是十行，看着很划算，但两处一旦漂掉（一边 60 字、一边 200 字），<b>没有任何
 * 测试会抓得到</b> —— 它不报错，只是同一个概念在两个地方长得不一样，直到有人对比两处
 * 的显示效果才发现。与 {@link PageResults} 被抽出来是同一条理由。
 */
public final class TextSnippet {

    private TextSnippet() {
    }

    /** 摘要长度。一屏列表里读的是「这是哪条」，一行放得下就够了 */
    public static final int LENGTH = 60;

    /**
     * 截断到 {@value #LENGTH} 个字符，超出部分用省略号代替。
     *
     * <p>{@code null} 与空白都回成 {@code null} 而不是空串：评论是可以没有正文的
     * （只打分不写字），调用方据此显示「（无文字）」；回空串会让那个判断退化成
     * 「有内容但看不见」。
     */
    public static String of(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.length() <= LENGTH
                ? content
                : content.substring(0, LENGTH) + "…";
    }
}

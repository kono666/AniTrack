package com.animetracker.review.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ReviewRequest {
    @NotNull(message = "缺少番剧 id")
    private Integer subjectId;
    @NotNull(message = "请先评分")
    @Min(value = 1, message = "评分不能低于 1")
    @Max(value = 10, message = "评分不能高于 10")
    private Integer rating;

    /**
     * 上限按数据库那一列的类型给: content 是 TEXT/VARCHAR 无界, 不限的话
     * 一次请求就能塞进任意大的字符串, 直接进库、进列表接口、进 Agent 上下文.
     */
    @Size(max = 5000, message = "评论内容不能超过 5000 个字符")
    private String content;
}

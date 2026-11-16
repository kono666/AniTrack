package com.animetracker.review.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 发一条回复 / 改一条回复 —— 两个接口用同一个请求体, 因为字段完全一样.
 *
 * <p><b>这里比 {@link ReviewRequest} 多一个 @NotBlank, 不是抄漏了.</b> 评论是
 * 「评分 + 可选文字」, 只打分不写字是合法用法(详情页上显示成「（无文字）」);
 * 而回复**只有**文字, 空回复没有任何含义。数据库那一列也是 NOT NULL(V8),
 * 接口层是开口、库是最终防线, 与 README 设计要点 11 同一口径。
 */
@Data
public class ReplyRequest {
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 5000, message = "回复内容不能超过 5000 个字符")
    private String content;
}

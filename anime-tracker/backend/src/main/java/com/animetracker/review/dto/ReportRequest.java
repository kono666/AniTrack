package com.animetracker.review.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 举报一条评论.
 *
 * <p><b>{@code reason} 这里只用 {@code @NotBlank} 挡空值, 白名单校验在 service 里</b>
 * —— 合法取值是 {@code ReviewReport.REASONS} 那四个, 而 {@code @Pattern} 里再抄一份
 * 就等于同一个约定有两处定义, 改了实体不改 DTO 的话两边会慢慢分叉. 这与
 * {@code TrackRequest.STATUS_REGEX} 那里的取舍**正好相反**: 那个正则之所以敢抄一份,
 * 是因为它同时被前端用来渲染选项; 这里的四个理由是**界面文案**(垃圾广告/辱骂攻击/
 * 剧透/其他), 中英文都要在前端单独写一遍, 抄不到这里来.
 *
 * <p>{@code detail} 上限 500 与建表那一列一致 —— 接口层先拦, 库是最终防线.
 */
@Data
public class ReportRequest {
    @NotBlank(message = "请选择举报理由")
    private String reason;
    @Size(max = 500, message = "补充说明不能超过 500 个字符")
    private String detail;
}

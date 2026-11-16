package com.animetracker.tracking.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class TrackRequest {

    /**
     * 合法的追番状态, 正则是唯一的源头.
     *
     * 写成字面量而不是用 String.join 从列表拼出来, 是因为 @Pattern 的 regexp
     * 必须是**编译期常量**, 拼出来的不算. 于是反过来让正则当源头, STATUSES 从它
     * 切出来 —— 两处各写一份的话迟早会有一边被改漏, 而漏掉的表现是
     * 「接口放行了一个统计口径和 Agent 都不认的状态」: 数据存进去了,
     * 但 getUserStats 的五个计数里一个都不含它, 用户看到的是「追番了但总数没变」.
     *
     * 以前这份清单只存在于 Agent 工具里(TrackingTools), 网页接口这一侧完全没有 ——
     * 也就是说同一个非法值走 Agent 会被拒、走 HTTP 会被收下.
     */
    public static final String STATUS_REGEX = "want_to_watch|watching|watched|on_hold|dropped";

    public static final List<String> STATUSES = List.of(STATUS_REGEX.split("\\|"));

    @NotNull(message = "缺少番剧 id")
    private Integer subjectId;

    /**
     * 追番状态. <b>可以缺席</b> —— 缺席的意思是「别碰这个字段」, 不是「清空」.
     *
     * <p>本类四个字段里只有 subjectId 是必填的, 其余三个缺席都等于保持原值
     * (见 TrackService#applyAndSave)。改前 status 上是 @NotBlank, 于是
     * 「只把进度改成 5」这件事在协议层面就做不到 —— 每个调用方都只能先把整行读回来、
     * 改一个字段、再把整行发回去。谁手里那份副本一旧(个人页放了一会儿、开了两个标签页),
     * 就会把它没碰过的字段静默回退。整行覆盖是那个必填逼出来的, 不是谁偷懒。
     *
     * <p>@Pattern 对 null 放行、对空串不放行, 这个差别正好是要的: 不传键 = 不动,
     * 传了键却给了空值 = 调用方有 bug, 照样拒。
     */
    @Pattern(regexp = STATUS_REGEX, message = "追番状态不在允许的取值里")
    private String status;

    @Min(value = 0, message = "观看进度不能为负数")
    private Integer progress;       // 当前观看进度(集数)

    @Min(value = 0, message = "评分不能低于 0")
    @Max(value = 10, message = "评分不能高于 10")
    private Integer score;          // 个人评分

    @Size(max = 2000, message = "备注不能超过 2000 个字符")
    private String notes;           // 备注
}

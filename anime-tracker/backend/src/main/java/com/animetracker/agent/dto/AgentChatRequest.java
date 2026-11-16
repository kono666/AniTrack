package com.animetracker.agent.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/** Agent 对话请求 */
@Data
public class AgentChatRequest {
    @NotBlank(message = "提问内容不能为空")
    @Size(max = 4000, message = "提问内容过长")
    private String message;

    /** 人格: user-assistant / admin-analyst, 不传则按用户端处理 */
    private String persona;

    /** 已有会话 id. 登录用户传了它, 历史就以服务端记录为准 */
    private Long conversationId;

    /** 历史消息, 仅未登录访客需要带; 会被 HistorySanitizer 清洗 */
    private List<HistoryItem> history;
}

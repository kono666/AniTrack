package com.animetracker.agent.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 前端回传的历史消息条目 */
@Data
public class HistoryItem {
    private String role;
    private String content;
}

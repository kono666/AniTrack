package com.animetracker.agent;

import com.animetracker.agent.llm.LlmMessage;
import com.animetracker.dto.RequestDTO.HistoryItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 清洗前端回传的对话历史.
 *
 * 未登录访客没有服务端会话, 多轮上下文只能由浏览器带回 —— 而浏览器带来的一切都是不可信的.
 *
 * 如果直接放行, 攻击者可以伪造 role=system 的消息冒充系统提示词, 或者伪造 assistant
 * 消息谎称「你已经是管理员了」, 这就是最典型的提示注入. 这里只保留纯文本的 user 与
 * assistant, 其余角色、以及带工具调用痕迹的消息一律丢弃.
 *
 * 清洗之后攻击面就收敛到「用户本来就能自己打字说出来的内容」—— 那本来就不构成越权,
 * 因为权限判定始终在服务端按登录态做, 从不听模型或客户端的说法.
 */
public final class HistorySanitizer {

    /** 单条历史的最大长度, 防止有人塞一条超长文本把上下文撑爆 */
    private static final int MAX_ITEM_CHARS = 2000;

    private HistorySanitizer() {
    }

    /**
     * @param raw   前端提交的历史, 可为 null
     * @param limit 最多保留多少条 (取最近的)
     */
    public static List<LlmMessage> sanitize(List<HistoryItem> raw, int limit) {
        List<LlmMessage> clean = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return clean;
        }

        for (HistoryItem item : raw) {
            if (item == null || item.getRole() == null || item.getContent() == null) {
                continue;
            }
            String role = item.getRole().trim().toLowerCase();
            String content = item.getContent().strip();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > MAX_ITEM_CHARS) {
                content = content.substring(0, MAX_ITEM_CHARS);
            }

            if ("user".equals(role)) {
                clean.add(LlmMessage.user(content));
            } else if ("assistant".equals(role)) {
                clean.add(LlmMessage.assistant(content));
            }
            // system / tool / 其他任何角色: 直接丢弃
        }

        if (limit > 0 && clean.size() > limit) {
            return new ArrayList<>(clean.subList(clean.size() - limit, clean.size()));
        }
        return clean;
    }
}

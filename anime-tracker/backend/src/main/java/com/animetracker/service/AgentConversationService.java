package com.animetracker.service;

import com.animetracker.agent.Persona;
import com.animetracker.agent.llm.LlmMessage;
import com.animetracker.entity.AgentConversation;
import com.animetracker.entity.AgentMessage;
import com.animetracker.entity.User;
import com.animetracker.exception.BusinessException;
import com.animetracker.repository.AgentConversationRepository;
import com.animetracker.repository.AgentMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话的读写.
 *
 * 未登录访客不落库: 没有归属主体的对话记录既无法做权限校验, 也只会积累成垃圾数据.
 * 访客的多轮上下文由前端每次带回(见 HistorySanitizer), 登录用户则从库里取, 以服务端为准.
 */
@Service
public class AgentConversationService {

    private static final int TITLE_MAX = 24;

    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;

    public AgentConversationService(AgentConversationRepository conversationRepository,
                                    AgentMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 取回会话并校验归属.
     *
     * 会话不存在与会话不属于当前用户, 返回同一个 404 —— 如果两者提示不同,
     * 攻击者就能靠枚举 id 探测出「哪些会话是存在的」.
     */
    public AgentConversation requireOwned(User user, Long conversationId) {
        if (user == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return conversationRepository.findByIdAndUser(conversationId, user)
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
    }

    /** 读取历史消息, 转成模型消息格式 */
    public List<LlmMessage> history(User user, Long conversationId) {
        AgentConversation conversation = requireOwned(user, conversationId);
        List<LlmMessage> messages = new ArrayList<>();
        for (AgentMessage m : messageRepository.findByConversationOrderByIdAsc(conversation)) {
            // 只认这两种角色. 即便将来有人往表里写了别的角色, 也不会被当成系统指令回灌给模型
            if ("user".equals(m.getRole())) {
                messages.add(LlmMessage.user(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                messages.add(LlmMessage.assistant(m.getContent()));
            }
        }
        return messages;
    }

    /**
     * 追加一轮问答, 返回会话 id (访客返回 null).
     *
     * 失败不应影响本次回答 —— 事务提交不了是持久化的问题, 用户该拿到的答复还是要拿到,
     * 所以调用方需要自行兜住异常.
     */
    public Long append(User user, Long conversationId, Persona persona,
                       String question, String answer) {
        if (user == null) {
            return null;
        }

        AgentConversation conversation;
        if (conversationId == null) {
            conversation = conversationRepository.save(AgentConversation.builder()
                    .user(user)
                    .persona(persona.promptFile())
                    .title(makeTitle(question))
                    .messageCount(0)
                    .build());
        } else {
            conversation = requireOwned(user, conversationId);
            conversation.setPersona(persona.promptFile());
        }

        messageRepository.save(AgentMessage.builder()
                .conversation(conversation).role("user").content(question).build());
        if (answer != null && !answer.isBlank()) {
            messageRepository.save(AgentMessage.builder()
                    .conversation(conversation).role("assistant").content(answer).build());
        }

        conversation.setMessageCount((int) messageRepository.countByConversation(conversation));
        conversationRepository.save(conversation);
        return conversation.getId();
    }

    /** 我的会话列表 */
    public List<Map<String, Object>> list(User user) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentConversation c : conversationRepository.findByUserOrderByUpdatedAtDesc(user)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("persona", c.getPersona());
            item.put("title", c.getTitle());
            item.put("messageCount", c.getMessageCount());
            item.put("updatedAt", c.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    /** 会话详情(含全部消息) */
    public Map<String, Object> detail(User user, Long conversationId) {
        AgentConversation conversation = requireOwned(user, conversationId);

        List<Map<String, Object>> messages = new ArrayList<>();
        for (AgentMessage m : messageRepository.findByConversationOrderByIdAsc(conversation)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            messages.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", conversation.getId());
        out.put("persona", conversation.getPersona());
        out.put("title", conversation.getTitle());
        out.put("messages", messages);
        return out;
    }

    @Transactional
    public void delete(User user, Long conversationId) {
        AgentConversation conversation = requireOwned(user, conversationId);
        messageRepository.deleteByConversation(conversation);
        conversationRepository.delete(conversation);
    }

    /** 取提问开头做标题, 压掉换行和连续空白 */
    private String makeTitle(String question) {
        String q = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        return q.length() <= TITLE_MAX ? q : q.substring(0, TITLE_MAX) + "…";
    }
}

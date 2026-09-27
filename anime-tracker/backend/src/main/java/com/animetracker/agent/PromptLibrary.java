package com.animetracker.agent;

import com.animetracker.agent.llm.LlmException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 系统提示词加载器.
 *
 * 提示词外置成 resources/prompts/*.md 而不是硬编码在 Java 里, 原因:
 *   1. 调整措辞不需要改代码、不需要重新编译
 *   2. 提示词本身是重要的设计产出, 独立成文件更便于版本管理与 review
 */
@Component
public class PromptLibrary {

    private final Map<Persona, String> cache = new EnumMap<>(Persona.class);

    public String systemPrompt(Persona persona) {
        return cache.computeIfAbsent(persona, this::load);
    }

    private String load(Persona persona) {
        String path = "prompts/" + persona.promptFile() + ".md";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new LlmException("系统提示词文件为空: " + path);
            }
            return text;
        } catch (IOException e) {
            throw new LlmException("系统提示词文件缺失: " + path, e);
        }
    }
}

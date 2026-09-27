package com.animetracker.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * 两家协议共用的 HTTP 收发与错误翻译.
 *
 * 集中在这里的原因: 错误处理逻辑与协议无关, 且必须一致 ——
 * 尤其是「绝不把上游原始响应体透给前端」这条, 只能有一处实现.
 */
final class LlmHttp {

    private LlmHttp() {
    }

    /**
     * 发送 JSON 请求并解析 JSON 响应.
     *
     * @param label 提供方名称, 仅用于拼装面向用户的错误提示
     */
    static JsonNode post(RestTemplate restTemplate, ObjectMapper mapper, String url,
                         HttpHeaders headers, Object body, String label) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LlmException("构造请求体失败", e);
        }

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
            String text = resp.getBody();
            if (text == null || text.isBlank()) {
                throw new LlmException(label + "返回了空响应");
            }
            return mapper.readTree(text);
        } catch (HttpStatusCodeException e) {
            // 注意: 只取状态码, 不透传 e.getResponseBodyAsString() —— 部分厂商报错时回显请求内容
            throw new LlmException(describe(e.getStatusCode().value(), label), e);
        } catch (ResourceAccessException e) {
            throw new LlmException(label + "请求超时或网络不可达, 请检查网络后重试", e);
        } catch (JsonProcessingException e) {
            throw new LlmException(label + "返回的内容无法解析为 JSON", e);
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂的中文说明 */
    private static String describe(int status, String label) {
        return switch (status) {
            case 401, 403 -> label + "鉴权失败: API Key 无效、已过期或无权访问该模型";
            case 402 -> label + "账户余额不足, 请先充值";
            case 404 -> label + "接口不存在: 请检查 llm.base-url 与 llm.model 配置是否正确";
            case 413 -> label + "请求内容过长, 请缩短问题后重试";
            case 429 -> label + "请求过于频繁或已达配额上限, 请稍后重试";
            default -> status >= 500
                    ? label + "服务暂时不可用 (HTTP " + status + "), 请稍后重试"
                    : label + "拒绝了我的请求 (HTTP " + status + ")";
        };
    }
}

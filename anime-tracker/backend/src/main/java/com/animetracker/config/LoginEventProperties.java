package com.animetracker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录事件的保留期。
 *
 * <p><b>为什么必须有一个保留期，而不是「先留着，以后再清理」。</b> {@code login_event}
 * 是这张库里唯一一张<b>只增不减、且行数只取决于「用户有多勤快」</b>的表 —— 别的表都有
 * 自然的删除路径（追番能删、评论能删、番剧表由回填脚本控制），只有它没有任何读路径会
 * 回溯到很久以前。留一个没有出口的增长源，就是把「数据库会不会被一张没人看的表撑爆」
 * 变成一个时间问题。
 *
 * <p><b>为什么是 180 天。</b> 这个数与看板的读路径无关（最远的查询是 30 天），它取的是
 * 「一年内两次上线之间还能对得上」的量级：够回答「半年前那个月有没有人用」，
 * 又不至于把三年的每一个 session 都留着。它是**运维口径**，不是产品口径，所以可配 ——
 * 想留全量就把这个数调大，代价只是磁盘。
 *
 * <p>用 {@link Duration} 而不是 {@code int days}：与 {@code ImageProxyProperties.ttl} 同
 * 一个写法，yml 里写 {@code 180d} / {@code 24h} 都成立，读配置的人不用去猜单位。
 */
@Data
@Component
@ConfigurationProperties(prefix = "anitrack.login-event")
public class LoginEventProperties {

    /** 事件最多留多久。早于「现在 − 保留期」的会被每日清理任务删掉 */
    private Duration retention = Duration.ofDays(180);
}

package com.animetracker.agent.tool;

import java.util.List;

/**
 * 一组同领域工具的提供方.
 *
 * 按业务域拆成多个实现 (内容发现 / 追番管理 / 统计 / 运营),
 * 比把所有工具塞进一个类更好维护, 也方便按域写测试.
 */
public interface ToolProvider {

    List<ToolDefinition> tools();
}

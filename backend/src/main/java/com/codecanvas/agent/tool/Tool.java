package com.codecanvas.agent.tool;

import java.util.Map;

/**
 * Agent 可调用的一个工具。
 *
 * <p>把"Agent 能干的事"显式列成工具而不是写死在流程里，好处有三：
 * 每一步做了什么在界面上可追溯；工具能单独测试；将来切成模型自主决定调哪个工具时，接口不用改。
 */
public interface Tool {

    String name();

    String description();

    /**
     * 执行工具。
     *
     * @param args 参数
     * @return 给 Agent 看的执行结果（会进入 trace，也可能回注给模型）
     */
    String execute(Map<String, Object> args);
}

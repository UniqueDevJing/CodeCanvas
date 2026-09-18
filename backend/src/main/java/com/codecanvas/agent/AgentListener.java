package com.codecanvas.agent;

import java.util.Map;

/**
 * 编排过程的回调出口。
 *
 * <p>Agent 不直接依赖 SSE：它只往这里抛事件，由 web 层决定怎么送到浏览器。
 * 这样编排逻辑可以被单元测试直接断言（收集事件列表即可），也方便将来换传输方式。
 */
public interface AgentListener {

    void onSession(String projectId, boolean created);

    void onStage(AgentStage stage, String detail);

    void onStatus(String text);

    void onToolCall(String tool, Map<String, Object> args, String result);

    void onFile(String path, String action, int bytes);

    void onDelta(String path, String text);

    void onTokens(UsageView usage);

    void onHeal(int attempt, String reason, String detail);

    void onResult(AgentResult result);

    void onError(String message);

    /** 不做任何事的最小实现，便于测试与内部调用。 */
    static AgentListener noop() {
        return new AgentListener() {
            @Override
            public void onSession(String projectId, boolean created) {
            }

            @Override
            public void onStage(AgentStage stage, String detail) {
            }

            @Override
            public void onStatus(String text) {
            }

            @Override
            public void onToolCall(String tool, Map<String, Object> args, String result) {
            }

            @Override
            public void onFile(String path, String action, int bytes) {
            }

            @Override
            public void onDelta(String path, String text) {
            }

            @Override
            public void onTokens(UsageView usage) {
            }

            @Override
            public void onHeal(int attempt, String reason, String detail) {
            }

            @Override
            public void onResult(AgentResult result) {
            }

            @Override
            public void onError(String message) {
            }
        };
    }
}

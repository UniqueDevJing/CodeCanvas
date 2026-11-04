package com.codecanvas.agent.trace;

import java.util.Map;

/**
 * 执行轨迹中的单个事件（C3 时间旅行）。
 *
 * @param ts        墙钟时间戳
 * @param elapsedMs 相对运行起点的毫秒数（回放动画的节拍）
 * @param type      事件类型：session / stage / status / tool / file / tokens / heal / result / error
 * @param stage     事件发生时所处的阶段名（可为空）
 * @param detail    人类可读摘要
 * @param data      结构化载荷（如工具参数与结果、token 用量、修复原因）
 */
public record RunEvent(
        long ts,
        long elapsedMs,
        String type,
        String stage,
        String detail,
        Map<String, Object> data) {

    public static RunEvent of(long start, String type, String stage, String detail, Map<String, Object> data) {
        long now = System.currentTimeMillis();
        return new RunEvent(now, Math.max(0, now - start), type, stage, detail,
                data == null ? Map.of() : data);
    }
}

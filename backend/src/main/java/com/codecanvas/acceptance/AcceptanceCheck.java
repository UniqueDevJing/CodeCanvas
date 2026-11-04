package com.codecanvas.acceptance;

/**
 * 单条验收断言（C4：规格 → 验收用例自动生成，SWE-bench 思路）。
 *
 * @param id          稳定标识（a1/a2/a3）
 * @param kind        断言类型：dom=检查 HTML 源码；behavior=交互行为（暂不纳入完成判据，如实标注）
 * @param target      判定目标：标签片段（如 {@code <canvas>}）、id="..."、class="..." 或关键文案
 * @param description 给用户看的中文断言描述
 */
public record AcceptanceCheck(String id, String kind, String target, String description) {
    public boolean isStatic() {
        return "dom".equalsIgnoreCase(kind) || "content".equalsIgnoreCase(kind);
    }
}

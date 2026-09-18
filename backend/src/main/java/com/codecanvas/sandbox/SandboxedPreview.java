package com.codecanvas.sandbox;

/**
 * 沙箱处理后的预览结果。
 *
 * @param html 注入必要的运行时探针之后、真正放进 iframe 的页面
 * @param mode 隔离方式标识
 * @param note 给用户看的隔离级别说明（不夸大也不隐瞒）
 */
public record SandboxedPreview(String html, String mode, String note) {
}

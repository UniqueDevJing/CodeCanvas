package com.codecanvas.guardrail;

import com.codecanvas.acceptance.AcceptanceSpec;

/**
 * 护栏判定时可见的上下文。
 *
 * <p>刻意做成不可变快照：规则只读、不改状态，才敢在链路上任意插拔与并发复用。
 * 文件类工具绑定的是会话工作区，而护栏上下文只带本次判定的文本，两者不混。
 */
public record GuardrailContext(
        String requirement,
        String prd,
        String rawOutput,
        String html,
        AcceptanceSpec acceptance,
        int attempt) {

    public static GuardrailContext input(String requirement) {
        return new GuardrailContext(requirement, null, null, null, null, 0);
    }

    public static GuardrailContext output(String rawOutput, String html,
                                          AcceptanceSpec acceptance, int attempt) {
        return new GuardrailContext(null, null, rawOutput, html, acceptance, attempt);
    }
}

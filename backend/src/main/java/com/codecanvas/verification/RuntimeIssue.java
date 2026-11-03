package com.codecanvas.verification;

import com.codecanvas.common.HtmlValidator;

/**
 * 运行时验证发现的一条问题。
 *
 * <p>结构化是为了"回注"——它带着 {@code source}/{@code line}/{@code stack}，
 * 能直接喂给现有的自检修复闭环，让模型知道"哪一行代码、什么错误"，
 * 而不是只收到一句"运行时出错了"。复用静态校验的 {@link HtmlValidator.Issue} 形状，
 * 是为了让同一套修复逻辑（describe → 回注 → 修复 → 重验）对静态和运行时问题一视同仁。
 *
 * @param type   问题类别
 * @param message 给用户和模型看的具体描述
 * @param source  出错来源文件名（通常是 index.html）
 * @param line    出错行号（0 表示未知）
 * @param stack   调用栈（可为 null）
 */
public record RuntimeIssue(RuntimeIssueType type, String message, String source, int line, String stack) {

    /** SKIPPED 不阻断；其余都算没通过运行时验收。 */
    public boolean blocking() {
        return type.blocking();
    }

    /** 映射成静态校验器的 Issue 形状，使修复闭环无需区分来源。 */
    public HtmlValidator.Issue toValidatorIssue() {
        return new HtmlValidator.Issue("RUNTIME_" + type.name(), message);
    }

    /** 构造一条跳过类问题（无浏览器可用时）。 */
    public static RuntimeIssue skipped(String reason) {
        return new RuntimeIssue(RuntimeIssueType.SKIPPED, reason, null, 0, null);
    }
}

package com.codecanvas.agent;

import com.codecanvas.common.HtmlValidator;
import com.codecanvas.project.ProjectFile;
import java.util.List;

/**
 * 一次运行的最终结果。
 *
 * @param previewHtml  已经过沙箱处理（注入了运行时错误上报脚本）的页面
 * @param sandboxMode  使用的沙箱隔离方式，用于在界面上如实说明当前隔离级别
 * @param healAttempts 自检修复触发次数
 * @param recovered    修复是否成功
 * @param quality      质检报告：分项检查结果，面向用户展示而不是调试输出
 */
public record AgentResult(
        String projectId,
        String versionId,
        String versionLabel,
        String summary,
        List<ProjectFile> files,
        String previewHtml,
        String sandboxMode,
        String sandboxNote,
        int healAttempts,
        boolean recovered,
        String model,
        String tierLabel,
        List<String> toolTrace,
        List<HtmlValidator.CheckResult> quality,
        UsageView usage) {
}

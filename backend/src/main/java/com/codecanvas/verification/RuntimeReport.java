package com.codecanvas.verification;

import java.util.List;

/**
 * 一次运行时验证的结果。
 *
 * @param skipped        环境不具备运行条件，验证被跳过（不阻断交付）
 * @param verified       浏览器是否成功跑完整个验证流程（即使发现了问题也算 true）
 * @param issues         发现的问题（可能为空）
 * @param screenshotPath 截图落盘路径（未配置或失败则为 null）
 */
public record RuntimeReport(boolean skipped, boolean verified, List<RuntimeIssue> issues, String screenshotPath) {

    /** 需要进入修复闭环的问题：排除 SKIPPED。 */
    public List<RuntimeIssue> blockingIssues() {
        return issues.stream().filter(RuntimeIssue::blocking).toList();
    }

    /** 给工具轨迹 / 状态栏看的一句话摘要。 */
    public String summary() {
        if (skipped) {
            return "运行时验证已跳过（" + (issues.isEmpty() ? "环境不可用" : issues.get(0).message()) + "）";
        }
        if (issues.isEmpty()) {
            return "运行时验证通过：页面在浏览器中正常加载与交互";
        }
        long blocking = blockingIssues().size();
        return "运行时验证发现 " + blocking + " 个问题：" + issues.get(0).message();
    }

    /** 一条跳过类报告。 */
    public static RuntimeReport skipped(String reason) {
        return new RuntimeReport(true, false, List.of(RuntimeIssue.skipped(reason)), null);
    }
}

package com.codecanvas.verification;

/**
 * 运行时验证的三态结论（C5 强制门禁）。
 *
 * <p>核心纪律：浏览器不可用时结论必须是显式的 NOT_VERIFIED，
 * 绝不允许静默滑成"通过"。三种状态互斥、语义固定：
 * <ul>
 *   <li>{@code VERIFIED} —— 浏览器真的跑完了验证流程（发现问题也算跑完）</li>
 *   <li>{@code NOT_VERIFIED} —— 开着验证但没跑成：浏览器缺失 / 超时 / 输出解析失败</li>
 *   <li>{@code SKIPPED} —— 策略显式关闭（codecanvas.runtime-verify.enabled=false），
 *       属于配置层面的豁免，同样要在交付里如实标注</li>
 * </ul>
 */
public record VerificationStatus(State state, String reason) {

    public enum State {
        VERIFIED,
        NOT_VERIFIED,
        SKIPPED
    }

    public static VerificationStatus verified() {
        return new VerificationStatus(State.VERIFIED, null);
    }

    public static VerificationStatus notVerified(String reason) {
        return new VerificationStatus(State.NOT_VERIFIED,
                reason == null || reason.isBlank() ? "运行时验证未完成" : reason);
    }

    public static VerificationStatus skipped(String reason) {
        return new VerificationStatus(State.SKIPPED,
                reason == null || reason.isBlank() ? "运行时验证已在策略层面关闭" : reason);
    }

    /** 从 Node 脚本回传的报告推导状态。 */
    public static VerificationStatus from(RuntimeReport report, boolean policyEnabled) {
        if (!policyEnabled) {
            return skipped("codecanvas.runtime-verify.enabled=false");
        }
        if (report == null) {
            return notVerified("运行时验证未产出报告");
        }
        if (report.skipped()) {
            String why = report.issues().isEmpty() ? "浏览器环境不可用" : report.issues().get(0).message();
            return notVerified(why);
        }
        return report.verified() ? verified()
                : notVerified("浏览器未完成完整验证流程");
    }

    public boolean isVerified() {
        return state == State.VERIFIED;
    }

    public boolean attempted() {
        return state != State.SKIPPED;
    }

    /** 面向质检面板的一句话。 */
    public String display() {
        return switch (state) {
            case VERIFIED -> "运行时验证已通过（浏览器实跑）";
            case NOT_VERIFIED -> "运行时验证未完成：" + reason;
            case SKIPPED -> "运行时验证已跳过：" + reason;
        };
    }
}

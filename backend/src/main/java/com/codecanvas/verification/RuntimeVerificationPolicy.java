package com.codecanvas.verification;

import com.codecanvas.common.HtmlValidator;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 运行时验证的总开关与合并策略。
 *
 * <p>把"要不要跑运行时验证"和"静态问题 + 运行时问题怎么合到一起喂给修复闭环"
 * 这两件决策集中到这里，编排器只问它：开没开、默认规格是什么、合完长什么样。
 *
 * <p>为什么运行时问题要和静态问题合并而不是单独处理：自检修复闭环是按
 * {@code List<HtmlValidator.Issue>} 驱动的，合并后模型一次就能看到"静态 + 运行时"的全貌，
 * 修复时也只需跑一轮，不会为了两类问题各修一遍。
 */
@Component
public class RuntimeVerificationPolicy {

    private final boolean enabled;
    private final RuntimeSpec defaultSpec;

    /**
     * @param settleMs 浏览器里加载/点击后等待 DOM 稳定的毫秒数。
     *
     * <p><b>P1-4 修掉的性能 bug</b>：这里以前误把 {@code runtime-verify.timeout-ms}
     * （15000，Java 侧杀进程的兜底上限）当成 settleMs 传给 Node 脚本，
     * 于是每次运行时验证都在浏览器里白等 15 秒，全量测试被拖到 6 分钟。
     * 两者是完全不同的量：timeout 管进程，settle 管渲染。现在拆成独立配置并夹到 2 秒以内，
     * 即使将来又配错也不会把生成链路拖垮。
     */
    public RuntimeVerificationPolicy(
            @Value("${codecanvas.runtime-verify.enabled:true}") boolean enabled,
            @Value("${codecanvas.runtime-verify.click-main-button:true}") boolean clickMainButton,
            @Value("${codecanvas.runtime-verify.settle-ms:300}") int settleMs) {
        this.enabled = enabled;
        this.defaultSpec = new RuntimeSpec(clickMainButton, false, List.of(), List.of(), null,
                Math.max(0, Math.min(settleMs, 2000)));
    }

    public boolean enabled() {
        return enabled;
    }

    public RuntimeSpec defaultSpec() {
        return defaultSpec;
    }

    /**
     * 把静态问题与运行时问题合并成一份交给修复闭环的清单。
     * 运行时里的 SKIPPED 不会进来（它本就不该阻断交付）。
     */
    public List<HtmlValidator.Issue> merge(List<HtmlValidator.Issue> staticIssues, RuntimeReport report) {
        List<HtmlValidator.Issue> all = new ArrayList<>(staticIssues);
        if (report != null) {
            for (RuntimeIssue issue : report.blockingIssues()) {
                all.add(issue.toValidatorIssue());
            }
        }
        return all;
    }
}

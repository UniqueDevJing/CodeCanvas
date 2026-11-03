package com.codecanvas.verification;

/**
 * 运行时验证器抽象。
 *
 * <p>把"生成的页面真在浏览器里跑一遍"和"怎么跑"解耦：编排器只依赖这个接口，
 * 具体实现可以换（Node + Playwright、未来的 Java 绑定、或测试用的桩）。
 * 这样"执行级验证闭环"既能接进自检修复，又不把编排器绑死在某种浏览器技术上。
 */
public interface RuntimeVerifier {

    /**
     * 在真实（或模拟）浏览器环境里验证生成的 HTML。
     *
     * @param html 生成的页面内容
     * @param spec 断言规格
     * @return 结构化报告；环境不可用时返回 skipped=true 的报告（不抛异常）
     */
    RuntimeReport verify(String html, RuntimeSpec spec);

    /** 当前环境是否能真正执行运行时验证。 */
    boolean available();

    /** 不可用时的原因，用于在状态栏如实说明而非假装可用。 */
    String unavailableReason();
}

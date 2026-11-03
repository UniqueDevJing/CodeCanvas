package com.codecanvas.verification;

/**
 * 运行时验证能发现的问题类别。
 *
 * <p>与静态校验（{@code HtmlValidator.Issue}）不同，这些是"页面真的在浏览器里跑起来之后"才暴露的问题：
 * 脚本抛了未捕获异常、控制台报了错、点了主按钮却毫无反应、该有的文案/元素没出现。
 * 这正是"能预览"和"跑得过验收"之间的距离。
 */
public enum RuntimeIssueType {

    /** 页面加载或交互时抛出未捕获异常（window.error / unhandledrejection）。 */
    PAGE_CRASH,

    /** 控制台出现 error 级别日志（通常是被吞掉的运行时异常信号）。 */
    CONSOLE_ERROR,

    /** 点主按钮后页面无任何变化，或点击本身抛异常。 */
    BUTTON_NOOP,

    /** 页面未包含预期文案（PRD 里承诺会展示的关键字）。 */
    MISSING_TEXT,

    /** 页面缺少预期元素（PRD 里承诺会出现的容器/控件）。 */
    MISSING_ELEMENT,

    /**
     * 环境不具备运行条件（无无头浏览器 / 脚本缺失），验证被跳过。
     *
     * <p>这是"诚实的降级"而非失败：跳过时不会把任何问题塞进修复闭环，
     * 避免在没有浏览器可用时白白触发几轮无效修复。
     */
    SKIPPED;

    /** 只有 SKIPPED 不阻断交付；其余都算"没通过运行时验收"。 */
    public boolean blocking() {
        return this != SKIPPED;
    }
}

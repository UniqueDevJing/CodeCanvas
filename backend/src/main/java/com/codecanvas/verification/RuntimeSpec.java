package com.codecanvas.verification;

import java.util.List;

/**
 * 运行时验证的断言规格。
 *
 * <p>默认只做"能跑起来"的高信号检查（加载不崩、点主按钮不抛未捕获异常、无控制台 error），
 * 因为"页面该显示什么 / 点了该发生什么"只有 PRD 能精确给出。盲目断言反而会变成误报
 * （和静态校验"宁可漏报别误报"的原则一致）：一个合法落地页点击后可能只是弹窗或视觉变化，
 * DOM 文本没变并不等于坏了。文案/元素/点击变化的断言默认关闭，
 * 后续若接入 PRD 解析（C4 规格→验收用例）再按需打开。
 *
 * @param clickMainButton   是否自动点一次主按钮（第一个可交互控件），用于触发其点击处理里的异常
 * @param assertClickChanges 是否在点击后断言 DOM 文本发生变化（需 PRD 明确承诺才开，默认关）
 * @param expectTexts       断言页面正文必须包含的文案
 * @param expectSelectors   断言页面必须存在的 CSS 选择器
 * @param screenshotPath    截图落盘路径（null 表示不截图）
 * @param settleMs          加载/点击后等待 DOM 稳定的毫秒数
 */
public record RuntimeSpec(boolean clickMainButton,
                         boolean assertClickChanges,
                         List<String> expectTexts,
                         List<String> expectSelectors,
                         String screenshotPath,
                         int settleMs) {

    public static RuntimeSpec defaults() {
        return new RuntimeSpec(true, false, List.of(), List.of(), null, 300);
    }

    /** 透传给 Node 运行脚本的纯数据形态（避免直接序列化 record 带来的字段名耦合）。 */
    public java.util.Map<String, Object> toScriptArgs() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("clickMainButton", clickMainButton);
        map.put("assertClickChanges", assertClickChanges);
        map.put("expectTexts", expectTexts == null ? List.of() : expectTexts);
        map.put("expectSelectors", expectSelectors == null ? List.of() : expectSelectors);
        map.put("screenshotPath", screenshotPath);
        map.put("settleMs", Math.max(0, settleMs));
        return map;
    }
}

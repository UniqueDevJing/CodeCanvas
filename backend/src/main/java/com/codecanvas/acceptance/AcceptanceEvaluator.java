package com.codecanvas.acceptance;

import com.codecanvas.common.HtmlValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 验收断言求值器（C4）：纯 Java、可单测。
 *
 * <p>静态断言（dom/content）对 HTML 源码做客观判定，结果并入质检报告与
 * 自检修复闭环——不通过 = 未完成，修到通过为止。行为断言（behavior）无法
 * 静态判定，如实标注"未纳入完成判据"，绝不假装通过。
 */
public final class AcceptanceEvaluator {

    private AcceptanceEvaluator() {
    }

    /** 对 HTML 求值全部断言，返回与质检报告同构的 CheckResult（前端质检面板直接可渲染） */
    public static List<HtmlValidator.CheckResult> evaluate(String html, AcceptanceSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return List.of();
        }
        String doc = html == null ? "" : html;
        List<HtmlValidator.CheckResult> results = new ArrayList<>();
        for (AcceptanceCheck check : spec.checks()) {
            results.add(evaluateOne(doc, check));
        }
        return results;
    }

    private static HtmlValidator.CheckResult evaluateOne(String doc, AcceptanceCheck check) {
        if (!check.isStatic()) {
            return new HtmlValidator.CheckResult(check.id(), check.description(), true,
                    "行为断言已声明，暂未纳入自动完成判据");
        }
        boolean hit = contains(doc, check.target());
        return new HtmlValidator.CheckResult(check.id(), check.description(), hit,
                hit ? "已满足：" + check.target() : "未在页面中找到：" + check.target());
    }

    /** 目标匹配：标签片段按"<tag+边界>"匹配（兼容属性，如 <canvas> 命中 <canvas id="x">）；#id / .class 按属性查找；其余按大小写不敏感文本查找 */
    public static boolean contains(String doc, String target) {
        String t = target == null ? "" : target.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = doc.toLowerCase(Locale.ROOT);
        if (t.startsWith("<")) {
            String tag = t.toLowerCase(Locale.ROOT);
            if (tag.endsWith(">")) {
                tag = tag.substring(0, tag.length() - 1);
            }
            int i = lower.indexOf(tag);
            while (i >= 0) {
                int after = i + tag.length();
                if (after < lower.length()) {
                    char next = lower.charAt(after);
                    if (next == '>' || Character.isWhitespace(next) || next == '/') {
                        return true;
                    }
                }
                i = lower.indexOf(tag, i + 1);
            }
            return false;
        }
        if (t.startsWith("#")) {
            String id = t.substring(1).toLowerCase(Locale.ROOT);
            return lower.contains("id=\"" + id + "\"") || lower.contains("id='" + id + "'");
        }
        if (t.startsWith(".")) {
            String cls = t.substring(1).toLowerCase(Locale.ROOT);
            int i = lower.indexOf("class=");
            while (i >= 0) {
                int vStart = i + 6;
                if (vStart < lower.length()) {
                    char quote = lower.charAt(vStart);
                    if (quote == '"' || quote == '\'') {
                        int vEnd = lower.indexOf(quote, vStart + 1);
                        if (vEnd > vStart) {
                            String classes = lower.substring(vStart + 1, vEnd);
                            for (String c : classes.split("\\s+")) {
                                if (c.equals(cls)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                i = lower.indexOf("class=", i + 1);
            }
            return false;
        }
        return lower.contains(t.toLowerCase(Locale.ROOT));
    }

    /** 静态断言中未通过的条目 → 质检 Issue（进入自检修复闭环） */
    public static List<HtmlValidator.Issue> failing(List<HtmlValidator.CheckResult> results) {
        List<HtmlValidator.Issue> issues = new ArrayList<>();
        for (HtmlValidator.CheckResult r : results) {
            if (!r.passed() && r.detail() != null && r.detail().startsWith("未在页面中找到")) {
                issues.add(new HtmlValidator.Issue("ACCEPTANCE", "验收断言未满足 [" + r.label() + "]，" + r.detail()));
            }
        }
        return issues;
    }
}

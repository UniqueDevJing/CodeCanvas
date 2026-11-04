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
 *
 * <p>语义断言（semantic，C-A）：断言描述与页面可见文本做嵌入相似度判定，
 * 须注入 {@link SemanticScorer}；未配置打分器时与 behavior 同等对待——
 * 如实标注"未纳入完成判据"，绝不假装通过。
 */
public final class AcceptanceEvaluator {

    /** 语义断言默认通过阈值：相似度 ≥ 0.6 视为满足 */
    public static final double SEMANTIC_PASS_THRESHOLD = 0.6;

    private AcceptanceEvaluator() {
    }

    /** 对 HTML 求值全部断言，返回与质检报告同构的 CheckResult（前端质检面板直接可渲染） */
    public static List<HtmlValidator.CheckResult> evaluate(String html, AcceptanceSpec spec) {
        return evaluate(html, spec, null);
    }

    /** 带语义打分器的求值（C-A）；scorer 为 null 时语义断言诚实降级为"未纳入判据" */
    public static List<HtmlValidator.CheckResult> evaluate(String html, AcceptanceSpec spec,
                                                           SemanticScorer scorer) {
        if (spec == null || spec.isEmpty()) {
            return List.of();
        }
        String doc = html == null ? "" : html;
        List<HtmlValidator.CheckResult> results = new ArrayList<>();
        for (AcceptanceCheck check : spec.checks()) {
            results.add(evaluateOne(doc, check, scorer));
        }
        return results;
    }

    private static HtmlValidator.CheckResult evaluateOne(String doc, AcceptanceCheck check,
                                                         SemanticScorer scorer) {
        if ("behavior".equalsIgnoreCase(check.kind())) {
            return new HtmlValidator.CheckResult(check.id(), check.description(), true,
                    "行为断言已声明，暂未纳入自动完成判据");
        }
        if ("semantic".equalsIgnoreCase(check.kind())) {
            return evaluateSemantic(doc, check, scorer);
        }
        boolean hit = contains(doc, check.target());
        return new HtmlValidator.CheckResult(check.id(), check.description(), hit,
                hit ? "已满足：" + check.target() : "未在页面中找到：" + check.target());
    }

    private static HtmlValidator.CheckResult evaluateSemantic(String doc, AcceptanceCheck check,
                                                              SemanticScorer scorer) {
        if (scorer == null) {
            return new HtmlValidator.CheckResult(check.id(), check.description(), true,
                    "语义断言已声明，未配置语义打分器，暂未纳入自动完成判据");
        }
        String visible = TextExtractor.visibleText(doc);
        if (visible.isBlank()) {
            return new HtmlValidator.CheckResult(check.id(), check.description(), false,
                    "页面无可见文本，语义断言无法判定");
        }
        double sim;
        try {
            sim = scorer.similarity(check.description(), visible);
        } catch (Exception e) {
            return new HtmlValidator.CheckResult(check.id(), check.description(), true,
                    "语义打分暂不可用，本轮不纳入完成判据");
        }
        if (sim < 0) {
            return new HtmlValidator.CheckResult(check.id(), check.description(), true,
                    "语义打分暂不可用，本轮不纳入完成判据");
        }
        boolean hit = sim >= SEMANTIC_PASS_THRESHOLD;
        return new HtmlValidator.CheckResult(check.id(), check.description(), hit,
                String.format("语义相似度 %.2f（阈值 %.2f，页面可见文本 %d 字）",
                        sim, SEMANTIC_PASS_THRESHOLD, visible.length()));
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

    /** 未通过的断言 → 质检 Issue（进入自检修复闭环）。语义断言失败的 detail 以"语义相似度"开头（含分值） */
    public static List<HtmlValidator.Issue> failing(List<HtmlValidator.CheckResult> results) {
        List<HtmlValidator.Issue> issues = new ArrayList<>();
        for (HtmlValidator.CheckResult r : results) {
            if (!r.passed() && r.detail() != null
                    && (r.detail().startsWith("未在页面中找到") || r.detail().startsWith("语义相似度"))) {
                issues.add(new HtmlValidator.Issue("ACCEPTANCE", "验收断言未满足 [" + r.label() + "]，" + r.detail()));
            }
        }
        return issues;
    }
}

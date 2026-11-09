package com.codecanvas.acceptance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 验收断言集（C4）：由 PRD 自动生成，作为"生成完成"的客观判据。
 *
 * <p>解析刻意宽容：模型输出可能混有解释文字或 Markdown 围栏，解析失败返回空集
 * （不阻断交付），与编排层结构化分诊的容错策略一致。
 */
public record AcceptanceSpec(List<AcceptanceCheck> checks) {

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static AcceptanceSpec empty() {
        return new AcceptanceSpec(List.of());
    }

    public boolean isEmpty() {
        return checks == null || checks.isEmpty();
    }

    /** 静态可检断言数（完成判据的覆盖面） */
    public long staticCount() {
        return checks == null ? 0 : checks.stream().filter(AcceptanceCheck::isStatic).count();
    }

    @SuppressWarnings("unchecked")
    public static AcceptanceSpec parse(String text) {
        if (text == null || text.isBlank()) {
            return empty();
        }
        try {
            String json = text.trim();
            int s = json.indexOf("{");
            int e = json.lastIndexOf("}");
            if (s < 0 || e <= s) {
                return empty();
            }
            json = json.substring(s, e + 1);
            Map<String, Object> root = LENIENT.readValue(json, Map.class);
            Object raw = root.get("checks");
            if (!(raw instanceof List<?> list)) {
                return empty();
            }
            List<AcceptanceCheck> checks = list.stream()
                    .limit(5) // 断言不在多而在准：超过 5 条说明模型没抓住重点
                    .map(item -> toCheck(item))
                    .filter(c -> c != null && c.target() != null && !c.target().isBlank()
                            && c.description() != null && !c.description().isBlank())
                    .toList();
            return new AcceptanceSpec(checks);
        } catch (Exception ex) {
            return empty();
        }
    }

    /**
     * 字段级语义契约校验（P0-3 护栏判据）：返回违规清单，空列表 = 通过。
     *
     * <p>与 {@link #parse} 分工明确：parse 负责「尽量把脏输出抠出结构」，
     * 这里负责「抠出来的东西语义上能不能用」。以前只有前者，模型输出缺字段时
     * 直接静默返回空断言集，界面上看起来一切正常，实际完成判据被悄悄丢掉了。
     *
     * <p>只挑语义承重的字段（id / kind / target / description），与 triagent P0-1 的
     * validateTriage 同一取舍：约束越多，无意义的定向修复调用越多、越烧钱。
     *
     * <p>已知边界：parse 会过滤掉 target/description 为空的条目，被丢弃的脏项不在本判据内；
     * 它们体现为 checks 条数变少乃至为空，仍会被下面第一条兜住。
     */
    public static List<String> violations(AcceptanceSpec spec, String rawOutput) {
        List<String> out = new ArrayList<>();
        if (rawOutput == null || !rawOutput.contains("{") || !rawOutput.contains("}")) {
            out.add("输出不是 JSON 对象（可能混有正文、Markdown 围栏或字段残缺）");
        }
        if (spec == null || spec.isEmpty()) {
            out.add("checks 为空或无法解析出有效断言");
            return out;
        }
        List<AcceptanceCheck> checks = spec.checks();
        if (checks.size() > 5) {
            out.add("checks 超过 5 条，模型没抓住重点");
        }
        Set<String> allowedKinds = Set.of("dom", "content", "behavior", "semantic");
        for (int i = 0; i < checks.size(); i++) {
            AcceptanceCheck c = checks.get(i);
            int n = i + 1;
            if (bad(c.id())) {
                out.add("第 " + n + " 条断言 id 缺失");
            }
            if (bad(c.target())) {
                out.add("第 " + n + " 条断言 target 缺失");
            }
            if (bad(c.description())) {
                out.add("第 " + n + " 条断言 description 缺失");
            }
            String kind = c.kind() == null ? "" : c.kind().toLowerCase(Locale.ROOT);
            if (!allowedKinds.contains(kind)) {
                out.add("第 " + n + " 条断言 kind 非法：" + c.kind());
            }
        }
        return out;
    }

    /** toCheck 用 String.valueOf，缺字段会落成字面量 "null"，这里必须当成缺失。 */
    private static boolean bad(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value);
    }

    private static AcceptanceCheck toCheck(Object item) {
        if (!(item instanceof Map<?, ?> m)) {
            return null;
        }
        return new AcceptanceCheck(
                String.valueOf(m.get("id")),
                String.valueOf(m.get("kind")),
                m.get("target") == null ? null : String.valueOf(m.get("target")),
                m.get("description") == null ? null : String.valueOf(m.get("description")));
    }
}

package com.codecanvas.acceptance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

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

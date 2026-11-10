package com.codecanvas.eval;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.common.CodeExtractor;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.common.TokenEstimator;
import com.codecanvas.config.LlmProperties;
import com.codecanvas.llm.BudgetPolicy;
import com.codecanvas.llm.ModelRouter;
import com.codecanvas.llm.ModelTier;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TaskBudget;
import com.codecanvas.llm.TokenMeter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeCanvas 离线评测基线（C2）：
 * 100 个确定性案例，覆盖模型路由 / HTML 校验器 / 代码提取 / Token 估算 / 验收断言 / 预算调度。
 * 一条命令出报告：mvn test -Dtest=CodeCanvasEvalHarnessTest（backend 模块下）
 * 报告落盘 evals/reports/（latest-report.json + latest.md）。
 * 定位：重构门禁基线 —— AgentOrchestrator 拆分等重构后重跑，通过率降幅不得超过 2pp。
 * 预算案例定价口径（每千 token 元）：旗舰 0.004/0.016，标准 0.001/0.004，轻量 0.0005/0.002。
 */
class CodeCanvasEvalHarnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Path repoRoot;
    private static ModelRouter router;
    private static BudgetPolicy budgetPolicy;

    private record CaseResult(String id, String category, boolean passed, String expected, String actual, long nanos) {}

    @BeforeAll
    static void setup() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        repoRoot = "backend".equals(cwd.getFileName() == null ? "" : cwd.getFileName().toString())
                ? cwd.getParent() : cwd;
        LlmProperties props = new LlmProperties();
        props.getPricing().setFlagshipInput(0.004);
        props.getPricing().setFlagshipOutput(0.016);
        props.getPricing().setStandardInput(0.001);
        props.getPricing().setStandardOutput(0.004);
        props.getPricing().setLightInput(0.0005);
        props.getPricing().setLightOutput(0.002);
        router = new ModelRouter(props);
        budgetPolicy = new BudgetPolicy(new TokenMeter(props), router, true, 0, 0, 0, 3);
    }

    private List<Map<String, Object>> load(String file) throws IOException {
        Path p = repoRoot.resolve("evals/cases").resolve(file);
        assertTrue(Files.exists(p), "案例文件不存在: " + p);
        return MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8),
                new TypeReference<List<Map<String, Object>>>() {});
    }

    @Test
    void evalBaseline() throws IOException {
        List<CaseResult> results = new ArrayList<>();
        results.addAll(evalRouting());
        results.addAll(evalValidator());
        results.addAll(evalExtractor());
        results.addAll(evalTokens());
        results.addAll(evalAcceptance());
        results.addAll(evalBudget());

        assertEquals(100, results.size(), "案例总数必须为 100（数据集口径）");
        writeReport(results);

        List<String> failures = results.stream().filter(r -> !r.passed())
                .map(r -> r.id() + " 期望=" + r.expected() + " 实际=" + r.actual())
                .toList();
        assertTrue(failures.isEmpty(),
                "评测未全通过 " + (results.size() - failures.size()) + "/" + results.size() + ":\n"
                        + String.join("\n", failures));
    }

    private List<CaseResult> evalRouting() throws IOException {
        List<CaseResult> out = new ArrayList<>();
        for (Map<String, Object> c : load("routing.json")) {
            long t0 = System.nanoTime();
            RouteDecision d = router.route(purpose(str(c, "purpose")), str(c, "prompt"),
                    bool(c, "firstTurn", false), intOf(c.get("existingFiles"), 0));
            out.add(new CaseResult(str(c, "id"), "routing", d.tier().name().equals(str(c, "expect")),
                    str(c, "expect"), d.tier().name() + "(score=" + d.score() + ")", System.nanoTime() - t0));
        }
        return out;
    }

    private List<CaseResult> evalValidator() throws IOException {
        List<CaseResult> out = new ArrayList<>();
        for (Map<String, Object> c : load("validator.json")) {
            long t0 = System.nanoTime();
            List<String> actTypes = HtmlValidator.validate(str(c, "html")).stream()
                    .map(HtmlValidator.Issue::type).sorted().toList();
            List<String> expTypes = strList(c, "expectTypes").stream().sorted().toList();
            out.add(new CaseResult(str(c, "id"), "validator", actTypes.equals(expTypes),
                    expTypes.toString(), actTypes.toString(), System.nanoTime() - t0));
        }
        return out;
    }

    private List<CaseResult> evalExtractor() throws IOException {
        List<CaseResult> out = new ArrayList<>();
        for (Map<String, Object> c : load("extractor.json")) {
            long t0 = System.nanoTime();
            Object rawObj = c.get("raw");
            String actual = CodeExtractor.extractHtml(rawObj == null ? null : str(c, "raw"));
            List<String> mismatches = new ArrayList<>();
            if (c.containsKey("expectEquals") && !actual.equals(str(c, "expectEquals"))) {
                mismatches.add("equals 期望[" + str(c, "expectEquals") + "] 实际[" + actual + "]");
            }
            if (c.containsKey("expectStartsWith") && !actual.startsWith(str(c, "expectStartsWith"))) {
                mismatches.add("startsWith 期望 " + str(c, "expectStartsWith") + " 实际 " + preview(actual));
            }
            if (c.containsKey("expectContains") && !actual.contains(str(c, "expectContains"))) {
                mismatches.add("contains 期望 " + str(c, "expectContains"));
            }
            out.add(new CaseResult(str(c, "id"), "extractor", mismatches.isEmpty(),
                    String.valueOf(c.containsKey("expectEquals") ? c.get("expectEquals") : c.keySet()),
                    mismatches.isEmpty() ? "OK" : String.join("; ", mismatches), System.nanoTime() - t0));
        }
        return out;
    }

    private List<CaseResult> evalTokens() throws IOException {
        List<CaseResult> out = new ArrayList<>();
        for (Map<String, Object> c : load("tokens.json")) {
            long t0 = System.nanoTime();
            Object textObj = c.get("text");
            int actual = TokenEstimator.estimate(textObj == null ? null : str(c, "text"));
            int expected = intOf(c.get("expect"), -1);
            out.add(new CaseResult(str(c, "id"), "tokens", actual == expected,
                    String.valueOf(expected), String.valueOf(actual), System.nanoTime() - t0));
        }
        return out;
    }

    private List<CaseResult> evalAcceptance() throws IOException {
        List<CaseResult> out = new ArrayList<>();
        for (Map<String, Object> c : load("acceptance.json")) {
            long t0 = System.nanoTime();
            AcceptanceSpec spec = AcceptanceSpec.parse(str(c, "raw"));
            List<String> violations = AcceptanceSpec.violations(spec, str(c, "raw"));
            List<String> mismatches = new ArrayList<>();
            int expChecks = intOf(c.get("expectChecks"), -1);
            if (spec.checks().size() != expChecks) {
                mismatches.add("checks 期望 " + expChecks + " 实际 " + spec.checks().size());
            }
            int expViolations = intOf(c.get("expectViolations"), -1);
            if (violations.size() != expViolations) {
                mismatches.add("violations 期望 " + expViolations + " 实际 " + violations.size()
                        + " (" + String.join("/", violations) + ")");
            }
            out.add(new CaseResult(str(c, "id"), "acceptance", mismatches.isEmpty(),
                    "checks=" + expChecks + " violations=" + expViolations,
                    mismatches.isEmpty() ? "OK" : String.join("; ", mismatches), System.nanoTime() - t0));
        }
        return out;
    }

    private List<CaseResult> evalBudget() throws IOException {
        List<CaseResult> out = new ArrayList<>();
        for (Map<String, Object> c : load("budget.json")) {
            long t0 = System.nanoTime();
            Map<?, ?> setup = (Map<?, ?>) c.get("setup");
            int maxHeal = intOf(setup.get("maxHeal"), 3);
            LlmProperties props = new LlmProperties();
            props.getPricing().setFlagshipInput(0.004);
            props.getPricing().setFlagshipOutput(0.016);
            props.getPricing().setStandardInput(0.001);
            props.getPricing().setStandardOutput(0.004);
            props.getPricing().setLightInput(0.0005);
            props.getPricing().setLightOutput(0.002);
            BudgetPolicy policy = new BudgetPolicy(new TokenMeter(props), router, true, 0, 0, 0, maxHeal);
            RouteDecision route = new RouteDecision(ModelTier.valueOf(str(c, "route")), "eval-model",
                    9, "eval");
            Object budgetObj = c.get("budget");
            TaskBudget budget = budgetObj == null ? null
                    : new TaskBudget(Double.valueOf(str(c, "budget")), null, null);
            BudgetPolicy.BudgetDecision d = policy.decide(route, budget,
                    intOf(c.get("promptTokens"), 0), intOf(c.get("plannedTokens"), 0));
            Map<?, ?> exp = (Map<?, ?>) c.get("expect");
            List<String> mismatches = new ArrayList<>();
            if (!d.tier().name().equals(str(exp, "tier"))) {
                mismatches.add("tier 期望 " + str(exp, "tier") + " 实际 " + d.tier());
            }
            if (d.overBudget() != Boolean.parseBoolean(str(exp, "overBudget"))) {
                mismatches.add("overBudget 期望 " + str(exp, "overBudget") + " 实际 " + d.overBudget());
            }
            if (d.allowedHealAttempts() != intOf(exp.get("allowedHeal"), -1)) {
                mismatches.add("allowedHeal 期望 " + exp.get("allowedHeal")
                        + " 实际 " + d.allowedHealAttempts());
            }
            out.add(new CaseResult(str(c, "id"), "budget", mismatches.isEmpty(),
                    String.valueOf(exp), mismatches.isEmpty() ? "OK" : String.join("; ", mismatches),
                    System.nanoTime() - t0));
        }
        return out;
    }

    // ---------- 报告 ----------

    private void writeReport(List<CaseResult> results) throws IOException {
        Map<String, List<CaseResult>> byCat = new LinkedHashMap<>();
        for (CaseResult r : results) {
            byCat.computeIfAbsent(r.category(), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> catStats = new ArrayList<>();
        for (Map.Entry<String, List<CaseResult>> e : byCat.entrySet()) {
            long passed = e.getValue().stream().filter(CaseResult::passed).count();
            catStats.add(Map.of("category", e.getKey(), "total", e.getValue().size(),
                    "passed", passed, "failed", e.getValue().size() - passed,
                    "passRate", percent(passed, e.getValue().size())));
        }
        long passed = results.stream().filter(CaseResult::passed).count();
        List<Long> nanos = results.stream().map(CaseResult::nanos).sorted().toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        report.put("total", results.size());
        report.put("passed", passed);
        report.put("failed", results.size() - passed);
        report.put("passRate", percent(passed, results.size()));
        report.put("harnessP50us", nanos.get(nanos.size() / 2) / 1000);
        report.put("harnessP95us", nanos.get((int) (nanos.size() * 0.95)) / 1000);
        report.put("byCategory", catStats);
        List<Map<String, Object>> failedCases = new ArrayList<>();
        for (CaseResult r : results) {
            if (!r.passed()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", r.id());
                f.put("category", r.category());
                f.put("expected", r.expected());
                f.put("actual", r.actual());
                failedCases.add(f);
            }
        }
        report.put("failedCases", failedCases);

        Path reports = repoRoot.resolve("evals/reports");
        Files.createDirectories(reports);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Files.writeString(reports.resolve("report-" + stamp + ".json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(reports.resolve("latest-report.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder();
        md.append("# CodeCanvas 评测基线报告\n\n");
        md.append("- 时间: ").append(report.get("timestamp")).append('\n');
        md.append("- 总案例: ").append(results.size()).append(" 通过: ").append(passed)
                .append(" 失败: ").append(results.size() - passed)
                .append(" 通过率: ").append(report.get("passRate")).append("\n\n");
        md.append("| 维度 | 案例 | 通过 | 失败 | 通过率 |\n|---|---|---|---|---|\n");
        for (Map<String, Object> s : catStats) {
            md.append("| ").append(s.get("category")).append(" | ").append(s.get("total"))
                    .append(" | ").append(s.get("passed")).append(" | ").append(s.get("failed"))
                    .append(" | ").append(s.get("passRate")).append(" |\n");
        }
        if (!failedCases.isEmpty()) {
            md.append("\n## 失败案例\n\n");
            for (Map<String, Object> f : failedCases) {
                md.append("- ").append(f.get("id")).append(" (").append(f.get("category"))
                        .append(") 期望=").append(f.get("expected")).append(" 实际=").append(f.get("actual")).append('\n');
            }
        }
        Files.writeString(reports.resolve("latest.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println("[eval] 报告已写入 " + reports.resolve("latest.md"));
        System.out.println(md);
    }

    // ---------- 工具 ----------

    private static com.codecanvas.llm.LlmPurpose purpose(String name) {
        return com.codecanvas.llm.LlmPurpose.valueOf(name);
    }

    private static String str(Map<?, ?> m, String k) {
        return String.valueOf(m.get(k));
    }

    private static boolean bool(Map<?, ?> m, String k, boolean def) {
        return !m.containsKey(k) ? def : Boolean.parseBoolean(String.valueOf(m.get(k)));
    }

    private static int intOf(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> strList(Map<?, ?> m, String k) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) m.get(k)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String preview(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    private static String percent(long passed, long total) {
        return Math.round(passed * 1000.0 / total) / 10.0 + "%";
    }
}

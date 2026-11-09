package com.codecanvas.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于 Node + Playwright 的运行时验证器（P1-4 加固）。
 *
 * <p>实现策略：不把浏览器逻辑写进 Java（避免引入重量级 Java 绑定依赖），
 * 而是把生成的 HTML 交给 {@code tools/runtime-verify.mjs} 这个 Node 脚本，
 * 由它用 Playwright 真正加载页面、点按钮、断言、截图，再把结构化结果以 JSON 回传。
 * 后端只负责"写临时文件 → 起进程 → 读 JSON"，进程边界让浏览器崩溃也不会拖垮主服务。
 *
 * <p><b>P1-4 修掉的诚实性问题</b>：改造前 {@link #available()} 只跑一次
 * {@code node --version}。Node 装了但 Playwright / Chromium 没装时，它会返回 true，
 * 于是配置界面显示"执行级验证已启用"，而每次验证其实都回一张 skipped 报告。
 * 现在改为<b>真实能力探测</b>：拿一页最小 HTML 走完整条 Node → Playwright → Chromium 链路，
 * 只有 {@code verified=true && skipped=false} 才认定可用，结果缓存、可 {@link #recheck()} 重探。
 *
 * <p>优雅降级：探测失败时 {@link #available()} 返回 false 并给出真实原因，
 * {@link #verify} 直接回 skipped 报告，不抛异常、不阻断交付。
 */
@Component
public class NodePlaywrightRuntimeVerifier implements RuntimeVerifier {

    private static final Logger log = LoggerFactory.getLogger(NodePlaywrightRuntimeVerifier.class);

    private static final String SCRIPT_NAME = "runtime-verify.mjs";

    /** 探测页要尽量小：不点按钮、不断言、不等渲染，只验证浏览器链路能不能跑通。 */
    private static final String PROBE_HTML =
            "<!DOCTYPE html><html><body><p>codecanvas capability probe</p></body></html>";

    private final ObjectMapper mapper = new ObjectMapper();

    private final String nodeCommand;
    private final String configuredScript;
    private final long timeoutMs;

    private volatile String resolvedScript;
    private volatile Capability capability;

    public NodePlaywrightRuntimeVerifier(
            @Value("${codecanvas.runtime-verify.node-command:node}") String nodeCommand,
            @Value("${codecanvas.runtime-verify.node-script:tools/runtime-verify.mjs}") String configuredScript,
            @Value("${codecanvas.runtime-verify.timeout-ms:15000}") int timeoutMs) {
        this.nodeCommand = nodeCommand;
        this.configuredScript = configuredScript;
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.resolvedScript = resolveScript(configuredScript);
    }

    /** 在候选路径里找第一个真实存在的脚本文件。 */
    private static String resolveScript(String configured) {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.add("tools/" + SCRIPT_NAME);
        // 从工作目录逐级向上找，覆盖"后端 jar 运行在 backend/ 或仓库根"两种情况
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            candidates.add(p.resolve("tools/" + SCRIPT_NAME).toString());
        }
        for (String c : candidates) {
            if (c != null && Files.exists(Paths.get(c))) {
                return c;
            }
        }
        return null;
    }

    @Override
    public boolean available() {
        if (resolvedScript == null) {
            return false;
        }
        Capability c = capability;
        if (c == null) {
            synchronized (this) {
                c = capability;
                if (c == null) {
                    c = probe();
                    capability = c;
                }
            }
        }
        return c.ready();
    }

    @Override
    public String unavailableReason() {
        if (resolvedScript == null) {
            return "未找到运行时验证脚本 tools/" + SCRIPT_NAME;
        }
        Capability c = capability;
        if (c == null) {
            available();
            c = capability;
        }
        return c == null || c.ready() ? null : c.reason();
    }

    /** 现场重探一次（装好 Chromium 后不必重启服务）。 */
    public void recheck() {
        synchronized (this) {
            resolvedScript = resolveScript(configuredScript);
            capability = null;
        }
        available();
    }

    /** 真实能力探测：Node 存在还不够，必须让浏览器把一页最小 HTML 真跑完。 */
    private Capability probe() {
        if (!checkNode()) {
            return new Capability(false, "未检测到 Node 运行时，无法执行无头浏览器验证");
        }
        RuntimeReport report = execute(PROBE_HTML,
                new RuntimeSpec(false, false, List.of(), List.of(), null, 0));
        if (report.skipped()) {
            String reason = report.issues().isEmpty() ? "环境不可用" : report.issues().get(0).message();
            return new Capability(false, "Playwright / Chromium 不可用：" + reason);
        }
        if (!report.verified()) {
            return new Capability(false, "无头浏览器未能完成一次验证");
        }
        log.info("执行级验证能力探测通过：Node + Playwright + Chromium 可用");
        return new Capability(true, null);
    }

    private boolean checkNode() {
        try {
            Process p = new ProcessBuilder(nodeCommand, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Override
    public RuntimeReport verify(String html, RuntimeSpec spec) {
        if (!available()) {
            return RuntimeReport.skipped(unavailableReason() == null ? "环境不可用" : unavailableReason());
        }
        return execute(html, spec);
    }

    /** 真正起进程执行验证；不检查 available()，避免与能力探测互相递归。 */
    private RuntimeReport execute(String html, RuntimeSpec spec) {
        Path htmlFile = null;
        Path specFile = null;
        try {
            htmlFile = Files.createTempFile("cc-rv-", ".html");
            specFile = Files.createTempFile("cc-rv-spec-", ".json");
            Files.writeString(htmlFile, html == null ? "" : html, StandardCharsets.UTF_8);
            Files.writeString(specFile, mapper.writeValueAsString(spec.toScriptArgs()), StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(nodeCommand, resolvedScript,
                    htmlFile.toString(), specFile.toString());
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String out;
            try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                bos.writeBytes(process.getInputStream().readAllBytes());
                out = bos.toString(StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return RuntimeReport.skipped("运行时验证超时（" + timeoutMs + "ms），已降级为仅静态校验");
            }
            return parseReport(out);
        } catch (IOException e) {
            log.warn("运行时验证执行失败，降级为仅静态校验", e);
            return RuntimeReport.skipped("运行时验证执行失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RuntimeReport.skipped("运行时验证被中断");
        } finally {
            deleteQuietly(htmlFile);
            deleteQuietly(specFile);
        }
    }

    /** 解析 Node 脚本回传的 JSON；任何异常都降级成 skipped，绝不阻断主流程。 */
    RuntimeReport parseReport(String json) {
        if (json == null || json.isBlank()) {
            return RuntimeReport.skipped("运行时验证未返回结果");
        }
        try {
            JsonNode root = mapper.readTree(json);
            boolean skipped = root.path("skipped").asBoolean(false);
            boolean verified = root.path("verified").asBoolean(false);
            String screenshot = root.path("screenshotPath").asText(null);
            List<RuntimeIssue> issues = new ArrayList<>();
            JsonNode arr = root.path("issues");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    RuntimeIssueType type;
                    try {
                        type = RuntimeIssueType.valueOf(node.path("type").asText("SKIPPED"));
                    } catch (IllegalArgumentException ex) {
                        type = RuntimeIssueType.SKIPPED;
                    }
                    issues.add(new RuntimeIssue(
                            type,
                            node.path("message").asText(""),
                            node.path("source").asText(null),
                            node.path("line").asInt(0),
                            node.path("stack").asText(null)));
                }
            }
            return new RuntimeReport(skipped, verified, issues, screenshot);
        } catch (Exception e) {
            return RuntimeReport.skipped("运行时验证结果解析失败：" + e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    private record Capability(boolean ready, String reason) {
    }
}

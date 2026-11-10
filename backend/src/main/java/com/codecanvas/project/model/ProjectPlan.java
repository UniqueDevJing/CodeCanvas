package com.codecanvas.project.model;

import com.codecanvas.common.JsonText;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目规划（C3）：生成前的结构化拆解——应用名、技术形态、入口文件与文件清单。
 *
 * <p>由一次轻量 LLM 调用产出；解析失败返回 null，调用方降级为单文件生成路径。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectPlan(String appName,
                          String summary,
                          String tech,
                          String entry,
                          List<PlanFile> files) {

    public static final String TECH_HTML = "html";
    public static final String TECH_REACT = "react";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_FILES = 8;

    public ProjectPlan {
        files = files == null ? List.of() : List.copyOf(files);
        tech = TECH_REACT.equalsIgnoreCase(tech) ? TECH_REACT : TECH_HTML;
        entry = entry == null || entry.isBlank() ? "index.html" : entry.trim();
    }

    /** 文件数是否在可生成范围内。 */
    public boolean isGeneratable() {
        return !files.isEmpty() && files.size() <= MAX_FILES;
    }

    public boolean isReact() {
        return TECH_REACT.equals(tech);
    }

    /** 规划里登记的文件路径集合。 */
    public List<String> plannedPaths() {
        List<String> paths = new ArrayList<>();
        for (PlanFile f : files) {
            if (f.path() != null && !f.path().isBlank()) {
                paths.add(f.path());
            }
        }
        return paths;
    }

    /**
     * 从模型回复解析规划。
     *
     * @return 合法且 isGeneratable 返回规划；否则返回 null（降级单文件路径）
     */
    public static ProjectPlan parse(String llmText) {
        ProjectPlan plan = JsonText.extract(llmText, ProjectPlan.class);
        if (plan == null) {
            return null;
        }
        // 过滤掉路径非法的登记项
        List<PlanFile> valid = new ArrayList<>();
        for (PlanFile f : plan.files) {
            if (f != null && f.path() != null && !f.path().isBlank()
                    && com.codecanvas.agent.tool.AgentWorkspace.checkPath(f.path()) == null) {
                valid.add(f);
            }
        }
        ProjectPlan cleaned = new ProjectPlan(plan.appName, plan.summary, plan.tech, plan.entry, valid);
        return cleaned.isGeneratable() ? cleaned : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanFile(String path, String purpose) {
    }
}

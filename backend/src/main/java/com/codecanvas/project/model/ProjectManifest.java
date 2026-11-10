package com.codecanvas.project.model;

import com.codecanvas.project.ProjectFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * 项目清单（C3）：一个版本的交付自描述——入口文件、技术形态、文件统计。
 *
 * <p>随版本快照一起落库（文件名 {@link #FILENAME}），后续浏览器验收（C5）
 * 按 entry 打开页面、恢复任务（C6）按 manifest 判断版本完整性。
 */
public record ProjectManifest(String entry,
                              String tech,
                              int fileCount,
                              List<String> paths,
                              long totalBytes,
                              String generatedAt) {

    /** manifest 在项目文件列表中的固定路径 */
    public static final String FILENAME = "codecanvas.manifest.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProjectManifest from(String entry, String tech, List<ProjectFile> files) {
        long bytes = 0;
        List<String> paths = new java.util.ArrayList<>();
        if (files != null) {
            for (ProjectFile f : files) {
                if (f == null) {
                    continue;
                }
                paths.add(f.path());
                bytes += f.content() == null ? 0
                        : f.content().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return new ProjectManifest(entry, tech, paths.size(), paths, bytes, Instant.now().toString());
    }

    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (Exception e) {
            // record 字段都是基本类型/String/List，序列化不会失败；兜底给出可解析的最小 JSON
            return "{\"entry\":\"" + entry + "\",\"tech\":\"" + tech + "\",\"fileCount\":" + fileCount + "}";
        }
    }

    /** 反序列化（C5/C6 从落库文件恢复用）；解析失败返回 null。 */
    public static ProjectManifest fromJson(String json) {
        try {
            return MAPPER.readValue(json, ProjectManifest.class);
        } catch (Exception e) {
            return null;
        }
    }
}

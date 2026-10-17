package com.codecanvas.agent.tool;

import com.codecanvas.project.ProjectFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 运行的可写文件工作区。
 *
 * <p>Agent 的产出先落在这里，全部阶段跑完、校验通过之后才作为版本快照提交。
 * 这样"生成到一半失败"不会污染项目的历史版本。
 */
public class AgentWorkspace {

    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<String> changed = new ArrayList<>();

    public void seed(List<ProjectFile> existing) {
        if (existing == null) {
            return;
        }
        for (ProjectFile file : existing) {
            files.put(file.path(), file.content());
        }
    }

    /**
     * 写入前校验路径。工作区是内存 Map，写入的 path 原样进项目 JSON，
     * 这里是路径的唯一守门人：拒绝空路径、绝对路径、目录穿越与反斜杠，
     * 避免把 "../../etc/passwd" 这类脏键永久固化进项目数据。
     *
     * <p>控制字符（含 NUL）一并拒绝：path 会被写进项目 JSON 并原样回显到前端，
     * 而 NUL 在 Java 的 {@code Paths.get(String)} 里会<b>截断</b>字符串，
     * 于是「校验通过的那个 key」与「真正落盘时用的那个 key」不是同一个。
     * 即使当前工作区是纯内存、无文件系统 IO，这个键也会随项目 JSON 一路带到
     * 导出/导入与前端渲染，是个很难排查的隐性脏数据来源。
     *
     * @return 错误说明；null 表示合法
     */
    public static String checkPath(String path) {
        if (path == null || path.isBlank()) {
            return "路径为空";
        }
        if (path.length() > 200) {
            return "路径过长（>200 字符）";
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "路径不允许包含控制字符（位置 " + i + "）";
            }
        }
        if (path.indexOf('\\') >= 0) {
            return "路径不允许包含反斜杠（统一用 /）";
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return "不允许绝对路径";
        }
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return "路径段非法：" + seg;
            }
        }
        return null;
    }

    public void write(String path, String content) {
        String invalid = checkPath(path);
        if (invalid != null) {
            throw new IllegalArgumentException("非法文件路径（" + invalid + "）: " + path);
        }
        files.put(path, content);
        if (!changed.contains(path)) {
            changed.add(path);
        }
    }

    public String read(String path) {
        return files.get(path);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public List<String> paths() {
        return new ArrayList<>(files.keySet());
    }

    public List<String> changedPaths() {
        return new ArrayList<>(changed);
    }

    public List<ProjectFile> snapshot() {
        List<ProjectFile> result = new ArrayList<>();
        files.forEach((path, content) -> result.add(new ProjectFile(path, content)));
        return result;
    }

    /** 主文件内容，用于预览与校验。 */
    public String mainContent() {
        return files.isEmpty() ? "" : files.values().iterator().next();
    }

    public String mainPath() {
        return files.isEmpty() ? "index.html" : files.keySet().iterator().next();
    }

    /** 逐行滚动历史摘要，作为多轮迭代的压缩上下文。 */
    public String historySummary(List<com.codecanvas.project.VersionSnapshot> versions) {
        if (versions == null || versions.isEmpty()) {
            return "（这是第一轮生成，没有历史变更）";
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, versions.size() - 5);
        for (int i = from; i < versions.size(); i++) {
            sb.append("- ").append(versions.get(i).label()).append("：")
                    .append(versions.get(i).summary()).append('\n');
        }
        return sb.toString().trim();
    }
}

package com.codecanvas.common;

import com.codecanvas.project.ProjectFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件预览装配器（C3）：把入口文件里对项目内文件的相对引用内联，
 * 使多文件项目在"单 HTML 直出"的预览与发布链路里照样可运行。
 *
 * <p>内联规则：
 * <ul>
 *   <li>{@code <link rel="stylesheet" href="styles.css">} → {@code <style>...</style>}</li>
 *   <li>{@code <script src="app.js"></script>} → 保留其余属性的内联 script（含 type="text/babel"）</li>
 * </ul>
 * 只处理项目内真实存在的文件；http(s)/data:/绝对路径引用一律不动。
 *
 * <p>C4 引入文件级沙箱后，此内联层仍是降级通道：文件级沙箱不可用时预览照常出图。
 */
public final class PreviewAssembler {

    private static final Pattern LINK_CSS = Pattern.compile(
            "<link\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?/?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\b([^>]*?)\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']([^>]*?)>\\s*</script>",
            Pattern.CASE_INSENSITIVE);

    private PreviewAssembler() {
    }

    /**
     * 装配入口文件：返回内联了项目内相对引用的完整 HTML。
     * entry 文件缺失返回空串。
     */
    public static String assemble(String entryPath, List<ProjectFile> files) {
        Map<String, String> byPath = new HashMap<>();
        String entry = null;
        String first = "";
        if (files != null) {
            for (ProjectFile f : files) {
                if (f == null || f.path() == null) {
                    continue;
                }
                String content = f.content() == null ? "" : f.content();
                byPath.put(normalize(f.path()), content);
                if (f.path().equals(entryPath)) {
                    entry = content;
                }
                if (first.isEmpty()) {
                    first = content;
                }
            }
        }
        if (entry == null || entry.isEmpty()) {
            // 入口缺失时回退第一个文件（历史单文件项目），再缺就无从装配
            entry = first;
        }
        if (entry.isEmpty()) {
            return "";
        }
        return inline(entry, byPath, 0);
    }

    private static String inline(String html, Map<String, String> byPath, int depth) {
        if (depth > 2) { // 防御引用环
            return html;
        }
        String result = inlineLinks(html, byPath, depth);
        result = inlineScripts(result, byPath, depth);
        return result;
    }

    private static String inlineLinks(String html, Map<String, String> byPath, int depth) {
        Matcher m = LINK_CSS.matcher(html);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String css = resolve(m.group(1), byPath);
            if (css == null) {
                continue;
            }
            sb.append(html, last, m.start());
            String inlined = css.contains("</style")
                    ? css.replace("</style", "<\\/style") // 内联的 CSS 不得提前闭合 style 标签
                    : css;
            sb.append("<style>\n").append(inlined).append("\n</style>");
            last = m.end();
        }
        sb.append(html.substring(last));
        return sb.toString();
    }

    private static String inlineScripts(String html, Map<String, String> byPath, int depth) {
        Matcher m = SCRIPT_SRC.matcher(html);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String js = resolve(m.group(2), byPath);
            if (js == null) {
                continue;
            }
            sb.append(html, last, m.start());
            String attrs = (m.group(1) + " " + m.group(3)).trim();
            String content = js.contains("</script")
                    ? js.replace("</script", "<\\/script") // 内联的 JS 不得提前闭合 script 标签
                    : js;
            sb.append("<script").append(attrs.isEmpty() ? "" : " " + attrs).append(">\n")
                    .append(content).append("\n</script>");
            last = m.end();
        }
        sb.append(html.substring(last));
        return sb.toString();
    }

    /** 仅解析项目内文件；返回 null 表示不是项目内引用，保持原样。 */
    private static String resolve(String ref, Map<String, String> byPath) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")
                || lower.startsWith("data:") || trimmed.startsWith("/") || trimmed.startsWith("#")) {
            return null;
        }
        // 去掉查询串与锚点
        int cut = trimmed.indexOf('?');
        if (cut < 0) {
            cut = trimmed.indexOf('#');
        }
        String path = cut >= 0 ? trimmed.substring(0, cut) : trimmed;
        // 入口相对路径解析（入口默认 index.html，同目录引用即原样）
        return byPath.get(normalize(path));
    }

    private static String normalize(String path) {
        return path == null ? null : path.trim().replace('\\', '/');
    }
}

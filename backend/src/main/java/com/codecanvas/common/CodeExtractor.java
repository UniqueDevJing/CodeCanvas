package com.codecanvas.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型输出里提取代码。
 *
 * <p>模型经常会自作主张加上"好的，我来帮你生成"这类开场白，或者把代码包在 Markdown 围栏里。
 * 这一层做兜底：不管它怎么包装，都把真正的 HTML 抠出来。
 */
public final class CodeExtractor {

    private CodeExtractor() {
    }

    private static final Pattern FENCED = Pattern.compile(
            "```(?:html|HTML)?\\s*([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern DOC_START = Pattern.compile("(?i)<!DOCTYPE|<html");

    public static String extractHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();

        Matcher fenced = FENCED.matcher(text);
        if (fenced.find()) {
            String inner = fenced.group(1).trim();
            if (!inner.isEmpty()) {
                return trimToDocument(inner);
            }
        }
        return trimToDocument(text);
    }

    /** 从第一个文档标记开始截取，丢掉前面的寒暄。 */
    private static String trimToDocument(String text) {
        Matcher start = DOC_START.matcher(text);
        if (start.find()) {
            String candidate = text.substring(start.start()).trim();
            int end = candidate.toLowerCase().lastIndexOf("</html>");
            if (end > 0) {
                return candidate.substring(0, end + "</html>".length());
            }
            return candidate;
        }
        return text;
    }
}

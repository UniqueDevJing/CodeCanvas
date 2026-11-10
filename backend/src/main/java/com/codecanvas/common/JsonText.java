package com.codecanvas.common;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从模型回复文本中容错提取 JSON 对象并反序列化（C3）。
 *
 * <p>模型输出常见三种形态：裸 JSON、```json 围栏、前后夹杂说明文字。
 * 统一在这里处理，解析失败返回 null，由调用方决定降级路径。
 */
public final class JsonText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonText() {
    }

    /**
     * 提取并反序列化。
     *
     * @return 成功返回目标对象；文本为空、找不到 JSON 或反序列化失败返回 null
     */
    public static <T> T extract(String text, Class<T> type) {
        String json = locate(text);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 定位文本中的 JSON 对象：优先取 ```json 围栏内容，其次取首个
     * 平衡的大括号块（考虑字符串内的引号转义）。
     */
    public static String locate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String fenced = extractFenced(text);
        if (fenced != null && looksLikeObject(fenced)) {
            return fenced;
        }
        return firstBalancedObject(text);
    }

    private static String extractFenced(String text) {
        int idx = text.indexOf("```json");
        if (idx < 0) {
            idx = text.indexOf("```JSON");
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + 7;
        int end = text.indexOf("```", start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).trim();
    }

    private static boolean looksLikeObject(String s) {
        return s.startsWith("{");
    }

    /** 扫描首个平衡 `{...}` 块；字符串内的大括号不参与计数。 */
    private static String firstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}

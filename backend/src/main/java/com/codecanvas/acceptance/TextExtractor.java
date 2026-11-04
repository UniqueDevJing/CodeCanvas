package com.codecanvas.acceptance;

/**
 * HTML 可见文本提取（C-A 语义断言用）：剥掉 script/style 与全部标签，只留用户能看到的话。
 * 纯 Java、零依赖、可单测；实体只做最常见的几个（够断言用，不追求完整 HTML5 解码表）。
 */
public final class TextExtractor {

    private TextExtractor() {
    }

    public static String visibleText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = html;
        s = s.replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
        return s.replaceAll("\\s+", " ").trim();
    }
}

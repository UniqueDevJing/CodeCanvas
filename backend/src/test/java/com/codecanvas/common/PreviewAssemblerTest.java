package com.codecanvas.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C3 多文件预览装配测试。 */
class PreviewAssemblerTest {

    private static List<com.codecanvas.project.ProjectFile> files(Map<String, String> map) {
        return map.entrySet().stream()
                .map(e -> new com.codecanvas.project.ProjectFile(e.getKey(), e.getValue()))
                .toList();
    }

    @Test
    void singleFileUnchanged() {
        String html = "<html><head><title>t</title></head><body>hi</body></html>";
        assertEquals(html, PreviewAssembler.assemble("index.html", files(Map.of("index.html", html))));
    }

    @Test
    void cssLinkInlined() {
        String entry = "<html><head><link rel=\"stylesheet\" href=\"styles.css\"></head><body></body></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of(
                "index.html", entry,
                "styles.css", "body { color: #333; }")));
        assertTrue(out.contains("<style>"), "link 应被替换为 style 块");
        assertTrue(out.contains("body { color: #333; }"), "CSS 内容应被内联");
        assertTrue(!out.contains("styles.css"), "相对引用应消失");
    }

    @Test
    void scriptSrcInlined() {
        String entry = "<html><body><script src=\"app.js\"></script></body></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of(
                "index.html", entry,
                "app.js", "console.log(1);")));
        assertTrue(out.contains("console.log(1);"), "JS 内容应被内联");
        assertTrue(!out.contains("src=\"app.js\""), "src 引用应消失");
    }

    @Test
    void babelScriptKeepsType() {
        String entry = "<html><body><script type=\"text/babel\" src=\"app.jsx\"></script></body></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of(
                "index.html", entry,
                "app.jsx", "const App = () => 1;")));
        assertTrue(out.contains("type=\"text/babel\""), "babel 类型必须保留");
        assertTrue(out.contains("const App = () => 1;"));
    }

    @Test
    void externalReferenceUntouched() {
        String entry = "<html><head>"
                + "<link rel=\"stylesheet\" href=\"https://cdn.example.com/x.css\">"
                + "<script src=\"https://unpkg.com/react\"></script>"
                + "</head><body></body></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of("index.html", entry)));
        assertTrue(out.contains("https://cdn.example.com/x.css"));
        assertTrue(out.contains("https://unpkg.com/react"));
    }

    @Test
    void inlineContentCannotCloseOuterTag() {
        String entry = "<html><body><script src=\"app.js\"></script></body></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of(
                "index.html", entry,
                "app.js", "var s = '</script>' + 1;")));
        assertTrue(out.contains("<\\/script"), "内联内容中的闭合标签必须被转义");
    }

    @Test
    void missingEntryFallsBackToFirstFile() {
        String html = "<html><body>legacy</body></html>";
        assertEquals(html, PreviewAssembler.assemble("index.html", files(Map.of("page.html", html))));
    }

    @Test
    void emptyFilesReturnsEmpty() {
        assertEquals("", PreviewAssembler.assemble("index.html", List.of()));
        assertEquals("", PreviewAssembler.assemble("index.html", null));
    }

    @Test
    void queryAndAnchorStripped() {
        String entry = "<html><head><link rel=\"stylesheet\" href=\"styles.css?v=2#top\"></head></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of(
                "index.html", entry,
                "styles.css", "p{}")));
        assertTrue(out.contains("p{}"), "带查询串/锚点的相对引用也应内联");
    }

    @Test
    void referenceCycleTerminates() {
        String entry = "<html><head><link rel=\"stylesheet\" href=\"styles.css\"></head></html>";
        String out = PreviewAssembler.assemble("index.html", files(Map.of(
                "index.html", entry,
                "styles.css", "@import url(\"styles.css\");")));
        assertNull(null); // 不抛异常即通过
        assertTrue(out.contains("@import"));
    }
}

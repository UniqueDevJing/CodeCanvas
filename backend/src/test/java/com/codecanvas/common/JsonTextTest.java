package com.codecanvas.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** C3 模型回复 JSON 提取测试。 */
class JsonTextTest {

    record Sample(String name, int size) {
    }

    @Test
    void bareJson() {
        Sample s = JsonText.extract("{\"name\":\"a\",\"size\":1}", Sample.class);
        assertEquals("a", s.name());
        assertEquals(1, s.size());
    }

    @Test
    void fencedJson() {
        String text = """
                前言说明
                ```json
                {"name":"b","size":2}
                ```
                后记
                """;
        assertEquals("b", JsonText.extract(text, Sample.class).name());
    }

    @Test
    void jsonWithSurroundingText() {
        Sample s = JsonText.extract("结果如下：{\"name\":\"c\",\"size\":3}，请查收。", Sample.class);
        assertEquals("c", s.name());
    }

    @Test
    void bracesInsideStringsDoNotBreakBalance() {
        String text = "{\"name\":\"a{b}c\",\"size\":4}";
        assertEquals(4, JsonText.extract(text, Sample.class).size());
    }

    @Test
    void escapedQuotesInsideStrings() {
        String raw = "{\"name\":\"say \\\"hi\\\"\",\"size\":5}";
        assertEquals(5, JsonText.extract(raw, Sample.class).size());
    }

    @Test
    void invalidReturnsNull() {
        assertNull(JsonText.extract(null, Sample.class));
        assertNull(JsonText.extract("", Sample.class));
        assertNull(JsonText.extract("no json here", Sample.class));
        assertNull(JsonText.extract("{broken", Sample.class));
    }

    @Test
    void missingFieldDefaults() {
        Sample s = JsonText.extract("{\"name\":\"only\"}", Sample.class);
        assertEquals("only", s.name());
        assertEquals(0, s.size());
    }
}

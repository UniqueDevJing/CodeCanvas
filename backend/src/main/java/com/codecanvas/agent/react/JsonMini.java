package com.codecanvas.agent.react;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 解析（C5）：仅用于 ReAct 的 Action Input（一层对象、字符串/数字/布尔值）。
 * 真实运行环境有 Jackson，但本类刻意零依赖——循环逻辑要在 no-Maven harness 里可测。
 */
final class JsonMini {

    private JsonMini() {
    }

    static Map<String, Object> parse(String s) {
        return new P(s.trim()).object();
    }

    private static final class P {
        private final String s;
        private int i;

        P(String s) {
            this.s = s;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skip();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skip();
                String k = string();
                skip();
                expect(':');
                skip();
                m.put(k, value());
                skip();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return m;
                } else {
                    throw new IllegalArgumentException("JSON 语法错误 @" + i);
                }
            }
        }

        private Object value() {
            skip();
            char c = peek();
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String raw = s.substring(start, i).trim();
            if ("true".equals(raw)) {
                return Boolean.TRUE;
            }
            if ("false".equals(raw)) {
                return Boolean.FALSE;
            }
            if ("null".equals(raw)) {
                return null;
            }
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                try {
                    return Double.valueOf(raw);
                } catch (NumberFormatException e2) {
                    return raw;
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 提前结束");
            }
            return s.charAt(i);
        }

        private void expect(char c) {
            skip();
            if (peek() != c) {
                throw new IllegalArgumentException("期望 " + c + "，实际 " + peek());
            }
            i++;
        }
    }
}

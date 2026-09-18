package com.codecanvas.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成代码的静态校验。
 *
 * <p>存在的原因：模型输出的代码"看起来对"但经常跑不起来。在把结果交给用户之前先做一轮
 * 机器可判定的检查，把问题变成明确的错误信息回注给模型去修——这就是自检闭环的入口。
 *
 * <p>检查项（都是确定性规则，不依赖模型）：
 * <ol>
 *   <li>输出是否为空、是否残留 Markdown 围栏或占位说明</li>
 *   <li>HTML 骨架是否存在，标签是否闭合（跳过 script / style / 注释内容）</li>
 *   <li>script 里的括号是否配平（跳过字符串与注释）</li>
 * </ol>
 */
public final class HtmlValidator {

    private HtmlValidator() {
    }

    /** 常见自闭合标签。 */
    private static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    private static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("<script\\b[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style\\b[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_INNER = Pattern.compile("<script\\b[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)");

    private static final List<String> PLACEHOLDERS = List.of("此处省略", "其他功能类似", "省略部分", "TODO:", "TODO：");

    public record Issue(String type, String message) {
    }

    /**
     * 单项检查结果，用于界面上的"质检报告"。
     *
     * @param id     稳定标识（structure / tags / script / content），前端据此映射图标
     * @param label  给用户看的中文名
     * @param passed 是否通过
     * @param detail 通过时是结论，未通过时是具体问题
     */
    public record CheckResult(String id, String label, boolean passed, String detail) {
    }

    /**
     * 把校验结果整理成固定几项检查。
     *
     * <p>为什么单独有这个入口：{@link #validate} 返回的是扁平问题列表，
     * 直接丢给界面就是一串用户看不懂的条目。这里归到他能理解的四个维度上，
     * 而且**四项总是全部返回**（通过也返回），界面上报告的结构才是稳定的。
     */
    public static List<CheckResult> report(String html) {
        List<Issue> issues = validate(html);
        return List.of(
                check("structure", "页面结构", issues, "HTML 骨架完整", "STRUCTURE"),
                check("tags", "标签闭合", issues, "所有标签都正确闭合", "TAG"),
                check("script", "脚本语法", issues, "脚本块括号配平", "SCRIPT"),
                check("content", "内容完整", issues, "没有占位和残留标记", "EMPTY", "FENCE", "PLACEHOLDER"));
    }

    private static CheckResult check(String id, String label, List<Issue> issues,
                                     String okText, String... types) {
        List<String> wanted = List.of(types);
        for (Issue issue : issues) {
            if (wanted.contains(issue.type())) {
                return new CheckResult(id, label, false, issue.message());
            }
        }
        return new CheckResult(id, label, true, okText);
    }

    public static List<Issue> validate(String html) {
        List<Issue> issues = new ArrayList<>();
        if (html == null || html.isBlank()) {
            issues.add(new Issue("EMPTY", "生成结果为空"));
            return issues;
        }

        String trimmed = html.trim();
        if (trimmed.contains("```")) {
            issues.add(new Issue("FENCE", "结果里残留了 Markdown 代码围栏（```），应该只输出纯 HTML"));
        }
        for (String placeholder : PLACEHOLDERS) {
            if (trimmed.contains(placeholder)) {
                issues.add(new Issue("PLACEHOLDER", "结果里存在占位内容「" + placeholder + "」，代码必须完整"));
                break;
            }
        }
        String lower = trimmed.toLowerCase();
        if (!lower.contains("<html") || !lower.contains("</html>")) {
            issues.add(new Issue("STRUCTURE", "缺少完整的 <html> 结构"));
        }
        if (!lower.contains("<body")) {
            issues.add(new Issue("STRUCTURE", "缺少 <body> 标签"));
        }

        issues.addAll(checkTagBalance(trimmed));
        issues.addAll(checkScriptBalance(trimmed));
        return issues;
    }

    /** 标签闭合检查：忽略注释、script 与 style 的内部内容。 */
    private static List<Issue> checkTagBalance(String html) {
        List<Issue> issues = new ArrayList<>();
        String skeleton = SCRIPT_BLOCK.matcher(html).replaceAll("<script></script>");
        skeleton = STYLE_BLOCK.matcher(skeleton).replaceAll("<style></style>");
        skeleton = COMMENT.matcher(skeleton).replaceAll("");

        Deque<String> stack = new ArrayDeque<>();
        Matcher matcher = TAG.matcher(skeleton);
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String name = matcher.group(2).toLowerCase();
            if (VOID_TAGS.contains(name)) {
                continue;
            }
            // 判断是否自闭合：看标签结束符前一个字符
            int end = skeleton.indexOf('>', matcher.end());
            if (end > 0 && skeleton.charAt(end - 1) == '/') {
                continue;
            }
            if (closing) {
                int distance = distanceFromTop(stack, name);
                if (distance < 0) {
                    issues.add(new Issue("TAG", "出现了多余的闭合标签 </" + name + ">"));
                    continue;
                }
                // 闭合标签前面还有没关掉的标签：把它们列出来，模型才知道该补什么
                if (distance > 0) {
                    List<String> unclosed = new ArrayList<>();
                    for (int i = 0; i < distance; i++) {
                        unclosed.add(stack.pop());
                    }
                    issues.add(new Issue("TAG", "以下标签没有闭合：" + String.join(", ", unclosed)));
                }
                stack.pop();
            } else {
                stack.push(name);
            }
        }
        if (!stack.isEmpty()) {
            issues.add(new Issue("TAG", "以下标签没有闭合：" + String.join(", ", stack)));
        }
        return issues;
    }

    /** 目标标签距离栈顶的位置；不在栈里返回 -1。 */
    private static int distanceFromTop(Deque<String> stack, String name) {
        int index = 0;
        for (String item : stack) {
            if (item.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** script 内括号配平检查：先剔除字符串与注释，再做计数。 */
    private static List<Issue> checkScriptBalance(String html) {
        List<Issue> issues = new ArrayList<>();
        Matcher matcher = SCRIPT_INNER.matcher(html);
        int blockIndex = 0;
        while (matcher.find()) {
            blockIndex++;
            String code = stripStringsAndComments(matcher.group(1));
            int round = 0;
            int curly = 0;
            int square = 0;
            for (int i = 0; i < code.length(); i++) {
                switch (code.charAt(i)) {
                    case '(' -> round++;
                    case ')' -> round--;
                    case '{' -> curly++;
                    case '}' -> curly--;
                    case '[' -> square++;
                    case ']' -> square--;
                    default -> {
                    }
                }
            }
            if (round != 0 || curly != 0 || square != 0) {
                issues.add(new Issue("SCRIPT", "第 " + blockIndex
                        + " 段脚本的括号不配平（小括号差 " + round + "，花括号差 " + curly
                        + "，方括号差 " + square + "），脚本会直接报语法错误"));
            }
        }
        return issues;
    }

    /** 去掉字符串字面量与注释，避免其中的括号干扰计数。 */
    private static String stripStringsAndComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < code.length() && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(code.length(), i + 2);
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < code.length()) {
                    char inner = code.charAt(i);
                    if (inner == '\\') {
                        i += 2;
                        continue;
                    }
                    if (inner == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 把问题清单转成给模型看的错误文本。 */
    public static String describe(List<Issue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            sb.append(i + 1).append(". [").append(issues.get(i).type()).append("] ")
                    .append(issues.get(i).message()).append('\n');
        }
        return sb.toString().trim();
    }
}

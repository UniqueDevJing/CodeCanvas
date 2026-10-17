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
 *   <li>script 里的括号是否配平（跳过字符串、模板字符串、注释与正则字面量）</li>
 * </ol>
 *
 * <p><b>误报比漏报更贵</b>：漏报只是少拦一次，误报却会让自检闭环去做无意义的重试——
 * 花掉真实的模型费用，用户还要多等。所以宁可规则保守，也不能乱报。
 * 这里有两处专门为"别误报"加的识别逻辑：正则字面量（{@link #regexCanStart}）与
 * 模板字符串插值（{@link #skipTemplateLiteral}）——两者都是实测踩出来的，
 * 前者让一个正常页面白等 193 秒，后者让另一个页面连做 3 轮无效修复后仍判不通过。
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

    /**
     * 剥离注释后再做占位符检测。
     *
     * <p>原先对整份 HTML 做 {@code contains} 纯文本匹配，会把**注释里的 TODO**
     * 当成未实现的占位内容：
     * <pre>
     *   &lt;script&gt;// TODO: 后续接入支付&lt;/script&gt;
     *   &lt;!-- TODO: 补充响应式断点 --&gt;
     * </pre>
     * 这两种都是开发者留下的正常注释，不是「模型偷懒没写完」。
     *
     * <p>误报的后果比漏报更糟：校验器判 PLACEHOLDER 失败 → 进入自检修复闭环
     * → 模型被要求「补全占位内容」→ 为消除告警而编造并不需要的功能代码。
     * 这与「首轮通过率」的目标直接冲突。
     *
     * <p><b>HTML 标签外不做 {@code //} 剥离</b>：{@code https://cdn...} 这类 URL
     * 含有 {@code //}，若一并当行注释处理会吃掉 URL 之后的内容，
     * 破坏后续的标签闭合检查。JS/CSS 的 {@code //} 注释只在 script/style 块内处理。
     */
    private static String stripComments(String html) {
        StringBuilder out = new StringBuilder(html.length());
        Matcher mComment = COMMENT.matcher(html);
        int last = 0;
        while (mComment.find()) {
            out.append(html, last, mComment.start()).append(' ');
            last = mComment.end();
        }
        out.append(html.substring(last));

        // script / style 块内再剥离 JS 行注释与块注释
        String skeleton = out.toString();
        Matcher mScript = SCRIPT_BLOCK.matcher(skeleton);
        StringBuilder rebuilt = new StringBuilder();
        last = 0;
        while (mScript.find()) {
            rebuilt.append(skeleton, last, mScript.start());
            rebuilt.append("<script>").append(stripJsComments(mScript.group())).append("</script>");
            last = mScript.end();
        }
        rebuilt.append(skeleton.substring(last));

        skeleton = rebuilt.toString();
        Matcher mStyle = STYLE_BLOCK.matcher(skeleton);
        rebuilt = new StringBuilder();
        last = 0;
        while (mStyle.find()) {
            rebuilt.append(skeleton, last, mStyle.start());
            rebuilt.append("<style>").append(stripJsComments(mStyle.group())).append("</style>");
            last = mStyle.end();
        }
        rebuilt.append(skeleton.substring(last));
        return rebuilt.toString();
    }

    /** 剥离一段 JS/CSS 内部的 {@code //} 行注释与 {@code /* *}{@code /} 块注释 */
    private static String stripJsComments(String block) {
        StringBuilder out = new StringBuilder(block.length());
        int i = 0;
        while (i < block.length()) {
            if (block.startsWith("//", i)) {
                int end = block.indexOf('\n', i);
                i = end < 0 ? block.length() : end;
                out.append(' ');
                continue;
            }
            if (block.startsWith("/*", i)) {
                int end = block.indexOf("*/", i + 2);
                i = end < 0 ? block.length() : end + 2;
                out.append(' ');
                continue;
            }
            out.append(block.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * 这些关键字后面跟的 {@code /} 是正则字面量，不是除号。
     *
     * <p>不做这个判断的话，{@code return /a/} 会被当成除法，正则里的括号就会被计入计数。
     */
    private static final Set<String> REGEX_PRECEDING_KEYWORDS = Set.of(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
            "case", "do", "else", "yield", "await", "throw");

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
        // 剥离注释后再检测占位：注释里的 TODO 是正常开发者注释，不是未实现的占位
        String withoutComments = stripComments(trimmed);
        for (String placeholder : PLACEHOLDERS) {
            if (withoutComments.contains(placeholder)) {
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

    /** 去掉字符串字面量、注释与正则字面量，避免其中的括号干扰计数。 */
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
            } else if (c == '`') {
                i = skipTemplateLiteral(code, i);
            } else if (c == '\'' || c == '"') {
                i = skipSimpleString(code, i);
            } else if (c == '/' && regexCanStart(code, i)) {
                i = skipRegexLiteral(code, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 跳过一段普通字符串字面量，返回新位置。 */
    private static int skipSimpleString(String code, int quoteIndex) {
        char quote = code.charAt(quoteIndex);
        int i = quoteIndex + 1;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /**
     * 跳过一段模板字符串，返回新位置。
     *
     * <p><b>为什么不能按普通字符串处理</b>：模板串里可以有 {@code ${...}} 插值，
     * 而插值内部**又能再嵌模板串**。按普通字符串"找到下一个反引号就收工"的写法，
     * 会停在内层模板的开引号上，后面的代码全被当成普通代码——
     * 于是 {@code </div>} 里的斜杠被当成正则开始，计数彻底错位。
     *
     * <p>实测案例（记账本页面的渲染函数）：
     * <pre>
     * `&lt;div class="item ${t.type}"&gt;
     *    ${t.note ? `&lt;span&gt;${t.note}&lt;/span&gt;` : ''}
     *  &lt;/div&gt;`
     * </pre>
     * 这段代码完全合法（{@code node --check} 通过），却让校验器报了三次"括号不配平"。
     *
     * <p>所以这里用深度计数：只有配对的花括号才结束插值，插值内部遇到反引号、
     * 引号、注释、正则都递归跳过。
     */
    private static int skipTemplateLiteral(String code, int backtickIndex) {
        int i = backtickIndex + 1;
        int depth = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (depth == 0) {
                if (c == '`') {
                    return i + 1;
                }
                if (c == '$' && i + 1 < code.length() && code.charAt(i + 1) == '{') {
                    depth = 1;
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }
            // 以下处于 ${ } 内部
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                i++;
            } else if (c == '`') {
                i = skipTemplateLiteral(code, i);
            } else if (c == '\'' || c == '"') {
                i = skipSimpleString(code, i);
            } else if (c == '/' && next == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < code.length() && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(code.length(), i + 2);
            } else if (c == '/' && regexCanStart(code, i)) {
                i = skipRegexLiteral(code, i);
            } else {
                i++;
            }
        }
        return i;
    }

    /**
     * 这个 {@code /} 是正则字面量的开始，还是除号？
     *
     * <p>为什么必须区分：正则里允许出现引号（CSV 处理里的 {@code /^"|"$/g} 就是典型），
     * 若把它当普通代码，里面的引号会被当成字符串开始，**后面的代码会被整段吞掉**，
     * 括号计数随之错位。实测就踩过：一个记账本页面的脚本被判成"括号不配平"，
     * 连续 3 次修复都没通过，白等 193 秒——而代码其实完全正常。
     *
     * <p>判据用业界通行的启发式：往前找最近的**有效字符**，
     * 只有当它是"操作数结尾"（标识符、数字、{@code ) ] }}）时才是除号；
     * 但 {@code return /re/} 这类关键字后面跟的是正则，单独放行。
     */
    private static boolean regexCanStart(String code, int slashIndex) {
        int i = slashIndex - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return true;
        }
        char prev = code.charAt(i);
        boolean operandLike = Character.isLetterOrDigit(prev) || prev == '_' || prev == '$'
                || prev == ')' || prev == ']' || prev == '}'
                || prev == '\'' || prev == '"' || prev == '`';
        if (!operandLike) {
            return true;
        }
        if (Character.isLetterOrDigit(prev)) {
            int end = i + 1;
            int start = end;
            while (start > 0 && (Character.isLetterOrDigit(code.charAt(start - 1))
                    || code.charAt(start - 1) == '_' || code.charAt(start - 1) == '$')) {
                start--;
            }
            return REGEX_PRECEDING_KEYWORDS.contains(code.substring(start, end));
        }
        return false;
    }

    /** 跳过一段正则字面量（含末尾的 g / i / m 等修饰符），返回新位置。 */
    private static int skipRegexLiteral(String code, int slashIndex) {
        int i = slashIndex + 1;
        boolean inClass = false;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\n') {
                break;              // 正则不能跨行，说明前面的判断是误判，就地收手
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                i++;
                break;
            }
            i++;
        }
        while (i < code.length() && Character.isLetter(code.charAt(i))) {
            i++;                    // 修饰符
        }
        return i;
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

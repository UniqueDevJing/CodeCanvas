package com.codecanvas.llm.mock;

import com.codecanvas.common.TokenEstimator;
import com.codecanvas.llm.LlmProvider;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.LlmResponse;
import com.codecanvas.llm.StreamListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 离线模拟引擎。
 *
 * <p>存在的原因：模型密钥会过期、会欠费、公司内网会不通，而任何一次演示都不该因此挂掉。
 * 它实现了与真实模型完全相同的 {@link LlmProvider} 契约，所以整条编排链路（流式输出、
 * 静态校验、自检修复、版本快照）在没有网络的情况下依然会被真实地跑一遍。
 *
 * <p>能力边界要说清楚：它能产出结构完整、可交互的页面，也支持一批常见的修改指令
 * （换主色、切深色、改圆角/字号、改标题、追加模块）和两类定点修复（沙箱存储被禁、变量未定义）。
 * 超出这个范围的复杂需求需要接真实模型。
 */
@Component
public class MockLlmProvider implements LlmProvider {

    private static final String ID = "mock";

    /** 常见颜色词 → 主色值。 */
    private static final Map<String, String> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("蓝", "#2563eb");
        COLORS.put("天蓝", "#0ea5e9");
        COLORS.put("红", "#dc2626");
        COLORS.put("绿", "#059669");
        COLORS.put("橙", "#ea580c");
        COLORS.put("黄", "#ca8a04");
        COLORS.put("紫", "#7c3aed");
        COLORS.put("粉", "#db2777");
        COLORS.put("青", "#0891b2");
        COLORS.put("棕", "#92400e");
        COLORS.put("咖", "#92400e");
        COLORS.put("黑", "#111111");
        COLORS.put("灰", "#6b7280");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String text = produce(request);
        return LlmResponse.of(text,
                TokenEstimator.estimate(request.systemPrompt() + request.userPrompt()),
                TokenEstimator.estimate(text),
                request.model(),
                ID,
                true);
    }

    @Override
    public void chatStream(LlmRequest request, StreamListener listener) {
        String text = produce(request);
        StringBuilder sent = new StringBuilder();
        int step = 48;
        try {
            for (int i = 0; i < text.length(); i += step) {
                String chunk = text.substring(i, Math.min(text.length(), i + step));
                sent.append(chunk);
                listener.onDelta(chunk);
                Thread.sleep(8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.onComplete(LlmResponse.of(text,
                TokenEstimator.estimate(request.systemPrompt() + request.userPrompt()),
                TokenEstimator.estimate(text),
                request.model(),
                ID,
                true));
    }

    /** 按调用目的产出内容。 */
    private String produce(LlmRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        return switch (request.purpose()) {
            case PRD -> buildPrd(extract(prompt, "【原始需求】", "【已确认需求】"));
            case CODE -> {
                String requirement = extract(prompt, "【原始需求】", "【当前代码开始】");
                if (requirement.isBlank()) {
                    requirement = extract(prompt, "【本次修改需求】", "【当前代码开始】");
                }
                String archetype = MockAppTemplates.detect(requirement);
                yield MockAppTemplates.document(MockAppTemplates.build(archetype, requirement), null);
            }
            case MODIFY -> applyModification(section(prompt, "【当前代码开始】", "【当前代码结束】"),
                    extract(prompt, "【本次修改需求】", "【当前代码开始】"));
            case FIX -> applyRepair(section(prompt, "【当前代码开始】", "【当前代码结束】"),
                    extract(prompt, "【错误信息】", "【当前代码开始】"));
        };
    }

    /** 结构化需求：把一句话需求落成可复用的 PRD，后续每轮迭代都拿它当稳定上下文。 */
    private String buildPrd(String requirement) {
        String topic = requirement == null || requirement.isBlank() ? "一个简单工具" : requirement.trim();
        String archetype = MockAppTemplates.detect(topic);
        String feature = switch (archetype) {
            case "expense" -> "记一笔支出、查看合计、删除记录";
            case "todo" -> "添加待办、勾选完成、清除已完成";
            case "pomodoro" -> "开始/暂停倒计时、重置、统计完成轮次";
            case "kanban" -> "三列任务分组、推进任务状态、新建任务";
            case "calculator" -> "四则运算、连续计算、清空与回退";
            case "landing" -> "首屏卖点、功能分区、行动按钮";
            case "dashboard" -> "关键指标卡、趋势柱状图、数值动效";
            default -> "数据录入、列表展示、即时交互反馈";
        };
        String style = archetype.equals("landing") ? "简洁克制，突出行动按钮"
                : "浅色卡片式布局，单一强调色，信息密度适中";
        return """
                目标：%s
                目标用户：需要快速记录和查看信息的个人用户
                核心能力：%s
                数据保存：需要在本地保存用户数据，刷新页面后不丢失
                视觉风格：%s
                交付形态：单文件 HTML，样式与脚本内联，打开即可运行
                验收要点：核心操作三步内可完成；页面在手机宽度下不出现横向滚动
                """.formatted(topic, feature, style).trim();
    }

    /** 增量修改：识别一批常见修改指令，直接改在代码上。 */
    private String applyModification(String code, String requirement) {
        if (code == null || code.isBlank()) {
            return MockAppTemplates.document(
                    MockAppTemplates.build(MockAppTemplates.detect(requirement), requirement), null);
        }
        String result = code;
        String req = requirement == null ? "" : requirement;

        String color = detectColor(req);
        if (color != null) {
            result = replaceAccent(result, color);
        }
        if (req.contains("深色") || req.contains("暗色") || req.contains("夜间")) {
            result = toDarkTheme(result);
        }
        if (req.contains("圆角")) {
            String radius = req.contains("小") || req.contains("方") ? "4px"
                    : (req.contains("大") || req.contains("圆") ? "20px" : "12px");
            result = result.replaceAll("--radius:\\s*\\d+px", "--radius:" + radius);
        }
        if (req.contains("字号") || req.contains("字体大") || req.contains("文字大")) {
            result = req.contains("小")
                    ? result.replace("font-size:14px;line-height:1.6", "font-size:13px;line-height:1.6")
                    : result.replace("font-size:14px;line-height:1.6", "font-size:16px;line-height:1.6");
        }
        String title = detectTitle(req);
        if (title != null) {
            result = result.replaceAll("<h1([^>]*)>[^<]*</h1>", "<h1$1>" + title + "</h1>");
        }

        boolean changed = !result.equals(code);
        if (!changed) {
            result = appendSection(result, req);
        }
        return result;
    }

    /** 定点修复：针对校验或运行时错误做最小改动，而不是整页重写。 */
    private String applyRepair(String code, String errorText) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String error = errorText == null ? "" : errorText;
        String lower = error.toLowerCase();
        String result = code;

        if (lower.contains("securityerror") || lower.contains("localstorage")
                || lower.contains("storage") || lower.contains("denied")) {
            result = fixSandboxStorage(result);
        }

        result = fixUnclosedTags(result, error);

        Matcher undefined = Pattern.compile("(\\w+) is not defined").matcher(error);
        if (undefined.find()) {
            result = defineGlobal(result, undefined.group(1));
        }

        if (result.equals(code)) {
            result = defineGlobal(result, "__cc_guard");
        }
        return result;
    }

    /** 结构问题：按校验器报出的未闭合标签，倒序补上闭合标签。 */
    private String fixUnclosedTags(String html, String error) {
        Matcher matcher = Pattern.compile("以下标签没有闭合：(.+?)(?:\\n|$)").matcher(error);
        if (!matcher.find()) {
            return html;
        }
        String[] tags = matcher.group(1).split("[,，]");
        StringBuilder closes = new StringBuilder();
        for (int i = tags.length - 1; i >= 0; i--) {
            String tag = tags[i].trim().replaceAll("[^a-zA-Z0-9-]", "");
            if (!tag.isEmpty()) {
                closes.append("</").append(tag).append(">\n");
            }
        }
        if (closes.length() == 0) {
            return html;
        }
        int index = html.toLowerCase().lastIndexOf("</body>");
        return index < 0
                ? html + "\n" + closes
                : html.substring(0, index) + closes + html.substring(index);
    }

    /**
     * 修复沙箱内 localStorage 不可用的问题。
     *
     * <p>预览 iframe 使用 opaque origin（为了隔离，不给 allow-same-origin），
     * 浏览器会禁止访问 localStorage 并抛 SecurityError。这里注入一个降级存储：
     * 能访问就用真的，不能访问就退回内存，保证功能不中断。
     */
    private String fixSandboxStorage(String html) {
        if (html.contains("__ccStorage")) {
            return html;
        }
        String shim = """
                <script>
                var __ccStorage = (function () {
                  try {
                    var probe = '__cc_probe__';
                    window.localStorage.setItem(probe, '1');
                    window.localStorage.removeItem(probe);
                    return window.localStorage;
                  } catch (e) {
                    var memory = {};
                    return {
                      getItem: function (key) {
                        return Object.prototype.hasOwnProperty.call(memory, key) ? memory[key] : null;
                      },
                      setItem: function (key, value) { memory[key] = String(value); },
                      removeItem: function (key) { delete memory[key]; },
                      clear: function () { memory = {}; }
                    };
                  }
                })();
                </script>
                """;
        // 先替换引用，再插入垫片，避免垫片自身的探测逻辑被改写
        String replaced = html.replace("localStorage", "__ccStorage");
        return injectAfterBody(replaced, shim);
    }

    /** 把未定义变量补一个安全声明，避免脚本整体中断。 */
    private String defineGlobal(String html, String name) {
        if (html.contains("var " + name + " =")) {
            return html;
        }
        String guard = "<script>try{if(typeof " + name + " === 'undefined'){window." + name + " = null;}}catch(e){}</script>";
        return injectAfterBody(html, guard);
    }

    private String injectAfterBody(String html, String snippet) {
        String lower = html.toLowerCase();
        int index = lower.indexOf("<body");
        if (index >= 0) {
            int close = html.indexOf('>', index);
            if (close > 0) {
                return html.substring(0, close + 1) + "\n" + snippet + html.substring(close + 1);
            }
        }
        return snippet + "\n" + html;
    }

    /** 追加一个可见模块，让"这一轮改了什么"在页面上能被看见。 */
    private String appendSection(String html, String requirement) {
        String text = requirement == null || requirement.isBlank() ? "本次迭代" : requirement.trim();
        String block = """
                <div class="card" style="border-left:3px solid var(--accent)">
                  <h2>本次迭代</h2>
                  <p class="muted" style="margin-top:8px">%s</p>
                </div>
                """.formatted(text.replace("<", "&lt;"));
        int index = html.indexOf("class=\"wrap");
        if (index < 0) {
            return html.replace("</body>", block + "</body>");
        }
        int close = html.indexOf('>', index);
        if (close < 0) {
            return html;
        }
        return html.substring(0, close + 1) + "\n" + block + html.substring(close + 1);
    }

    private String replaceAccent(String html, String color) {
        if (!html.contains("--accent:")) {
            return html;
        }
        return html.replaceAll("--accent:\\s*#[0-9a-fA-F]{3,8}", "--accent:" + color);
    }

    private String toDarkTheme(String html) {
        return html
                .replace("--bg:#f6f6f4", "--bg:#111110")
                .replace("--panel:#ffffff", "--panel:#1c1c1b")
                .replace("--text:#1a1a18", "--text:#f2f2f0")
                .replace("--muted:#8a8a85", "--muted:#9c9c96")
                .replace("--border:#e8e8e5", "--border:#2f2f2c")
                .replace("--accent-soft:#eef0fe", "--accent-soft:#26264a");
    }

    private String detectColor(String text) {
        if (text == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : COLORS.entrySet()) {
            if (text.contains(entry.getKey() + "色") || text.contains("换成" + entry.getKey())
                    || text.contains("改成" + entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String detectTitle(String text) {
        if (text == null) {
            return null;
        }
        String[] patterns = {"标题改成", "标题改为", "标题是", "名字叫", "名字改成", "叫做"};
        for (String marker : patterns) {
            int index = text.indexOf(marker);
            if (index >= 0) {
                String value = text.substring(index + marker.length()).trim();
                value = value.split("[，。,.！!?？]")[0].trim();
                if (!value.isEmpty() && value.length() <= 24) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 取出两个标记之间的内容。 */
    private String section(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) {
            return "";
        }
        from += start.length();
        int to = text.indexOf(end, from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    /** 取标记后到行尾/下一标记前的内容。 */
    private String extract(String text, String start, String end) {
        String value = section(text, start, end);
        return value.replace("\r", "").trim();
    }
}

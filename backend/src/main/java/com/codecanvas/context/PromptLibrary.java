package com.codecanvas.context;

import com.codecanvas.project.ProjectFile;
import com.codecanvas.project.model.ProjectPlan;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词组装。
 *
 * <p>所有提示词都在这里集中管理，好处是：改提示词不用翻遍业务代码，且每个阶段的输入形状是固定的。
 * 提示词里用【】标记分节，编排层与离线引擎都按这些标记取内容，避免靠自然语言猜结构。
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    // ==================== 系统提示词 ====================

    public static final String PRD_SYSTEM = """
            你是一名需求分析师。用户会用一句话描述他想要的软件。
            请把它整理成结构化需求文档，只输出以下字段，不要输出任何解释性文字：
            目标 / 目标用户 / 核心能力 / 数据保存 / 视觉风格 / 交付形态 / 验收要点
            要求：判断要具体，不要写"界面友好"这类空话；篇幅控制在 200 字内。
            """;

    public static final String CODE_SYSTEM = """
            你是一名资深前端工程师。请根据结构化需求，生成一个完整可运行的单文件网页应用。

            硬性约束：
            1. 只输出 HTML 代码本身，不要 Markdown 代码围栏，不要任何解释文字。
            2. CSS 和 JavaScript 全部内联在同一个文件里，不引用任何外部资源（不要 CDN、不要外链字体和图片）。
            3. 界面语言用简体中文，视觉风格简洁克制：浅色卡片式布局，单一强调色，信息密度适中。
            4. 必须响应式，手机宽度下不出现横向滚动。
            5. 主题色统一定义为 CSS 变量 --accent，方便后续调整。
            6. 需要本地持久化数据时，必须对存储不可用的情况做降级处理：
               优先使用 localStorage，若被浏览器安全策略禁止则退回内存存储，保证功能不中断。
            7. 代码必须是完整的，不允许出现"此处省略""其他功能类似"这类占位。
            """;

    public static final String MODIFY_SYSTEM = """
            你是一名资深前端工程师，正在对已有的单文件网页应用做增量修改。

            硬性约束：
            1. 只输出修改后的完整 HTML 代码本身，不要 Markdown 代码围栏，不要解释文字。
            2. 必须保留已有功能，只改动与本次需求相关的部分，不要顺手重写页面结构。
            3. 如果不确定改动范围，选择改动更小的那个方案。
            4. 代码必须完整，不允许出现占位或省略。
            """;

    public static final String FIX_SYSTEM = """
            你是一名资深前端工程师，正在修复一段报错的网页代码。

            硬性约束：
            1. 只输出修复后的完整 HTML 代码，不要 Markdown 代码围栏，不要解释文字。
            2. 做最小必要修改，只修掉报错相关的问题，不要重写页面。
            3. 如果报错是浏览器安全策略导致的（例如沙箱内 localStorage 不可用），
               请加入降级方案让功能在受限环境下也能工作。
            4. 代码必须完整，不允许出现占位或省略。
            """;

    public static final String ACCEPTANCE_SYSTEM = """
            你是一名测试工程师。根据结构化需求，生成恰好 3 条验收断言，用于客观判定"生成的页面是否完成"。

            只输出一个 JSON 对象，禁止输出任何解释文字或 Markdown 代码围栏，格式：
            {"checks":[{"id":"a1","kind":"dom","target":"<canvas>","description":"页面提供绘画画布"}]}

            字段要求：
            - id 固定为 a1 / a2 / a3；
            - kind 固定为 dom（表示通过检查 HTML 源码判定）；
              仅当某条核心功能无法用任何词面/结构特征表达时，才允许该条用
              kind=semantic（此时 target 填功能的一句话中文描述），最多 1 条；
            - target 是要在 HTML 里查找的具体标记：标签片段（如 <canvas>）、id 或 class（如 id="timer"）、
              或必须出现的关键文案；必须是生成页面几乎必然包含的东西，不要写凭空猜测的类名；
            - description 是给用户看的中文断言描述，一句话，具体不空泛。

            要求：每条断言都必须能通过检查 HTML 源码客观判定；三条合起来要覆盖需求的核心功能；
            不要输出"界面友好""运行流畅"这类无法静态判定的断言。
            """;

    public static final String REACT_SYSTEM = """
            你是一名自主前端工程师（ReAct 模式）。你要通过多步"思考 → 调用工具 → 观察结果"完成一个\
            完整可运行的单文件网页应用，而不是一次性输出全部代码。

            每轮你只能按以下格式输出（严格遵守，除此之外不要输出任何文字）：
            Thought: <一句话思考：当前进度与下一步>
            Action: <工具名，必须来自可用工具列表>
            Action Input: <JSON 参数，如 {"path":"index.html","content":"<html>…完整 HTML…</html>"}>

            当你确认页面已写好且结构完整时，输出：
            Thought: <完成理由>
            Action: finish
            Action Input: {}

            硬性规则：
            1. 每轮只调用一个工具；Action Input 必须是合法 JSON。
            2. 典型路径：write_file 写出完整 HTML → validate_html 检查 → 若有问题 read_file 查看\
            并 write_file 修复 → finish。
            3. write_file 的 content 必须是完整 HTML（从 <html> 到 </html>），禁止占位或省略。
            """;

    // ==================== 用户提示词 ====================

    public static String prdUserPrompt(String requirement) {
        return """
                【原始需求】
                %s
                """.formatted(requirement);
    }

    /** 验收断言生成的用户提示词（C4）：只看结构化需求，断言从规格推导而不是从代码反推 */
    public static String acceptanceUserPrompt(String prd) {
        return """
                【结构化需求】
                %s
                """.formatted(prd);
    }

    /** ReAct 每轮的用户提示词（C5）：任务 + 可用工具 + 迄今为止的执行历史 */
    public static String reactUserPrompt(String task, String toolsDescription, String transcript) {
        return """
                【任务】
                %s

                【可用工具】
                %s

                【执行历史】
                %s
                请输出下一轮 Thought / Action / Action Input。
                """.formatted(task, toolsDescription,
                transcript == null || transcript.isBlank() ? "（尚未开始，请从 write_file 开始）" : transcript);
    }

    public static String codeUserPrompt(String prd, String requirement) {
        return """
                【结构化需求】
                %s

                【原始需求】
                %s
                """.formatted(prd, requirement);
    }

    /**
     * 增量修改的提示词。
     *
     * <p>注意这里注入的是"结构化需求 + 相关文件 + 本轮修改点"，而不是把全部历史对话原样塞进去。
     * 这是长对话下模型还能记住约定的关键。
     */
    public static String modifyUserPrompt(String prd,
                                          String requirement,
                                          List<ProjectFile> files,
                                          String changeSummary,
                                          String truncationNote) {
        return """
                【结构化需求】
                %s

                【历史变更摘要】
                %s

                【本次修改需求】
                %s

                %s
                【当前代码开始】
                %s
                【当前代码结束】
                """.formatted(prd, changeSummary, requirement, truncationNote, joinFiles(files));
    }

    public static String fixUserPrompt(String prd, List<ProjectFile> files, String errorText) {
        return """
                【结构化需求】
                %s

                【错误信息】
                %s

                【当前代码开始】
                %s
                【当前代码结束】
                """.formatted(prd, errorText, joinFiles(files));
    }

    // ==================== C3 多文件项目模型 ====================

    public static final String PLAN_SYSTEM = """
            你是一名资深前端架构师。请把用户需求拆解成一个小型前端项目的文件规划。

            只输出 JSON（不要代码围栏、不要解释），格式：
            {"appName":"应用名(<=16字)","summary":"一句话说明应用做什么","tech":"html 或 react","entry":"入口文件路径","files":[{"path":"文件相对路径","purpose":"这个文件负责什么"}]}

            规划规则：
            1. tech=html 时入口是 index.html，CSS/JS 可拆分文件（如 styles.css / app.js），
               index.html 用相对路径引用它们；不用构建步骤。
            2. tech=react 时必须是无构建方案：index.html 通过 CDN 引入 React、ReactDOM 和 Babel，
               业务代码放在 .jsx 文件里并由 index.html 用 <script type="text/babel" src="..."> 引入；
               数据文件用 .js（Babel 不编译普通 script src）。
            3. 文件数量 2 到 8 个，每个文件职责单一、用途明确，不要为拆而拆。
            4. 文件路径用相对路径、正斜杠，不允许出现 node_modules、src/main.jsx 这类需要构建才能运行的入口。
            """;

    /** 生成规划的用户提示词。 */
    public static String planUserPrompt(String prd, String requirement) {
        return """
                【结构化需求】
                %s

                【原始需求】
                %s
                """.formatted(prd, requirement);
    }

    /** 生成单个文件的用户提示词（按规划逐文件生成）。 */
    public static String planFileUserPrompt(ProjectPlan plan, ProjectPlan.PlanFile target,
                                            List<String> alreadyWritten) {
        StringBuilder sb = new StringBuilder();
        sb.append("【项目规划】\n应用：").append(plan.appName())
                .append("\n说明：").append(plan.summary())
                .append("\n技术形态：").append(plan.tech())
                .append("\n入口文件：").append(plan.entry()).append('\n');
        sb.append("\n【文件清单】\n");
        for (ProjectPlan.PlanFile f : plan.files()) {
            String mark = f.path().equals(target.path()) ? "（本任务要生成的文件）" : "";
            sb.append("- ").append(f.path()).append("：").append(f.purpose()).append(mark).append('\n');
        }
        if (!alreadyWritten.isEmpty()) {
            sb.append("\n【已生成的文件】").append(String.join("、", alreadyWritten))
                    .append("\n引用这些文件时必须使用与清单一致的相对路径。\n");
        }
        sb.append("\n【本任务】\n只生成文件 ").append(target.path())
                .append("（").append(target.purpose()).append("）。");
        if (!plan.entry().equals(target.path())) {
            sb.append("该文件不是入口文件，不要输出 <!DOCTYPE html> 外壳");
            if (plan.isReact()) {
                sb.append("，不要引入 CDN 资源，React 组件用全局变量 React/ReactDOM（入口文件已加载）");
            } else {
                sb.append("，只输出该文件本身的完整内容");
            }
        } else {
            sb.append("这是入口文件，必须是完整可运行的页面");
            if (plan.isReact()) {
                sb.append("，通过 CDN 引入 react/react-dom/babel-standalone，并用 type=\"text/babel\" 的相对路径 script 引入清单里的全部 .jsx 文件");
            } else {
                sb.append("，用相对路径引入清单里的其余文件");
            }
        }
        sb.append("。只输出该文件的完整代码内容，不要代码围栏，不要解释文字。");
        return sb.toString();
    }

    public static final String MULTI_FILE_MODIFY_SYSTEM = """
            你是一名资深前端工程师，正在对已有的多文件网页项目做增量修改。

            硬性约束：
            1. 只输出一个 JSON 对象（不要代码围栏、不要解释文字），格式：
               {"summary":"本轮改了什么(<=50字)","changes":[{"path":"文件路径","action":"CREATE 或 UPDATE 或 DELETE","content":"文件完整内容，DELETE 时省略"}]}
            2. 只提交与本次需求相关的文件：改了哪个文件就提交哪个，未改动的文件不要出现。
            3. content 必须是该文件的完整内容，不允许省略或占位。
            4. 新增文件用 CREATE，删除文件用 DELETE（content 省略），修改用 UPDATE。
            5. 必须保留已有功能，只做与需求相关的最小改动。
            """;

    /** 多文件增量修改的用户提示词。 */
    public static String multiFileModifyUserPrompt(String prd,
                                                   String requirement,
                                                   List<ProjectFile> files,
                                                   String changeSummary) {
        return """
                【结构化需求】
                %s

                【历史变更摘要】
                %s

                【本次修改需求】
                %s

                【当前项目全部文件开始】
                %s
                【当前项目全部文件结束】

                请按系统提示词的 JSON 格式输出本轮变更。
                """.formatted(prd, changeSummary, requirement, joinFiles(files));
    }

    /** 多文件时按路径分节拼装，便于模型定位。 */
    private static String joinFiles(List<ProjectFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        return files.stream()
                .map(file -> files.size() == 1
                        ? file.content()
                        : "// ===== " + file.path() + " =====\n" + file.content())
                .collect(Collectors.joining("\n\n"));
    }
}
